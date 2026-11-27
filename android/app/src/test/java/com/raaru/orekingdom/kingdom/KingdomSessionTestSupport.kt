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

    val framesFlow = MutableSharedFlow<KingdomInbound>(extraBufferCapacity = 64)
    override val frames: Flow<KingdomInbound> = framesFlow

    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected.asStateFlow()

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

    override suspend fun getHistory(request: HistoryPageRequest): HistoryRead =
        HistoryRead(history.filter { it.channelId == request.channelId }, hasMore = false)

    override suspend fun getThreads(channelId: String): List<ContractThread> {
        threadsError?.let { throw it }
        return threads
    }

    override suspend fun getThreadHistory(channelId: String, threadId: String): List<ContractMessage> =
        emptyList()

    override suspend fun getSearch(query: String): SearchRead {
        searchError?.let { throw it }
        return searchRead
    }

    override suspend fun getPump(): PumpRead = PumpRead(emptyList(), 0, null, false, "locked")

    override suspend fun getNightshift(): NightshiftRead = NightshiftRead(null, emptyList())

    override suspend fun getIntel(): List<IntelItemRead> = emptyList()

    override suspend fun getPasses(): PassesRead = PassesRead(false, null)

    override suspend fun getCrown(): CrownRead = CrownRead(null, null)

    override suspend fun getModQueue(): List<ModQueueItemRead> = emptyList()

    override suspend fun getModLog(): List<ModLogEntryRead> = emptyList()

    override suspend fun getSanctions(): List<SanctionRead> = emptyList()

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
