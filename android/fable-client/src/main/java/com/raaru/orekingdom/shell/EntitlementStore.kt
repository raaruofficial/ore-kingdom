package com.raaru.orekingdom.shell

import androidx.compose.runtime.Immutable
import com.raaru.orekingdom.designsystem.components.ComposerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/* ---- Contract shapes (CONTRACT.md §6), field-for-field; decoding is wired at the transport. ---- */

data class Entitlements(
    val role: String,
    val capabilities: Capabilities,
    val expiresAt: String?,
    val founding: Boolean,
    val modules: Map<String, String>,
)

data class Capabilities(
    val post: List<String>,
    val react: List<String>,
    val tip: Boolean,
    val gift: Boolean,
    val commercialPost: Boolean,
    val spotlightBid: Boolean,
    val pumpModule: String,
    val pumpDayIncluded: Boolean,
    val atmUnlockIncluded: Boolean,
    val nightshiftConsole: Boolean,
    val autopilotSurface: Boolean,
    val modLoft: Boolean,
)

data class ChannelDto(
    val id: String,
    val category: String,
    val name: String,
    val kind: String,
    val slowmodeSec: Int,
    val minRoleRead: String,
    val minRolePost: String,
    val position: Int,
)

data class SpotlightPostDto(val authorId: String, val body: String, val commercial: Boolean)

data class SpotlightDto(
    val occupied: Boolean,
    val post: SpotlightPostDto?,
    val windowEndsAt: String?,
    val floorUsd: Int,
)

/** Seam onto the pinned contract's transport. Implemented at repo integration. */
interface ShellTransport {
    /** REST `getEntitlements`. */
    suspend fun getEntitlements(): Entitlements

    /** REST channel list: server-ordered, already entitlement-filtered. */
    suspend fun getChannels(): List<ChannelDto>

    /** REST spotlight state. */
    suspend fun getSpotlight(): SpotlightDto

    /** Payloads of `role.update` and `entitlement.update` frames (handled identically). */
    val entitlementPushes: Flow<Entitlements>

    /** `unsubscribe` frame. */
    suspend fun unsubscribe(channelIds: List<String>)
}

/* ---- Persistence seam ---- */

interface LastVisitedStore {
    fun get(): String?
    fun set(channelId: String)
}

class InMemoryLastVisitedStore : LastVisitedStore {
    @Volatile
    private var channelId: String? = null
    override fun get(): String? = channelId
    override fun set(channelId: String) {
        this.channelId = channelId
    }
}

/* ---- UI state ---- */

enum class RailStatus { Loading, Ready, Error }

enum class ChannelKind { Text, System, StageReserved }

@Immutable
data class RailChannel(
    val id: String,
    val name: String,
    val kind: ChannelKind,
    val unreadCount: Int,
    val composer: ComposerState,
)

@Immutable
data class RailCategory(val title: String, val channels: List<RailChannel>)

@Immutable
data class RailState(
    val status: RailStatus = RailStatus.Loading,
    val categories: List<RailCategory> = emptyList(),
    val lastVisitedChannelId: String? = null,
)

/** `null` at the call site means the spotlight read is still loading. */
@Immutable
sealed interface SpotlightUiState {
    data class Occupied(
        val authorId: String,
        val authorName: String?,
        val body: String,
        val commercial: Boolean,
        val windowEndsAt: String,
        val floorUsd: Int,
    ) : SpotlightUiState

    data class Vacant(val floorUsd: Int) : SpotlightUiState
    object Unavailable : SpotlightUiState
}

private const val KindText = "text"
private const val KindSystem = "system"
private const val KindModule = "module"
private const val RefreshMillis = 45_000L
private const val RetryMillis = 3_000L

/** Channel post classes that no pass grants; these render read-only, never the upsell. */
private val NonPassPostClasses = setOf("house", "system", "commercial", "moderator")

/**
 * Session bootstrap and live entitlement state. Renders from the entitlement read and the
 * server-filtered channel list only; no role comparison happens here. [scope] is expected
 * to be main-confined.
 */
