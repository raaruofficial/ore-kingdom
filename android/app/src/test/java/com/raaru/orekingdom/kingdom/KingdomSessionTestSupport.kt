package com.raaru.orekingdom.kingdom

import com.raaru.orekingdom.channel.HistoryPageRequest
import com.raaru.orekingdom.channel.OutboxPersistence
import com.raaru.orekingdom.moderation.AppealSubmission
import com.raaru.orekingdom.moderation.ModActionRequest
import com.raaru.orekingdom.moderation.ModAppealDecision
import com.raaru.orekingdom.moderation.ReportSubmission
import com.raaru.orekingdom.shell.Capabilities
import com.raaru.orekingdom.shell.ChannelDto
import com.raaru.orekingdom.shell.EntitlementPush
import com.raaru.orekingdom.shell.Entitlements
import com.raaru.orekingdom.shell.SpotlightDto
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared harness for the KingdomSession seam tests (S-SEAM-2; Q-23…Q-25).
 * Supervisor-authored: a full [KingdomSessionTransport] fake whose reads
 * are plain mutable fields, an in-memory outbox persistence, and small
 * builders for the contract shapes. Mirrors the real transport's
 * documented side effect that `getChannels` refreshes the snapshot.
 */
internal class FakeSessionTransport(
    var me: Pair<String, ContractMember>,
    var members: List<ContractMember>,
    var channels: List<ChannelDto>,
    var history: List<ContractMessage> = emptyList(),
    var entitlements: Entitlements = sessionEntitlements(),
    var spotlight: SpotlightDto = SpotlightDto(false, null, null, 0),
    var threads: List<ContractThread> = emptyList(),
    var threadsError: Exception? = null,
    var searchRead: SearchRead = SearchRead(emptyList(), emptyList()),
    var searchError: Exception? = null,
) : KingdomSessionTransport {

    /* Tranche-4 harness extension (supervisor-authored): configurable
     * module/moderation reads, error arms, and call recording for the
     * C-cluster pins. Every default reproduces the pre-extension fake
     * exactly, so the Q-23…Q-25 tests are unaffected. */
    var historyError: Exception? = null
    var historyHasMore: Boolean = false
    val historyRequests = CopyOnWriteArrayList<HistoryPageRequest>()
    var threadHistory: List<ContractMessage> = emptyList()
    var searchCalls: Int = 0
    var pumpRead: PumpRead = PumpRead(emptyList(), 0, null, false, "locked")
    var pumpError: Exception? = null
    var nightshiftRead: NightshiftRead = NightshiftRead(null, emptyList())
    var nightshiftError: Exception? = null
    var intelRead: List<IntelItemRead> = emptyList()
    var intelError: Exception? = null
    var passesRead: PassesRead = PassesRead(false, null)
    var passesError: Exception? = null
    var crownRead: CrownRead = CrownRead(null, null)
    var crownError: Exception? = null
    var modQueueRead: List<ModQueueItemRead> = emptyList()
    var modQueueError: Exception? = null
    var modQueueCalls: Int = 0
    var modLogRead: List<ModLogEntryRead> = emptyList()
    var modLogError: Exception? = null
    var modLogCalls: Int = 0
    var sanctionsRead: List<SanctionRead> = emptyList()
    var sanctionsError: Exception? = null

    val framesFlow = MutableSharedFlow<KingdomInbound>(extraBufferCapacity = 64)
    override val frames: Flow<KingdomInbound> = framesFlow

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Tranche-4 harness extension: lets a pin drive the connected arm. */
    fun setConnected(value: Boolean) {
        _connected.value = value
    }

    private val _channelsSnapshot = MutableStateFlow(channels)
    override val channelsSnapshot: StateFlow<List<ChannelDto>> = _channelsSnapshot.asStateFlow()

    private val pushesFlow = MutableSharedFlow<EntitlementPush>(extraBufferCapacity = 8)
    override val entitlementPushes: Flow<EntitlementPush> = pushesFlow

    /** Every raw frame the session asked the transport to write, in order. */
    val sentFrames = CopyOnWriteArrayList<String>()

    override fun start(scope: CoroutineScope) = Unit

    override fun sendRaw(frame: String): Boolean {
        sentFrames += frame
        return true
    }

    override suspend fun getEntitlements(): Entitlements = entitlements

    override suspend fun getChannels(): List<ChannelDto> {
        _channelsSnapshot.value = channels
        return channels
    }

    override suspend fun getSpotlight(): SpotlightDto = spotlight

    override suspend fun unsubscribe(channelIds: List<String>) = Unit

    override suspend fun getMe(): Pair<String, ContractMember> = me

    override suspend fun getMembers(): List<ContractMember> = members

    override suspend fun getHistory(request: HistoryPageRequest): HistoryRead {
        historyRequests += request
        historyError?.let { throw it }
        return HistoryRead(history.filter { it.channelId == request.channelId }, hasMore = historyHasMore)
    }

    override suspend fun getThreads(channelId: String): List<ContractThread> {
        threadsError?.let { throw it }
        return threads
    }

    override suspend fun getThreadHistory(channelId: String, threadId: String): List<ContractMessage> =
        threadHistory.filter { it.channelId == channelId && it.threadId == threadId }

    override suspend fun getSearch(query: String): SearchRead {
        searchCalls++
        searchError?.let { throw it }
        return searchRead
    }

    override suspend fun getPump(): PumpRead {
        pumpError?.let { throw it }
        return pumpRead
    }

    override suspend fun getNightshift(): NightshiftRead {
        nightshiftError?.let { throw it }
        return nightshiftRead
    }

    override suspend fun getIntel(): List<IntelItemRead> {
        intelError?.let { throw it }
        return intelRead
    }

    override suspend fun getPasses(): PassesRead {
        passesError?.let { throw it }
        return passesRead
    }

    override suspend fun getCrown(): CrownRead {
        crownError?.let { throw it }
        return crownRead
    }

    override suspend fun getModQueue(): List<ModQueueItemRead> {
        modQueueCalls++
        modQueueError?.let { throw it }
        return modQueueRead
    }

    override suspend fun getModLog(): List<ModLogEntryRead> {
        modLogCalls++
        modLogError?.let { throw it }
        return modLogRead
    }

    override suspend fun getSanctions(): List<SanctionRead> {
        sanctionsError?.let { throw it }
        return sanctionsRead
    }

    override suspend fun submitReport(submission: ReportSubmission) = Unit

    override suspend fun submitAppeal(appeal: AppealSubmission) = Unit

    override suspend fun modClaim(reportId: String) = Unit

    override suspend fun modAction(request: ModActionRequest) = Unit

    override suspend fun modDecideAppeal(decision: ModAppealDecision) = Unit

    override suspend fun nightshiftSuggestion(suggestionId: String, accept: Boolean) = Unit
}

