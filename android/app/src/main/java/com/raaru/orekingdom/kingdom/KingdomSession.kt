package com.raaru.orekingdom.kingdom

import android.content.Context
import com.raaru.orekingdom.channel.ChannelMessageUi
import com.raaru.orekingdom.channel.ChannelOutbox
import com.raaru.orekingdom.channel.ChannelRealtime
import com.raaru.orekingdom.channel.ChannelSendController
import com.raaru.orekingdom.channel.ChannelStatus
import com.raaru.orekingdom.channel.ChannelUiState
import com.raaru.orekingdom.channel.FileOutboxPersistence
import com.raaru.orekingdom.channel.HistoryPageRequest
import com.raaru.orekingdom.channel.MessageDecision
import com.raaru.orekingdom.channel.OutboxEntry
import com.raaru.orekingdom.channel.OutboxPersistence
import com.raaru.orekingdom.channel.RealtimeOutbound
import com.raaru.orekingdom.channel.SearchMessageHitUi
import com.raaru.orekingdom.channel.SearchStatus
import com.raaru.orekingdom.channel.SearchThreadHitUi
import com.raaru.orekingdom.channel.SearchUiState
import com.raaru.orekingdom.channel.ThreadListUiState
import com.raaru.orekingdom.channel.ThreadSummaryUi
import com.raaru.orekingdom.channel.ThreadUiState
import com.raaru.orekingdom.designsystem.components.ComposerState
import com.raaru.orekingdom.designsystem.components.OrePresence
import com.raaru.orekingdom.designsystem.components.OreReaction
import com.raaru.orekingdom.designsystem.components.OreRole
import com.raaru.orekingdom.identity.MemberListUiState
import com.raaru.orekingdom.identity.MemberUi
import com.raaru.orekingdom.identity.ProfileStatus
import com.raaru.orekingdom.identity.ProfileUiState
import com.raaru.orekingdom.modules.nightshift.FleetSummaryLineUi
import com.raaru.orekingdom.modules.nightshift.FleetSummaryUi
import com.raaru.orekingdom.modules.nightshift.IntelFeedUi
import com.raaru.orekingdom.modules.nightshift.IntelItemUi
import com.raaru.orekingdom.modules.nightshift.NightshiftConsoleUi
import com.raaru.orekingdom.modules.nightshift.NightshiftStatus
import com.raaru.orekingdom.modules.nightshift.SuggestionUi
import com.raaru.orekingdom.modules.pump.PumpAtmState
import com.raaru.orekingdom.modules.pump.PumpAtmUi
import com.raaru.orekingdom.modules.pump.PumpConsoleUi
import com.raaru.orekingdom.modules.pump.PumpDayBankUi
import com.raaru.orekingdom.modules.pump.PumpStatus
import com.raaru.orekingdom.modules.pump.PumpWalletUi
import com.raaru.orekingdom.moderation.AppealState
import com.raaru.orekingdom.moderation.ModContextLineUi
import com.raaru.orekingdom.moderation.ModHistoryUi
import com.raaru.orekingdom.moderation.ModLogEntryUi
import com.raaru.orekingdom.moderation.ModLogStatus
import com.raaru.orekingdom.moderation.ModLogUiState
import com.raaru.orekingdom.moderation.ModQueueItemUi
import com.raaru.orekingdom.moderation.ModQueueStatus
import com.raaru.orekingdom.moderation.ModQueueUiState
import com.raaru.orekingdom.moderation.ModSnapshotUi
import com.raaru.orekingdom.moderation.SanctionNoticeUi
import com.raaru.orekingdom.moderation.SanctionNoticesStatus
import com.raaru.orekingdom.moderation.SanctionNoticesUiState
import com.raaru.orekingdom.passes.CrownDisplayUi
import com.raaru.orekingdom.passes.CrownStatus
import com.raaru.orekingdom.passes.PassRecipientUi
import com.raaru.orekingdom.passes.PassScreenUi
import com.raaru.orekingdom.passes.PassStatus
import com.raaru.orekingdom.shell.EntitlementStore
import com.raaru.orekingdom.shell.Entitlements
import com.raaru.orekingdom.shell.LastVisitedStore
import com.raaru.orekingdom.shell.RailChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Display mapping of the contract role string to the role mark (badge/colour only, never enablement). */
fun kingdomOreRoleOf(role: String): OreRole = when (role) {
    "member" -> OreRole.Member
    "operator" -> OreRole.Operator
    "syndicate" -> OreRole.Syndicate
    "prestige" -> OreRole.Prestige
    else -> OreRole.Free
}

