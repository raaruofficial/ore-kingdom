package com.raaru.orekingdom.channel

import com.raaru.orekingdom.designsystem.components.OrePresence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** CONTRACT.md frame type names, verbatim. */
object RealtimeFrames {
    const val SUBSCRIBE = "subscribe"
    const val UNSUBSCRIBE = "unsubscribe"
    const val RESUME = "resume"
    const val TYPING = "typing"
    const val PRESENCE_SET = "presence.set"
    const val MESSAGE = "message"
    const val PRESENCE = "presence"
    const val ROLE_UPDATE = "role.update"
    const val ENTITLEMENT_UPDATE = "entitlement.update"
    const val RESYNC = "resync"
    const val RESYNC_BEHIND = "behind"
    const val RESYNC_GAP = "gap"
    const val REPLAY_LIMIT = 500
    const val HISTORY_PAGE = 50
    const val TYPING_TTL_MS = 6_000L
}

/** REST history request: `GET history?channel_id=…&before_seq=…&limit=50`. Null cursor = newest page. */
data class HistoryPageRequest(
    val channelId: String,
    val beforeSeq: Long?,
    val limit: Int = RealtimeFrames.HISTORY_PAGE,
)

/** Requests the host must issue. Payloads mirror the contract shapes; the host writes the frames. */
sealed interface RealtimeOutbound {
    /** `subscribe` → `{ "channel_ids": [...] }` */
    data class Subscribe(val channelIds: List<String>) : RealtimeOutbound
    /** `unsubscribe` → `{ "channel_ids": [...] }` */
    data class Unsubscribe(val channelIds: List<String>) : RealtimeOutbound
    /** `resume` → `{ "last_seq": { "<channel_id>": int } }`; server replays ≤ 500 per channel. */
    data class Resume(val lastSeq: Map<String, Long>) : RealtimeOutbound
    data class History(val request: HistoryPageRequest) : RealtimeOutbound
}

/**
 * History pagination plan for a gap the replay cannot cover, or for a `resync`.
 * Pages are fetched newest → oldest by seq cursor and PREPENDED into the keyed list, so the
 * scroll position does not jump. [replaceWindow]: the fetched newest page replaces the rendered
 * window (resync); backlog is not rendered incrementally, older pages load on scroll.
 */
data class HistoryPlan(
    val channelId: String,
    val beforeSeq: Long?,
    val untilSeq: Long?,
    val replaceWindow: Boolean,
) {
    fun request() = HistoryPageRequest(channelId, beforeSeq)
}

enum class ChannelSyncMode { Live, CatchUp }

data class ChannelSyncState(
    val channelId: String,
    val lastSeq: Long? = null,
    val mode: ChannelSyncMode = ChannelSyncMode.Live,
    val resumeInFlight: Boolean = false,
    val plan: HistoryPlan? = null,
)

/** Presence exactly as the contract Presence shape supplied it. Nothing is inferred. */
data class PresenceUi(
    val userId: String,
    val state: OrePresence,
    val activity: String?,
    val invisible: Boolean,
)

data class RealtimeState(
    val connected: Boolean = false,
    val subscribed: Set<String> = emptySet(),
    val channels: Map<String, ChannelSyncState> = emptyMap(),
    /** channelId → userId → expiry (epoch ms). Transient; never a message, never history. */
    val typing: Map<String, Map<String, Long>> = emptyMap(),
    /** userId → presence. Ephemeral; cleared and rebuilt from the live feed on every connect. */
    val presence: Map<String, PresenceUi> = emptyMap(),
) {
    fun typists(channelId: String): Set<String> = typing[channelId]?.keys.orEmpty()
}

/** Result of an entitlement-driven subscription change. Purge all caches for [removedChannelIds]. */
data class SubscriptionChange(
    val outbound: List<RealtimeOutbound>,
    val removedChannelIds: Set<String>,
)