internal class InMemoryOutboxPersistence : OutboxPersistence {
    private var stored: String? = null
    override fun write(serialized: String) {
        stored = serialized
    }

    override fun read(): String? = stored
}

internal fun sessionEntitlements(): Entitlements = Entitlements(
    role = "member",
    capabilities = Capabilities(
        post = emptyList(), react = emptyList(), tip = false, gift = false,
        commercialPost = false, spotlightBid = false, pumpModule = "none",
        pumpDayIncluded = false, atmUnlockIncluded = false,
        nightshiftConsole = false, autopilotSurface = false, modLoft = false,
    ),
    expiresAt = null,
    founding = false,
    modules = emptyMap(),
)

internal fun sessionMember(
    id: String,
    name: String,
    role: String = "member",
    founding: Boolean = false,
    moderator: Boolean = false,
): ContractMember = ContractMember(id, name, role, founding, moderator)

internal fun sessionChannel(id: String): ChannelDto =
    ChannelDto(id, "general-cat", id.removePrefix("ch_"), "text", 0, "free", "member", 0)

internal fun sessionMessage(
    id: String,
    channelId: String,
    seq: Long,
    authorId: String,
    body: String = "body-$id",
    editedAt: String? = null,
    deletedAt: String? = null,
    reactions: List<ContractReaction> = emptyList(),
): ContractMessage = ContractMessage(
    id = id,
    channelId = channelId,
    seq = seq,
    authorId = authorId,
    body = body,
    threadId = null,
    replyTo = null,
    createdAt = "2026-11-27T00:00:00Z",
    editedAt = editedAt,
    deletedAt = deletedAt,
    reactions = reactions,
    commercial = false,
)