/**
 * SW2-01 overlay maths, pure for testability: seconds of slowmode still
 * owed after an ack at [lastAckAtMs], read at [nowMs].
 */
object SlowmodeMath {
    fun remainingSeconds(slowmodeSec: Int, lastAckAtMs: Long, nowMs: Long): Int {
        if (slowmodeSec <= 0) return 0
        val elapsedSeconds = (nowMs - lastAckAtMs) / 1000L
        val remaining = slowmodeSec.toLong() - elapsedSeconds
        return remaining.coerceIn(0L, slowmodeSec.toLong()).toInt()
    }
}

/**
 * The host session: owns the [EntitlementStore], the [ChannelRealtime]
 * state machine, the durable outbox and the single send controller, and
 * routes every frame between [KingdomTransport] and them
 * (INTEGRATION_MAP §3.3/§3.4). UI layers read snapshots from here and
 * call the intent functions; no socket or JSON knowledge leaks upward.
 *
 * SW2-01 decision (recorded): the CHANNEL HOST overlays slowmode. The
 * store derives Enabled/Disabled/Upsell from entitlements only; this
 * session combines that with the channel DTO's `slowmode_sec` and the
 * last ack time for the channel, producing [ComposerState.Slowmode]
 * while the window runs (see [effectiveComposer]).
 *
 * SW2-02 status (recorded): the upsell-vs-disabled presentation split
 * still rests on the store's single `minRolePost` class comparison —
 * presentation only; enablement derives from `capabilities.post`
 * alone. A contract capability field is the real fix and is recorded
 * as a proposal in the integration record; the client infers nothing
 * further here.
 */