/** What to do with an incoming `message` frame. */
sealed interface MessageDecision {
    /** Render in seq order. [outbound] is non-empty when a hole below it must be filled by history. */
    data class Accept(val outbound: List<RealtimeOutbound> = emptyList()) : MessageDecision
    /** Already have this seq (replay overlap / own send reconciled by key): never render twice. */
    object Duplicate : MessageDecision
    /** Do not render now; catch-up will deliver it in order. */
    data class Deferred(val outbound: List<RealtimeOutbound> = emptyList()) : MessageDecision
    /** Channel not subscribed/entitled: drop, never render. */
    object Ignore : MessageDecision
}

data class HistoryPageResult(
    val complete: Boolean,
    val replaceWindow: Boolean,
    val next: RealtimeOutbound.History? = null,
)

/**
 * Realtime client-state holder: shapes and transitions only, no sockets, no rendering.
 * The host feeds contract frames in and issues the returned [RealtimeOutbound] requests.
 */
class ChannelRealtime {
    private val lock = Any()
    private val _state = MutableStateFlow(RealtimeState())
    val state: StateFlow<RealtimeState> = _state.asStateFlow()

    private inline fun <T> mutate(block: (RealtimeState) -> Pair<RealtimeState, T>): T = synchronized(lock) {
        val (next, result) = block(_state.value)
        _state.value = next
        result
    }

    /**
     * Applies the readable channel set from the latest entitlement-filtered channel list
     * (bootstrap, `role.update`, `entitlement.update`). Newly gated channels are unsubscribed and
     * their sync, typing and seq state dropped immediately, without waiting for reconnect.
     */
    fun applyEntitledChannels(readableChannelIds: Collection<String>): SubscriptionChange = mutate { s ->
        val target = readableChannelIds.toSet()
        val added = target - s.subscribed
        val removed = s.subscribed - target
        val channels = (s.channels - removed) + added.associateWith { s.channels[it] ?: ChannelSyncState(it) }
        val out = ArrayList<RealtimeOutbound>()
        if (s.connected) {
            if (removed.isNotEmpty()) out += RealtimeOutbound.Unsubscribe(removed.toList())
            if (added.isNotEmpty()) {
                out += RealtimeOutbound.Subscribe(added.toList())
                out += catchUpRequests(added.mapNotNull { channels[it] })
            }
        }
        val marked = channels.mapValues { (id, c) ->
            if (s.connected && id in added && c.lastSeq != null) c.copy(mode = ChannelSyncMode.CatchUp, resumeInFlight = true) else c
        }
        s.copy(subscribed = target, channels = marked, typing = s.typing - removed) to SubscriptionChange(out, removed)
    }

    /** The server refused these ids on `subscribe`: drop them; never render them. */
    fun onSubscribeRefused(channelIds: Collection<String>): Set<String> = mutate { s ->
        val refused = channelIds.toSet() intersect s.subscribed
        s.copy(subscribed = s.subscribed - refused, channels = s.channels - refused, typing = s.typing - refused) to refused
    }

    /** Seeds last-seen seq from the local cache / an initial history page. Never moves backwards. */
    fun seedLastSeq(channelId: String, seq: Long) = mutate { s ->
        val c = s.channels[channelId] ?: return@mutate s to Unit
        if (c.lastSeq != null && c.lastSeq >= seq) s to Unit
        else s.copy(channels = s.channels + (channelId to c.copy(lastSeq = seq))) to Unit
    }

    /**
     * (Re)connected. Presence and typing are discarded and rebuilt from the live feed. Returns
     * `subscribe` for the entitled set, one `resume` carrying last_seq for every channel with a
     * known seq, and a newest-page history request for channels never loaded.
     */
    fun onConnected(): List<RealtimeOutbound> = mutate { s ->
        val ids = s.subscribed.toList()
        if (ids.isEmpty()) return@mutate s.copy(connected = true, typing = emptyMap(), presence = emptyMap()) to emptyList()
        val out = listOf<RealtimeOutbound>(RealtimeOutbound.Subscribe(ids)) + catchUpRequests(s.channels.values)
        val channels = s.channels.mapValues { (_, c) ->
            if (c.lastSeq != null) c.copy(mode = ChannelSyncMode.CatchUp, resumeInFlight = true, plan = null) else c.copy(plan = null)
        }
        s.copy(connected = true, channels = channels, typing = emptyMap(), presence = emptyMap()) to out
    }