class EntitlementStore(
    private val transport: ShellTransport,
    private val scope: CoroutineScope,
    private val lastVisited: LastVisitedStore = InMemoryLastVisitedStore(),
) {
    private val _entitlements = MutableStateFlow<Entitlements?>(null)
    val entitlements: StateFlow<Entitlements?> = _entitlements.asStateFlow()

    private val _rail = MutableStateFlow(RailState())
    val rail: StateFlow<RailState> = _rail.asStateFlow()

    private val _spotlight = MutableStateFlow<SpotlightUiState?>(null)
    val spotlight: StateFlow<SpotlightUiState?> = _spotlight.asStateFlow()

    private val mutex = Mutex()
    private var channels: List<ChannelDto> = emptyList()
    private var unread: Map<String, Int> = emptyMap()
    private var loaded = false
    private var failed = false
    private var retrySoon = false

    fun start() {
        scope.launch {
            transport.entitlementPushes.collect { push -> mutex.withLock { applyLocked(push) } }
        }
        scope.launch {
            while (isActive) {
                refreshNow()
                delay(if (retrySoon) RetryMillis else RefreshMillis)
            }
        }
    }

    /** Re-read now: retry, reconnect, or a `not_entitled` refusal on send (F-RP-2). */
    fun refresh() {
        scope.launch { refreshNow() }
    }

    fun selectChannel(channelId: String) {
        if (channels.none { it.id == channelId && it.kind != KindModule }) return
        lastVisited.set(channelId)
        publish()
    }

    /** Seam for the message layer; unread counts are local client state. */
    fun setUnread(channelId: String, count: Int) {
        unread = unread + (channelId to count.coerceAtLeast(0))
        publish()
    }

    private suspend fun refreshNow() {
        mutex.withLock {
            val read = attempt { transport.getEntitlements() }
            if (read != null) {
                applyLocked(read)
            } else {
                retrySoon = true
                failed = !loaded
                publish()
            }
        }
        val spot = attempt { transport.getSpotlight() }
        if (spot != null) {
            _spotlight.value = spot.toUi()
        } else if (_spotlight.value == null) {
            _spotlight.value = SpotlightUiState.Unavailable
        }
    }

    private suspend fun applyLocked(read: Entitlements) {
        _entitlements.value = read
        failed = false
        publish() // composers follow the new capability set immediately
        val fresh = attempt { transport.getChannels() }
        if (fresh == null) {
            retrySoon = true
            failed = !loaded
            publish()
            return
        }
        val freshIds = fresh.map { it.id }.toSet()
        val gone = channels.map { it.id }.filterNot { it in freshIds }
        if (gone.isNotEmpty()) attempt { transport.unsubscribe(gone) }
        channels = fresh
        loaded = true
        retrySoon = false
        publish()
    }

    private fun publish() {
        val read = _entitlements.value
        val postable = read?.capabilities?.post.orEmpty().toSet()
        val visible = if (read == null) emptyList() else channels.filter { it.kind != KindModule }
        val categories = visible.groupBy { it.category }.map { (title, list) ->
            RailCategory(
                title = title,
                channels = list.map { channel ->
                    RailChannel(
                        id = channel.id,
                        name = channel.name,
                        kind = when (channel.kind) {
                            KindText -> ChannelKind.Text
                            KindSystem -> ChannelKind.System
                            else -> ChannelKind.StageReserved
                        },
                        unreadCount = unread[channel.id] ?: 0,
                        composer = when {
                            channel.id in postable -> ComposerState.Enabled
                            channel.minRolePost in NonPassPostClasses -> ComposerState.Disabled()
                            else -> ComposerState.Upsell()
                        },
                    )
                },
            )
        }
        val ids = visible.map { it.id }.toSet()
        _rail.value = RailState(
            status = when {
                read != null && loaded -> RailStatus.Ready
                failed -> RailStatus.Error
                else -> RailStatus.Loading
            },
            categories = categories,
            lastVisitedChannelId = lastVisited.get()?.takeIf { it in ids },
        )
    }

    private fun SpotlightDto.toUi(): SpotlightUiState {
        val current = post
        val ends = windowEndsAt
        return if (occupied && current != null && ends != null) {
            SpotlightUiState.Occupied(
                authorId = current.authorId,
                authorName = null, // TODO-CONTRACT: no member lookup shape for the spotlight author
                body = current.body,
                commercial = current.commercial,
                windowEndsAt = ends,
                floorUsd = floorUsd,
            )
        } else {
            SpotlightUiState.Vacant(floorUsd)
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