class KingdomSession(
    val transport: KingdomSessionTransport,
    private val scope: CoroutineScope,
    lastVisitedStore: LastVisitedStore,
    outboxPersistence: OutboxPersistence,
) {
    /**
     * S-SEAM-2: production wiring — the [LastVisitedStore] is the
     * SharedPreferences-backed store and the outbox persists to the
     * app-private file, exactly as before the seam. Tests use the
     * primary constructor with in-memory seams and no Context.
     */
    constructor(
        transport: KingdomSessionTransport,
        scope: CoroutineScope,
        context: Context,
    ) : this(
        transport,
        scope,
        PrefsLastVisitedStore(context),
        FileOutboxPersistence(File(context.filesDir, "kingdom-outbox.txt")),
    )

    val store: EntitlementStore = EntitlementStore(transport, scope, lastVisitedStore)
    val realtime: ChannelRealtime = ChannelRealtime()
    val outbox: ChannelOutbox = ChannelOutbox(
        persistence = outboxPersistence,
    )

    /** The one send controller for the session; every channel composer shares it (it is channel-agnostic). */
    val sendController: ChannelSendController = ChannelSendController(
        outbox = outbox,
        transmit = { entry -> transmit(entry) },
        onSendAck = { key -> onSendAck(key) },
    )

    private val lock = Any()
    private val messages = LinkedHashMap<String, ContractMessage>()
    private val directory = LinkedHashMap<String, ContractMember>()
    private val hasOlder = HashMap<String, Boolean>()
    private val channelStatus = HashMap<String, ChannelStatus>()
    private val loadingChannels = HashSet<String>()
    private val lastAckAtMs = HashMap<String, Long>()
    private val ackedKeys = HashMap<String, String>()

    @Volatile var selfId: String? = null
        private set

    @Volatile var openChannelId: String? = null
        private set

    private val _version = MutableStateFlow(0)

    /** Bumped on every cache/directory mutation; UI layers collect it to recompose snapshots. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun start() {
        transport.start(scope)
        store.start()
        scope.launch { transport.frames.collect { handle(it) } }
        scope.launch { transport.channelsSnapshot.collect { applyEntitledChannels() } }
        scope.launch { store.entitlements.collect { applyEntitledChannels() } }
        scope.launch { loadDirectory() }
        scope.launch {
            while (isActive) {
                delay(1_000)
                realtime.expireTyping(System.currentTimeMillis())
            }
        }
    }

    /* ---- directory / identity ---- */

    private suspend fun loadDirectory() {
        try {
            val (id, me) = transport.getMe()
            selfId = id
            synchronized(lock) { directory[me.userId] = me }
            bump()
        } catch (_: Exception) {
            // Directory stays empty; names fall back to ids until a later load succeeds.
        }
        try {
            val members = transport.getMembers()
            synchronized(lock) { members.forEach { directory[it.userId] = it } }
            bump()
        } catch (_: Exception) {
        }
    }

    /** Re-reads the member directory (call when the profile/members surface opens). */
    fun refreshDirectory() {
        scope.launch { loadDirectory() }
    }

    fun member(userId: String): ContractMember? = synchronized(lock) { directory[userId] }

    /* ---- entitlement-driven subscriptions (F-IA-2) ---- */

    private fun applyEntitledChannels() {
        val readable = transport.channelsSnapshot.value
            .filter { it.kind != "module" }
            .map { it.id }
        if (readable.isEmpty()) return
        val change = realtime.applyEntitledChannels(readable)
        if (change.removedChannelIds.isNotEmpty()) {
            synchronized(lock) {
                val removed = change.removedChannelIds
                val iterator = messages.entries.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next().value.channelId in removed) iterator.remove()
                }
                hasOlder.keys.removeAll(removed)
                channelStatus.keys.removeAll(removed)
            }
            bump()
        }
        execute(change.outbound)
    }

    /* ---- inbound frames ---- */

    private fun handle(inbound: KingdomInbound) {
        when (inbound) {
            KingdomInbound.Connected -> {
                execute(realtime.onConnected())
                sendController.drain()
            }
            KingdomInbound.Disconnected -> {
                realtime.onDisconnected()
                sendController.onDisconnected()
            }
            is KingdomInbound.Ack -> {
                val channelId = outbox.peek(inbound.forId)?.channelId
                if (channelId != null) {
                    realtime.seedLastSeq(channelId, inbound.seq)
                    synchronized(lock) {
                        ackedKeys[inbound.forId] = inbound.messageId
                        // SW2-01: the slowmode window runs from the ack.
                        lastAckAtMs[channelId] = System.currentTimeMillis()
                    }
                }
                sendController.handleAck(inbound.forId)
                bump()
            }
            is KingdomInbound.Message -> {
                val message = inbound.message
                when (val decision = realtime.onMessage(message.channelId, message.seq)) {
                    is MessageDecision.Accept -> {
                        insert(message)
                        execute(decision.outbound)
                    }
                    MessageDecision.Duplicate -> insertIfAbsent(message)
                    is MessageDecision.Deferred -> execute(decision.outbound)
                    MessageDecision.Ignore -> Unit
                }
                if (message.channelId != openChannelId && message.authorId != selfId) {
                    val current = store.rail.value.categories
                        .flatMap { it.channels }
                        .firstOrNull { it.id == message.channelId }
                        ?.unreadCount ?: 0
                    store.setUnread(message.channelId, current + 1)
                }
            }
            is KingdomInbound.MessageUpdate -> insert(inbound.message)
            is KingdomInbound.MessageDelete -> synchronized(lock) {
                messages[inbound.messageId]?.let { existing ->
                    if (existing.channelId == inbound.channelId) {
                        messages[inbound.messageId] =
                            existing.copy(deletedAt = existing.deletedAt ?: "deleted")
                    }
                }
                bump()
            }
            is KingdomInbound.Reaction -> synchronized(lock) {
                messages[inbound.messageId]?.let { existing ->
                    val others = existing.reactions.filter { it.emoji != inbound.emoji }
                    val next = if (inbound.count <= 0) {
                        others
                    } else {
                        others + ContractReaction(
                            emoji = inbound.emoji,
                            count = inbound.count,
                            mine = selfId != null && selfId in inbound.actorIds,
                        )
                    }
                    messages[inbound.messageId] = existing.copy(reactions = next)
                }
                bump()
            }
            is KingdomInbound.Presence -> realtime.applyPresence(inbound.presence)
            is KingdomInbound.Resync -> execute(realtime.onResync(inbound.channelId, inbound.reason))
            is KingdomInbound.Error -> {
                if (inbound.forId != null) {
                    sendController.handleRefused(inbound.forId)
                    if (inbound.code == "not_entitled") store.refresh()
                    bump()
                } else if (inbound.channelId != null) {
                    val refused = realtime.onSubscribeRefused(listOf(inbound.channelId))
                    if (refused.isNotEmpty()) {
                        synchronized(lock) {
                            val iterator = messages.entries.iterator()
                            while (iterator.hasNext()) {
                                if (iterator.next().value.channelId in refused) iterator.remove()
                            }
                        }
                        bump()
                    }
                }
            }
        }
    }

    private fun onSendAck(idempotencyKey: String) {
        // The entry is already removed by the controller. The slowmode
        // timestamp for its channel was recorded in the Ack branch,
        // which is the only place that still knows the entry's channel.
        bump()
    }

    private fun transmit(entry: OutboxEntry) {
        transport.sendRaw(
            KingdomJson.sendFrame(entry.idempotencyKey, entry.channelId, entry.body, entry.threadId, entry.replyTo),
        )
    }

    /* ---- outbound execution ---- */

    private fun execute(outbound: List<RealtimeOutbound>) {
        outbound.forEach { item ->
            when (item) {
                is RealtimeOutbound.Subscribe -> transport.sendRaw(KingdomJson.subscribeFrame(item.channelIds))
                is RealtimeOutbound.Unsubscribe -> transport.sendRaw(KingdomJson.unsubscribeFrame(item.channelIds))
                is RealtimeOutbound.Resume -> transport.sendRaw(KingdomJson.resumeFrame(item.lastSeq))
                is RealtimeOutbound.History -> fetchHistory(item.request, planDriven = true)
            }
        }
    }

    private fun fetchHistory(request: HistoryPageRequest, planDriven: Boolean) {
        val channelId = request.channelId
        synchronized(lock) {
            if (!loadingChannels.add(channelId)) return
        }
        scope.launch {
            try {
                val read = transport.getHistory(request)
                read.messages.forEach { insert(it) }
                synchronized(lock) {
                    hasOlder[channelId] = read.hasMore
                    channelStatus[channelId] = ChannelStatus.Ready
                }
                if (planDriven) {
                    val result = realtime.onHistoryPage(
                        channelId,
                        read.messages.minOfOrNull { it.seq },
                        read.messages.maxOfOrNull { it.seq },
                        read.hasMore,
                    )
                    result.next?.let { fetchHistory(it.request, planDriven = true) }
                } else if (read.messages.isNotEmpty()) {
                    realtime.seedLastSeq(channelId, read.messages.maxOf { it.seq })
                }
                bump()
            } catch (_: Exception) {
                synchronized(lock) {
                    if (messages.values.none { it.channelId == channelId }) {
                        channelStatus[channelId] = ChannelStatus.Error
                    }
                }
                bump()
            } finally {
                synchronized(lock) { loadingChannels.remove(channelId) }
            }
        }
    }

    /* ---- UI intents ---- */

    fun openChannel(channelId: String) {
        openChannelId = channelId
        store.selectChannel(channelId)
        store.setUnread(channelId, 0)
        val pending = realtime.pendingHistory(channelId)
        when {
            pending != null -> execute(listOf(pending))
            synchronized(lock) { messages.values.none { it.channelId == channelId } } ->
                fetchHistory(HistoryPageRequest(channelId, null), planDriven = true)
            else -> Unit
        }
    }

    fun retryChannel(channelId: String) {
        synchronized(lock) { channelStatus.remove(channelId) }
        val pending = realtime.pendingHistory(channelId)
        if (pending != null) execute(listOf(pending)) else {
            fetchHistory(HistoryPageRequest(channelId, null), planDriven = true)
        }
    }

    fun loadOlder(channelId: String) {
        val oldest = synchronized(lock) {
            messages.values.filter { it.channelId == channelId }.minOfOrNull { it.seq }
        } ?: return
        if (synchronized(lock) { hasOlder[channelId] } == false) return
        fetchHistory(HistoryPageRequest(channelId, oldest), planDriven = false)
    }

    fun setTyping(channelId: String, typing: Boolean) {
        transport.sendRaw(KingdomJson.typingFrame(channelId, typing))
    }

    fun toggleReaction(channelId: String, messageId: String, emoji: String) {
        val mine = synchronized(lock) {
            messages[messageId]?.reactions?.firstOrNull { it.emoji == emoji }?.mine == true
        }
        transport.sendRaw(KingdomJson.reactFrame(channelId, messageId, emoji, add = !mine))
    }

    /* ---- snapshots for the UI layer ---- */

    /** SW2-01 overlay: see the class doc. [nowMs] is injectable for tests. */
    fun effectiveComposer(channel: RailChannel, nowMs: Long = System.currentTimeMillis()): ComposerState {
        val base = channel.composer
        if (base !is ComposerState.Enabled) return base
        val dto = transport.channelsSnapshot.value.firstOrNull { it.id == channel.id } ?: return base
        if (dto.slowmodeSec <= 0) return base
        val lastAck = synchronized(lock) { lastAckAtMs[channel.id] } ?: return base
        val remaining = SlowmodeMath.remainingSeconds(dto.slowmodeSec, lastAck, nowMs)
        return if (remaining > 0) ComposerState.Slowmode(remaining) else base
    }

    fun channelState(channel: RailChannel, entitlements: Entitlements?): ChannelUiState {
        val cached: List<ContractMessage>
        val pending: List<OutboxEntry>
        val acked: Map<String, String>
        synchronized(lock) {
            cached = messages.values.filter { it.channelId == channel.id }.sortedBy { it.seq }
            acked = HashMap(ackedKeys)
        }
        pending = outbox.forChannel(channel.id).filter { it.idempotencyKey !in acked }
        val self = selfId?.let { member(it) }
        val rows = ArrayList<ChannelMessageUi>(cached.size + pending.size)
        cached.forEach { message ->
            val author = member(message.authorId)
            rows += ChannelMessageUi(
                id = message.id,
                seq = message.seq,
                authorName = author?.displayName ?: message.authorId,
                authorRole = kingdomOreRoleOf(author?.role ?: "free"),
                body = message.body,
                timestamp = message.createdAt,
                edited = message.editedAt != null,
                deleted = message.deletedAt != null,
                commercial = message.commercial,
                reactions = message.reactions.map { OreReaction(it.emoji, it.count, it.mine) },
                own = message.authorId == selfId,
                founding = author?.founding == true,
                moderator = author?.moderator == true,
            )
        }
        pending.forEach { entry ->
            rows += ChannelMessageUi(
                id = entry.idempotencyKey,
                seq = null,
                authorName = self?.displayName ?: "You",
                authorRole = kingdomOreRoleOf(self?.role ?: "free"),
                body = entry.body,
                timestamp = entry.queuedAt,
                delivery = sendController.deliveryOf(entry.idempotencyKey),
                own = true,
                founding = self?.founding == true,
                moderator = self?.moderator == true,
                localKey = entry.idempotencyKey,
            )
        }
        val typists = realtime.state.value.typists(channel.id)
            .filter { it != selfId }
            .map { member(it)?.displayName ?: it }
        return ChannelUiState(
            channelName = channel.name,
            status = synchronized(lock) { channelStatus[channel.id] }
                ?: if (cached.isEmpty()) ChannelStatus.Loading else ChannelStatus.Ready,
            messages = rows,
            stale = !transport.connected.value && cached.isNotEmpty(),
            typists = typists,
            reactionsEnabled = entitlements?.capabilities?.react?.contains(channel.id) == true,
            hasOlder = synchronized(lock) { hasOlder[channel.id] } ?: false,
        )
    }

    /* ---- module / secondary-surface read models ---- */

    data class PumpLoad(val console: PumpConsoleUi, val dayBank: PumpDayBankUi, val atm: PumpAtmUi)

    suspend fun loadPump(entitlements: Entitlements?): PumpLoad {
        val caps = entitlements?.capabilities
        return try {
            val read = transport.getPump()
            PumpLoad(
                console = PumpConsoleUi(
                    status = PumpStatus.Ready,
                    wallets = read.wallets.map { PumpWalletUi(it.addressShort, it.balanceText, it.tankStateText) },
                ),
                dayBank = PumpDayBankUi(
                    status = PumpStatus.Ready,
                    bankedDays = read.bankedDays,
                    activeRemainingSeconds = read.activeRemainingSeconds,
                    firstDayFree = read.firstDayFree,
                    unlimited = caps?.pumpDayIncluded == true,
                ),
                atm = PumpAtmUi(
                    status = PumpStatus.Ready,
                    state = when {
                        caps?.atmUnlockIncluded == true -> PumpAtmState.Included
                        read.atmState == "unlocked" -> PumpAtmState.Unlocked
                        read.atmState == "included" -> PumpAtmState.Included
                        else -> PumpAtmState.Locked
                    },
                ),
            )
        } catch (_: Exception) {
            PumpLoad(
                console = PumpConsoleUi(status = PumpStatus.Error),
                dayBank = PumpDayBankUi(status = PumpStatus.Error),
                atm = PumpAtmUi(status = PumpStatus.Error),
            )
        }
    }

    data class NightshiftLoad(val console: NightshiftConsoleUi, val intel: IntelFeedUi)

    suspend fun loadNightshift(): NightshiftLoad {
        val console = try {
            val read = transport.getNightshift()
            NightshiftConsoleUi(
                status = NightshiftStatus.Ready,
                fleet = read.fleet?.let {
                    FleetSummaryUi(it.headline, it.lines.map { line -> FleetSummaryLineUi(line.first, line.second) })
                },
                suggestions = read.suggestions.map { SuggestionUi(it.id, it.title, it.body) },
            )
        } catch (_: Exception) {
            NightshiftConsoleUi(status = NightshiftStatus.Error)
        }
        val intel = try {
            IntelFeedUi(
                status = NightshiftStatus.Ready,
                items = transport.getIntel().map { IntelItemUi(it.title, it.body, it.severity, it.at) },
            )
        } catch (_: Exception) {
            IntelFeedUi(status = NightshiftStatus.Error)
        }
        return NightshiftLoad(console, intel)
    }

    fun membersState(): MemberListUiState {
        val presence = realtime.state.value.presence
        val members = synchronized(lock) { directory.values.toList() }
        if (members.isEmpty()) return MemberListUiState(status = com.raaru.orekingdom.identity.MemberListStatus.Loading)
        return MemberListUiState(
            status = com.raaru.orekingdom.identity.MemberListStatus.Ready,
            members = members.map { member ->
                val p = presence[member.userId]
                MemberUi(
                    userId = member.userId,
                    displayName = member.displayName,
                    role = kingdomOreRoleOf(member.role),
                    presence = p?.state ?: OrePresence.Offline,
                    activity = p?.activity,
                    founding = member.founding,
                    moderator = member.moderator,
                )
            },
        )
    }

    fun profileState(userId: String): ProfileUiState {
        val member = member(userId)
            ?: return ProfileUiState(status = ProfileStatus.Error, userId = userId)
        val p = realtime.state.value.presence[userId]
        return ProfileUiState(
            status = ProfileStatus.Ready,
            userId = member.userId,
            displayName = member.displayName,
            isSelf = userId == selfId,
            memberRole = member.role,
            founding = member.founding,
            moderator = member.moderator,
            presence = p?.state,
            activity = p?.activity,
        )
    }

    data class PassesLoad(val passes: PassScreenUi, val crown: CrownDisplayUi)

    suspend fun loadPasses(): PassesLoad {
        val passes = try {
            val read = transport.getPasses()
            val recipients = synchronized(lock) { directory.values.toList() }
                .filter { it.userId != selfId }
                .map { PassRecipientUi(it.userId, it.displayName) }
            PassScreenUi(
                status = PassStatus.Ready,
                lifetimeHolder = read.lifetimeHolder,
                prestigeSeatsRemaining = read.prestigeSeatsRemaining,
                recipients = recipients,
            )
        } catch (_: Exception) {
            PassScreenUi(status = PassStatus.Error)
        }
        val crown = try {
            val read = transport.getCrown()
            CrownDisplayUi(status = CrownStatus.Ready, holderName = read.holderName, month = read.month)
        } catch (_: Exception) {
            CrownDisplayUi(status = CrownStatus.Error)
        }
        return PassesLoad(passes, crown)
    }

    suspend fun loadModQueue(entitlements: Entitlements?): ModQueueUiState {
        val modLoft = entitlements?.capabilities?.modLoft == true
        if (!modLoft) return ModQueueUiState(viewerId = selfId ?: "", modLoft = false, status = ModQueueStatus.Ready)
        return try {
            val items = transport.getModQueue().map { item ->
                ModQueueItemUi(
                    reportId = item.reportId,
                    state = item.state,
                    reason = item.reason,
                    reporterCount = item.reporterCount,
                    snapshot = ModSnapshotUi(
                        body = item.snapshot.body,
                        authorId = item.snapshot.authorId,
                        channelId = item.snapshot.channelId,
                        createdAt = item.snapshot.createdAt,
                        context = item.snapshot.context.map {
                            ModContextLineUi(it.id, member(it.authorId)?.displayName ?: it.authorId, it.body, it.createdAt)
                        },
                        authorName = member(item.snapshot.authorId)?.displayName,
                    ),
                    claimedBy = item.claimedBy,
                    claimedByName = item.claimedBy?.let { member(it)?.displayName },
                    authorHistory = item.authorHistory.map { ModHistoryUi(it.rung, it.reasonCategory, it.at) },
                    appeal = item.appeal?.let {
                        com.raaru.orekingdom.moderation.ModAppealUi(
                            statement = it.statement,
                            sanctionBy = it.sanctionBy,
                            state = AppealState.of(it.state),
                        )
                    },
                )
            }
            ModQueueUiState(viewerId = selfId ?: "", modLoft = true, status = ModQueueStatus.Ready, items = items)
        } catch (_: Exception) {
            ModQueueUiState(viewerId = selfId ?: "", modLoft = true, status = ModQueueStatus.Error)
        }
    }

    suspend fun loadModLog(entitlements: Entitlements?): ModLogUiState {
        val modLoft = entitlements?.capabilities?.modLoft == true
        if (!modLoft) return ModLogUiState(modLoft = false, status = ModLogStatus.Ready)
        return try {
            ModLogUiState(
                modLoft = true,
                status = ModLogStatus.Ready,
                entries = transport.getModLog().map {
                    ModLogEntryUi(
                        actorId = it.actorId,
                        action = it.action,
                        targetId = it.targetId,
                        reason = it.reason,
                        at = it.at,
                        actorName = member(it.actorId)?.displayName,
                        targetName = member(it.targetId)?.displayName,
                    )
                },
            )
        } catch (_: Exception) {
            ModLogUiState(modLoft = true, status = ModLogStatus.Error)
        }
    }

    suspend fun loadSanctions(): SanctionNoticesUiState = try {
        SanctionNoticesUiState(
            status = SanctionNoticesStatus.Ready,
            notices = transport.getSanctions().map {
                SanctionNoticeUi(
                    sanctionId = it.sanctionId,
                    rung = it.rung,
                    reasonCategory = it.reasonCategory,
                    durationSec = it.durationSec,
                    appeal = AppealState.of(it.appealState),
                )
            },
        )
    } catch (_: Exception) {
        SanctionNoticesUiState(status = SanctionNoticesStatus.Error)
    }

    /* ---- thread + search read models (stage-1 remainder, mounted 2026-11-27) ---- */

    suspend fun loadThreadList(channelId: String): ThreadListUiState = try {
        val threads = transport.getThreads(channelId)
        ThreadListUiState(
            status = ChannelStatus.Ready,
            threads = threads.map { thread ->
                ThreadSummaryUi(
                    id = thread.id,
                    title = thread.title,
                    anchorAuthorName = thread.anchorMessageId?.let { anchorId ->
                        synchronized(lock) { messages[anchorId] }
                            ?.let { member(it.authorId)?.displayName ?: it.authorId }
                    } ?: "Unknown",
                    replyCount = thread.replyCount,
                    lastActivity = thread.lastActivityAt,
                    archived = thread.state == "archived",
                )
            }.sortedByDescending { it.lastActivity },
        )
    } catch (_: Exception) {
        ThreadListUiState(status = ChannelStatus.Error)
    }

    suspend fun loadThread(channelId: String, threadId: String): ThreadUiState {
        val thread = try {
            transport.getThreads(channelId).firstOrNull { it.id == threadId }
        } catch (_: Exception) {
            null
        } ?: return ThreadUiState(threadId = threadId, title = "", status = ChannelStatus.Error)
        return try {
            val replies = transport.getThreadHistory(channelId, threadId)
            replies.forEach { insert(it) }
            val anchor = thread.anchorMessageId?.let { anchorId ->
                synchronized(lock) { messages[anchorId] }
            }
            ThreadUiState(
                threadId = thread.id,
                title = thread.title,
                status = ChannelStatus.Ready,
                archived = thread.state == "archived",
                // TODO-CONTRACT: no unarchive action shape exists; never inferred.
                canUnarchive = false,
                anchor = anchor?.let { messageUi(it) },
                replies = replies.map { messageUi(it) },
                reactionsEnabled = store.entitlements.value?.capabilities?.react?.contains(channelId) == true,
            )
        } catch (_: Exception) {
            ThreadUiState(threadId = thread.id, title = thread.title, status = ChannelStatus.Error)
        }
    }

    /**
     * Live re-derivation of a loaded thread from the message cache: WS
     * broadcasts (including the echo of the viewer's own reply) land in
     * the cache first, so the open thread view tracks them.
     */
    fun threadSnapshot(channelId: String, base: ThreadUiState): ThreadUiState {
        val replies = synchronized(lock) {
            messages.values
                .filter { it.channelId == channelId && it.threadId == base.threadId }
                .sortedBy { it.seq }
        }
        if (replies.isEmpty()) return base
        return base.copy(replies = replies.map { messageUi(it) })
    }

    suspend fun loadSearch(query: String): SearchUiState {
        if (query.isBlank()) return SearchUiState(query = query, status = SearchStatus.Idle)
        return try {
            val read = transport.getSearch(query)
            read.messages.forEach { insert(it) }
            val channels = transport.channelsSnapshot.value
            SearchUiState(
                query = query,
                status = SearchStatus.Ready,
                messages = read.messages.map { message ->
                    SearchMessageHitUi(
                        channelId = message.channelId,
                        message = messageUi(message),
                        channelName = channels.firstOrNull { it.id == message.channelId }?.name,
                    )
                },
                threads = read.threads.map { thread ->
                    SearchThreadHitUi(
                        id = thread.id,
                        channelId = thread.channelId,
                        title = thread.title,
                        archived = thread.state == "archived",
                        replyCount = thread.replyCount,
                        lastActivity = thread.lastActivityAt,
                        channelName = channels.firstOrNull { it.id == thread.channelId }?.name,
                    )
                },
                readableChannelIds = channels.map { it.id }.toSet(),
            )
        } catch (_: Exception) {
            SearchUiState(query = query, status = SearchStatus.Error)
        }
    }

    /** Maps a cached contract message to its render row (the same mapping channelState uses). */
    private fun messageUi(message: ContractMessage): ChannelMessageUi {
        val author = member(message.authorId)
        return ChannelMessageUi(
            id = message.id,
            seq = message.seq,
            authorName = author?.displayName ?: message.authorId,
            authorRole = kingdomOreRoleOf(author?.role ?: "free"),
            body = message.body,
            timestamp = message.createdAt,
            edited = message.editedAt != null,
            deleted = message.deletedAt != null,
            commercial = message.commercial,
            reactions = message.reactions.map { OreReaction(it.emoji, it.count, it.mine) },
            own = message.authorId == selfId,
            founding = author?.founding == true,
            moderator = author?.moderator == true,
        )
    }

    /* ---- cache helpers ---- */

    private fun insert(message: ContractMessage) {
        synchronized(lock) { messages[message.id] = message }
        bump()
    }

    private fun insertIfAbsent(message: ContractMessage) {
        synchronized(lock) {
            if (message.id !in messages) messages[message.id] = message
        }
        bump()
    }

    private fun bump() {
        _version.value += 1
    }
}