    fun onDisconnected() = mutate { s ->
        val channels = s.channels.mapValues { (_, c) -> c.copy(resumeInFlight = false, plan = null) }
        s.copy(connected = false, channels = channels, typing = emptyMap()) to Unit
    }

    private fun catchUpRequests(channels: Collection<ChannelSyncState>): List<RealtimeOutbound> {
        val out = ArrayList<RealtimeOutbound>()
        val known = channels.filter { it.lastSeq != null }.associate { it.channelId to it.lastSeq!! }
        if (known.isNotEmpty()) out += RealtimeOutbound.Resume(known)
        channels.filter { it.lastSeq == null }.forEach { out += RealtimeOutbound.History(HistoryPageRequest(it.channelId, null)) }
        return out
    }

    /**
     * Incoming `message` frame. Strict seq order: contiguous → accept; already seen → duplicate;
     * gap ≤ 500 → `resume` replay (message deferred, replay redelivers it in order); larger gap,
     * or a gap that survives a replay → history pagination plan filling the hole by prepend.
     */
    fun onMessage(channelId: String, seq: Long): MessageDecision = mutate { s ->
        val c = s.channels[channelId] ?: return@mutate s to MessageDecision.Ignore
        fun put(n: ChannelSyncState) = s.copy(channels = s.channels + (channelId to n))
        val last = c.lastSeq
        when {
            last != null && seq <= last -> s to MessageDecision.Duplicate
            c.plan?.replaceWindow == true -> s to MessageDecision.Deferred()
            last == null || seq == last + 1 -> put(
                c.copy(lastSeq = seq, resumeInFlight = false, mode = if (c.plan == null) ChannelSyncMode.Live else ChannelSyncMode.CatchUp),
            ) to MessageDecision.Accept()
            seq - last - 1 <= RealtimeFrames.REPLAY_LIMIT && !c.resumeInFlight && c.plan == null ->
                put(c.copy(mode = ChannelSyncMode.CatchUp, resumeInFlight = true)) to
                    MessageDecision.Deferred(listOf(RealtimeOutbound.Resume(mapOf(channelId to last))))
            else -> {
                val plan = HistoryPlan(channelId, beforeSeq = seq, untilSeq = c.plan?.untilSeq ?: last, replaceWindow = false)
                put(c.copy(lastSeq = seq, mode = ChannelSyncMode.CatchUp, resumeInFlight = false, plan = plan)) to
                    MessageDecision.Accept(listOf(RealtimeOutbound.History(plan.request())))
            }
        }
    }

    /**
     * `resync` directive (`behind` | `gap`): drop to catch-up mode and re-fetch the newest page.
     * Live frames are deferred, not rendered as backlog, until [onHistoryPage] completes.
     */
    fun onResync(channelId: String, reason: String): List<RealtimeOutbound> = mutate { s ->
        val c = s.channels[channelId] ?: return@mutate s to emptyList()
        val plan = HistoryPlan(channelId, beforeSeq = null, untilSeq = null, replaceWindow = true)
        s.copy(channels = s.channels + (channelId to c.copy(mode = ChannelSyncMode.CatchUp, resumeInFlight = false, plan = plan))) to
            listOf<RealtimeOutbound>(RealtimeOutbound.History(plan.request()))
    }

    /**
     * A history page for an active plan arrived ([oldestSeq]/[newestSeq] of the page, null if
     * empty). Returns the next page request, or completion. With no active plan this is ordinary
     * scroll pagination: nothing changes here.
     */
    fun onHistoryPage(channelId: String, oldestSeq: Long?, newestSeq: Long?, hasMore: Boolean): HistoryPageResult = mutate { s ->
        val c = s.channels[channelId] ?: return@mutate s to HistoryPageResult(complete = true, replaceWindow = false)
        val plan = c.plan
        fun put(n: ChannelSyncState) = s.copy(channels = s.channels + (channelId to n))
        when {
            plan == null -> {
                val seeded = if (newestSeq != null && (c.lastSeq == null || c.lastSeq < newestSeq)) put(c.copy(lastSeq = newestSeq)) else s
                seeded to HistoryPageResult(complete = true, replaceWindow = false)
            }
            plan.replaceWindow -> put(
                c.copy(lastSeq = newestSeq ?: c.lastSeq, mode = ChannelSyncMode.Live, plan = null),
            ) to HistoryPageResult(complete = true, replaceWindow = true)
            oldestSeq == null || !hasMore || (plan.untilSeq != null && oldestSeq <= plan.untilSeq + 1) ->
                put(c.copy(mode = ChannelSyncMode.Live, plan = null)) to HistoryPageResult(complete = true, replaceWindow = false)
            else -> {
                val next = plan.copy(beforeSeq = oldestSeq)
                put(c.copy(plan = next)) to HistoryPageResult(false, false, RealtimeOutbound.History(next.request()))
            }
        }
    }

    /** Re-issue the active plan's current page after a failed fetch (same cursor). */
    fun pendingHistory(channelId: String): RealtimeOutbound.History? =
        _state.value.channels[channelId]?.plan?.let { RealtimeOutbound.History(it.request()) }

    /**
     * TODO-CONTRACT: CONTRACT.md defines only the client → server `typing` frame; no inbound
     * typing shape exists. This is the stubbed entry point for whatever the contract later pins.
     * [typing] true = "start", false = "stop". Entries expire after TYPING_TTL_MS.
     */
    fun applyTypingStub(channelId: String, userId: String, typing: Boolean, nowMs: Long) = mutate { s ->
        if (channelId !in s.subscribed) return@mutate s to Unit
        val current = s.typing[channelId].orEmpty()
        val next = if (typing) current + (userId to nowMs + RealtimeFrames.TYPING_TTL_MS) else current - userId
        s.copy(typing = if (next.isEmpty()) s.typing - channelId else s.typing + (channelId to next)) to Unit
    }

    /** Drops expired typists. Call on a ticker while any channel has typists. */
    fun expireTyping(nowMs: Long) = mutate { s ->
        val next = s.typing.mapValues { (_, users) -> users.filterValues { it > nowMs } }.filterValues { it.isNotEmpty() }
        (if (next == s.typing) s else s.copy(typing = next)) to Unit
    }

    /** Presence snapshot delivered on subscribe: merged into the live map. */
    fun applyPresenceSnapshot(snapshot: List<PresenceUi>) = mutate { s ->
        s.copy(presence = s.presence + snapshot.associateBy { it.userId }) to Unit
    }

    /**
     * `presence` frame: one user's presence replaced with exactly what the server supplied.
     * Invisible members arrive as the server chooses to show them (offline to others) and are
     * rendered that way; message arrival never alters presence (F-RT-1).
     */
    fun applyPresence(presence: PresenceUi) = mutate { s ->
        s.copy(presence = s.presence + (presence.userId to presence)) to Unit
    }

    companion object {
        /** Maps the contract `state` string. Unknown values render as offline, never invented. */
        fun presenceOf(userId: String, state: String, activity: String?, invisible: Boolean) = PresenceUi(
            userId = userId,
            state = when (state) {
                "online" -> OrePresence.Online
                "idle" -> OrePresence.Idle
                else -> OrePresence.Offline
            },
            activity = activity,
            invisible = invisible,
        )
    }
}
