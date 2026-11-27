package com.raaru.orekingdom.kingdom

import com.raaru.orekingdom.channel.ChannelMessageUi
import com.raaru.orekingdom.channel.ThreadUiState
import com.raaru.orekingdom.channel.ChannelOutbox
import com.raaru.orekingdom.channel.ChannelStatus
import com.raaru.orekingdom.channel.OutboxEnqueueResult
import com.raaru.orekingdom.channel.PresenceUi
import com.raaru.orekingdom.designsystem.components.ComposerState
import com.raaru.orekingdom.designsystem.components.OreMessageDelivery
import com.raaru.orekingdom.designsystem.components.OrePresence
import com.raaru.orekingdom.designsystem.components.OreReaction
import com.raaru.orekingdom.designsystem.components.OreRole
import com.raaru.orekingdom.identity.ProfileStatus
import com.raaru.orekingdom.shell.ChannelKind
import com.raaru.orekingdom.shell.InMemoryLastVisitedStore
import com.raaru.orekingdom.shell.RailChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Message-derivation pins for KingdomSession (DeepSeek full-spend
 * programme Q-23): how cached contract messages become render rows —
 * authorship, directory marks, edited/deleted flags, reaction mapping.
 * Harness in KingdomSessionTestSupport.kt (S-SEAM-2).
 */
class KingdomSessionMessageTest {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private lateinit var transport: FakeSessionTransport
    private lateinit var session: KingdomSession

    @After
    fun tearDown() {
        job.cancel()
    }

    private fun startWith(history: List<ContractMessage>, members: List<ContractMember>) {
        transport = FakeSessionTransport(
            me = "u_self" to sessionMember("u_self", "Self"),
            members = members,
            channels = listOf(sessionChannel("ch_general")),
            history = history,
        )
        session = KingdomSession(transport, scope, InMemoryLastVisitedStore(), InMemoryOutboxPersistence())
        session.start()
        session.openChannel("ch_general")
    }

    private fun rows(): List<ChannelMessageUi> = session.channelState(
        RailChannel("ch_general", "general", ChannelKind.Text, 0, ComposerState.Enabled),
        null,
    ).messages

    private fun emit(frame: KingdomInbound) = runBlocking { transport.framesFlow.emit(frame) }

    @Test
    fun ownAndOtherAuthorshipDerivesFromSelfId() {
        startWith(
            history = listOf(
                sessionMessage("m1", "ch_general", 1, "u_self"),
                sessionMessage("m2", "ch_general", 2, "u_other"),
            ),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )

        assertEquals(2, rows().size)
        assertTrue(rows()[0].own)
        assertFalse(rows()[1].own)
        assertEquals("Self", rows()[0].authorName)
        assertEquals("Other", rows()[1].authorName)
    }

    @Test
    fun unknownAuthorFallsBackToIdAndFreeRole() {
        startWith(
            history = listOf(
                sessionMessage("m1", "ch_general", 1, "u_ghost"),
            ),
            members = listOf(
                sessionMember("u_self", "Self"),
            ),
        )

        val row = rows().single()

        assertEquals("u_ghost", row.authorName)
        assertEquals(OreRole.Free, row.authorRole)
        assertFalse(row.founding)
        assertFalse(row.moderator)
    }

    @Test
    fun deletedAndEditedFlagsComeFromTimestamps() {
        startWith(
            history = listOf(
                sessionMessage("m1", "ch_general", 1, "u_other", deletedAt = "2026-11-27T01:00:00Z"),
                sessionMessage("m2", "ch_general", 2, "u_other", editedAt = "2026-11-27T02:00:00Z"),
            ),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )

        assertTrue(rows()[0].deleted)
        assertFalse(rows()[0].edited)
        assertTrue(rows()[1].edited)
        assertFalse(rows()[1].deleted)
    }

    @Test
    fun directoryMarksFlowToTheRow() {
        startWith(
            history = listOf(
                sessionMessage("m1", "ch_general", 1, "u_op"),
            ),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_op", "Op", role = "operator", founding = true, moderator = true),
            ),
        )

        val row = rows().single()

        assertEquals(OreRole.Operator, row.authorRole)
        assertTrue(row.founding)
        assertTrue(row.moderator)
    }

    @Test
    fun storedReactionsMapStraightToTheRow() {
        startWith(
            history = listOf(sessionMessage("m1", "ch_general", 1, "u_other", reactions = listOf(ContractReaction("👍", 3, true)))),
            members = listOf(sessionMember("u_self", "Self"), sessionMember("u_other", "Other")),
        )

        assertEquals(listOf(OreReaction("👍", 3, true)), rows().single().reactions)
    }

    @Test
    fun reactionFrameAggregatesMineFromActorIds() {
        startWith(
            history = listOf(sessionMessage("m1", "ch_general", 1, "u_other")),
            members = listOf(sessionMember("u_self", "Self"), sessionMember("u_other", "Other")),
        )

        emit(KingdomInbound.Reaction("ch_general", "m1", "🔥", 2, listOf("u_self", "u_other")))
        assertEquals(listOf(OreReaction("🔥", 2, true)), rows().single().reactions)

        emit(KingdomInbound.Reaction("ch_general", "m1", "🔥", 1, listOf("u_other")))
        assertEquals(listOf(OreReaction("🔥", 1, false)), rows().single().reactions)
    }

    @Test
    fun profileStateUnknownIdIsErrorEchoingTheId() {
        startWith(
            history = emptyList(),
            members = listOf(sessionMember("u_self", "Self")),
        )

        val state = session.profileState("u_nobody")

        assertEquals(ProfileStatus.Error, state.status)
        assertEquals("u_nobody", state.userId)
    }

    @Test
    fun profileStateMemberIsReadyVerbatimAndSelfFlagged() {
        startWith(
            history = emptyList(),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other", role = "operator", founding = true, moderator = true),
            ),
        )

        val other = session.profileState("u_other")
        assertEquals(ProfileStatus.Ready, other.status)
        assertEquals("u_other", other.userId)
        assertEquals("Other", other.displayName)
        assertEquals("operator", other.memberRole)
        assertTrue(other.founding)
        assertTrue(other.moderator)
        assertFalse(other.isSelf)

        val self = session.profileState("u_self")
        assertEquals(ProfileStatus.Ready, self.status)
        assertTrue(self.isSelf)
    }

    @Test
    fun profileStateCopiesPresenceWhenKnown() {
        startWith(
            history = emptyList(),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )
        emit(KingdomInbound.Presence(PresenceUi("u_other", OrePresence.Idle, "mining", false)))

        val state = session.profileState("u_other")

        assertEquals(OrePresence.Idle, state.presence)
        assertEquals("mining", state.activity)
    }

    @Test
    fun profileStateHasNullPresenceWhenUnknown() {
        startWith(
            history = emptyList(),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )

        val state = session.profileState("u_other")

        assertEquals(null, state.presence)
        assertEquals(null, state.activity)
    }

    @Test
    fun channelRowsSortBySeqRegardlessOfInsertionOrder() {
        startWith(
            history = listOf(
                sessionMessage("m3", "ch_general", 3, "u_other"),
                sessionMessage("m1", "ch_general", 1, "u_other"),
                sessionMessage("m2", "ch_general", 2, "u_self"),
            ),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )

        assertEquals(listOf(1L, 2L, 3L), rows().map { it.seq })
        assertEquals(listOf("m1", "m2", "m3"), rows().map { it.id })
    }

    @Test
    fun channelRowCarriesCommercialFlagAndReactionOrder() {
        startWith(
            history = listOf(
                sessionMessage(
                    "m1", "ch_general", 1, "u_other",
                    reactions = listOf(ContractReaction("🔥", 2, false), ContractReaction("👍", 5, true)),
                ).copy(commercial = true),
            ),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )

        val row = rows().single()

        assertTrue(row.commercial)
        assertEquals(listOf(OreReaction("🔥", 2, false), OreReaction("👍", 5, true)), row.reactions)
    }

    @Test
    fun ackedEntryReintroducedByRestoreDoesNotRenderAsPending() {
        startWith(
            history = listOf(sessionMessage("m1", "ch_general", 1, "u_other")),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )
        session.sendController.submit("ch_general", "ghost body", online = false)
        val key = session.outbox.forChannel("ch_general").single().idempotencyKey

        emit(KingdomInbound.Ack(key, "m_ghost", 42))
        assertTrue(session.outbox.forChannel("ch_general").isEmpty())

        val scratch = ChannelOutbox(capacity = 100, persistence = null, keyFactory = { key })
        scratch.enqueue("ch_general", "ghost body")
        assertEquals(1, session.outbox.restore(scratch.serialize()))
        assertTrue(session.outbox.forChannel("ch_general").any { it.idempotencyKey == key })

        assertTrue(rows().none { it.localKey == key })
        assertEquals(listOf("m1"), rows().map { it.id })
    }

    @Test
    fun pendingRowRendersAfterCachedRowsWithDeliveryAndLocalKey() {
        startWith(
            history = listOf(sessionMessage("m1", "ch_general", 1, "u_other")),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )

        val result = session.sendController.submit("ch_general", "held body", online = false)
        assertTrue(result is OutboxEnqueueResult.Queued)
        val key = session.outbox.forChannel("ch_general").single().idempotencyKey

        val rows = rows()
        assertEquals(2, rows.size)
        assertEquals("m1", rows[0].id)
        assertEquals(key, rows[1].localKey)
        assertEquals(null, rows[1].seq)
        assertEquals("held body", rows[1].body)
        assertEquals(OreMessageDelivery.Queued, rows[1].delivery)
        assertTrue(rows[1].own)
    }

    @Test
    fun typistsExcludeSelfAndResolveNamesThroughDirectory() {
        startWith(
            history = listOf(sessionMessage("m1", "ch_general", 1, "u_other")),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )
        session.realtime.applyTypingStub("ch_general", "u_other", true, 1_000L)
        session.realtime.applyTypingStub("ch_general", "u_ghost", true, 1_000L)
        session.realtime.applyTypingStub("ch_general", "u_self", true, 1_000L)

        val state = session.channelState(
            RailChannel("ch_general", "general", ChannelKind.Text, 0, ComposerState.Enabled),
            null,
        )

        assertEquals(setOf("Other", "u_ghost"), state.typists.toSet())
    }

    @Test
    fun staleOnlyWhenDisconnectedWithCachedRows() {
        startWith(
            history = listOf(sessionMessage("m1", "ch_general", 1, "u_other")),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )
        val rail = RailChannel("ch_general", "general", ChannelKind.Text, 0, ComposerState.Enabled)

        assertTrue(session.channelState(rail, null).stale)
        transport.setConnected(true)
        assertFalse(session.channelState(rail, null).stale)
    }

    @Test
    fun staleIsFalseWhenNoCachedRows() {
        startWith(
            history = emptyList(),
            members = listOf(sessionMember("u_self", "Self")),
        )
        val rail = RailChannel("ch_general", "general", ChannelKind.Text, 0, ComposerState.Enabled)

        assertFalse(session.channelState(rail, null).stale)
    }

    @Test
    fun statusIsLoadingWithNoRowsAndNoRecordedStatus() {
        transport = FakeSessionTransport(
            me = "u_self" to sessionMember("u_self", "Self"),
            members = listOf(sessionMember("u_self", "Self")),
            channels = listOf(sessionChannel("ch_general")),
        )
        session = KingdomSession(transport, scope, InMemoryLastVisitedStore(), InMemoryOutboxPersistence())

        val state = session.channelState(
            RailChannel("ch_general", "general", ChannelKind.Text, 0, ComposerState.Enabled),
            null,
        )

        assertEquals(ChannelStatus.Loading, state.status)
    }

    @Test
    fun statusIsReadyFromRowsWithoutRecordedStatus() {
        transport = FakeSessionTransport(
            me = "u_self" to sessionMember("u_self", "Self"),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
            channels = listOf(sessionChannel("ch_general")),
        )
        session = KingdomSession(transport, scope, InMemoryLastVisitedStore(), InMemoryOutboxPersistence())
        session.start()
        emit(KingdomInbound.Message(sessionMessage("m1", "ch_general", 1, "u_other")))

        val state = session.channelState(
            RailChannel("ch_general", "general", ChannelKind.Text, 0, ComposerState.Enabled),
            null,
        )

        assertEquals(ChannelStatus.Ready, state.status)
        assertEquals(listOf("m1"), state.messages.map { it.id })
    }

    @Test
    fun reactionsEnabledFollowsTheReactEntitlement() {
        startWith(
            history = listOf(sessionMessage("m1", "ch_general", 1, "u_other")),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )
        val rail = RailChannel("ch_general", "general", ChannelKind.Text, 0, ComposerState.Enabled)
        val withReact = sessionEntitlements().copy(
            capabilities = sessionEntitlements().capabilities.copy(react = listOf("ch_general")),
        )

        assertTrue(session.channelState(rail, withReact).reactionsEnabled)
        assertFalse(session.channelState(rail, sessionEntitlements()).reactionsEnabled)
        assertFalse(session.channelState(rail, null).reactionsEnabled)
    }

    @Test
    fun effectiveComposerReturnsNonEnabledBaseUntouched() {
        startWith(
            history = emptyList(),
            members = listOf(sessionMember("u_self", "Self")),
        )
        val rail = RailChannel("ch_general", "general", ChannelKind.Text, 0, ComposerState.Disabled("nope"))

        assertEquals(ComposerState.Disabled("nope"), session.effectiveComposer(rail, 1_000L))
    }

    @Test
    fun effectiveComposerReturnsBaseWhenChannelAbsentFromSnapshot() {
        startWith(
            history = emptyList(),
            members = listOf(sessionMember("u_self", "Self")),
        )
        val rail = RailChannel("ch_absent", "absent", ChannelKind.Text, 0, ComposerState.Enabled)

        assertEquals(ComposerState.Enabled, session.effectiveComposer(rail, 1_000L))
    }

    @Test
    fun effectiveComposerReturnsBaseWhenSlowmodeIsZero() {
        startWith(
            history = emptyList(),
            members = listOf(sessionMember("u_self", "Self")),
        )
        val rail = RailChannel("ch_general", "general", ChannelKind.Text, 0, ComposerState.Enabled)

        assertEquals(ComposerState.Enabled, session.effectiveComposer(rail, 1_000L))
    }

    @Test
    fun effectiveComposerReturnsBaseWhenNoAckRecorded() {
        transport = FakeSessionTransport(
            me = "u_self" to sessionMember("u_self", "Self"),
            members = listOf(sessionMember("u_self", "Self")),
            channels = listOf(sessionChannel("ch_general").copy(slowmodeSec = 30)),
            history = emptyList(),
        )
        session = KingdomSession(transport, scope, InMemoryLastVisitedStore(), InMemoryOutboxPersistence())
        session.start()
        val rail = RailChannel("ch_general", "general", ChannelKind.Text, 0, ComposerState.Enabled)

        assertEquals(ComposerState.Enabled, session.effectiveComposer(rail, 1_000L))
    }

    @Test
    fun effectiveComposerYieldsSlowmodeInsideTheWindow() {
        transport = FakeSessionTransport(
            me = "u_self" to sessionMember("u_self", "Self"),
            members = listOf(sessionMember("u_self", "Self")),
            channels = listOf(sessionChannel("ch_general").copy(slowmodeSec = 30)),
            history = emptyList(),
        )
        session = KingdomSession(transport, scope, InMemoryLastVisitedStore(), InMemoryOutboxPersistence())
        session.start()
        session.openChannel("ch_general")
        session.sendController.submit("ch_general", "timed body", online = false)
        val key = session.outbox.forChannel("ch_general").single().idempotencyKey

        val ackAtMs = System.currentTimeMillis()
        emit(KingdomInbound.Ack(key, "m_timed", 7))

        val rail = RailChannel("ch_general", "general", ChannelKind.Text, 0, ComposerState.Enabled)
        assertEquals(ComposerState.Slowmode(30), session.effectiveComposer(rail, ackAtMs))
    }

    @Test
    fun effectiveComposerReturnsBaseAfterTheWindowExpires() {
        transport = FakeSessionTransport(
            me = "u_self" to sessionMember("u_self", "Self"),
            members = listOf(sessionMember("u_self", "Self")),
            channels = listOf(sessionChannel("ch_general").copy(slowmodeSec = 30)),
            history = emptyList(),
        )
        session = KingdomSession(transport, scope, InMemoryLastVisitedStore(), InMemoryOutboxPersistence())
        session.start()
        session.openChannel("ch_general")
        session.sendController.submit("ch_general", "timed body", online = false)
        val key = session.outbox.forChannel("ch_general").single().idempotencyKey

        val ackAtMs = System.currentTimeMillis()
        emit(KingdomInbound.Ack(key, "m_timed", 7))

        val rail = RailChannel("ch_general", "general", ChannelKind.Text, 0, ComposerState.Enabled)
        assertEquals(ComposerState.Enabled, session.effectiveComposer(rail, ackAtMs + 31_000L))
    }

    @Test
    fun threadSnapshotReturnsBaseUnchangedWhenNoCachedReplies() {
        startWith(
            history = listOf(sessionMessage("m1", "ch_general", 1, "u_other")),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )
        val existing = ChannelMessageUi(
            id = "mx", seq = 5L, authorName = "Other", authorRole = OreRole.Member,
            body = "kept", timestamp = "2026-11-27T00:00:00Z",
        )
        val base = ThreadUiState(
            threadId = "th_1", title = "Thread title", status = ChannelStatus.Ready,
            replies = listOf(existing),
        )

        val result = session.threadSnapshot("ch_general", base)

        assertEquals(base, result)
        assertEquals(listOf(existing), result.replies)
    }

    @Test
    fun threadSnapshotReplacesRepliesSortedBySeq() {
        startWith(
            history = listOf(
                sessionMessage("m1", "ch_general", 1, "u_other"),
                sessionMessage("r2", "ch_general", 9, "u_other").copy(threadId = "th_1"),
                sessionMessage("r1", "ch_general", 8, "u_self").copy(threadId = "th_1"),
            ),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )
        val base = ThreadUiState(threadId = "th_1", title = "Thread title", status = ChannelStatus.Ready)

        val result = session.threadSnapshot("ch_general", base)

        assertEquals(listOf("r1", "r2"), result.replies.map { it.id })
        assertEquals(listOf(8L, 9L), result.replies.map { it.seq })
        assertEquals("Self", result.replies[0].authorName)
        assertTrue(result.replies[0].own)
        assertEquals("Other", result.replies[1].authorName)
    }

    @Test
    fun threadSnapshotExcludesOtherThreadsAndChannels() {
        startWith(
            history = listOf(
                sessionMessage("r1", "ch_general", 8, "u_other").copy(threadId = "th_1"),
                sessionMessage("x1", "ch_general", 9, "u_other").copy(threadId = "th_2"),
                sessionMessage("m1", "ch_general", 1, "u_other"),
            ),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )
        transport.threads = listOf(
            ContractThread("th_1", "ch_other", null, "Other channel thread", "open", 1, "2026-11-27T02:00:00Z"),
        )
        transport.threadHistory = listOf(
            sessionMessage("x2", "ch_other", 3, "u_other").copy(threadId = "th_1"),
        )
        runBlocking { session.loadThread("ch_other", "th_1") }
        val base = ThreadUiState(threadId = "th_1", title = "Thread title", status = ChannelStatus.Ready)

        val result = session.threadSnapshot("ch_general", base)

        assertEquals(listOf("r1"), result.replies.map { it.id })
    }

    @Test
    fun toggleReactionRemovesWhenMineIsTrue() {
        startWith(
            history = listOf(
                sessionMessage(
                    "m1", "ch_general", 1, "u_other",
                    reactions = listOf(ContractReaction("🔥", 2, true)),
                ),
            ),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )

        session.toggleReaction("ch_general", "m1", "🔥")

        val frame = transport.sentFrames.single()
        assertTrue(frame.contains("\"op\":\"remove\""))
        assertTrue(frame.contains("\"message_id\":\"m1\""))
    }

    @Test
    fun toggleReactionAddsWhenNotMineAbsentOrMessageMissing() {
        startWith(
            history = listOf(
                sessionMessage(
                    "m1", "ch_general", 1, "u_other",
                    reactions = listOf(ContractReaction("🔥", 2, false)),
                ),
            ),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )

        session.toggleReaction("ch_general", "m1", "🔥")
        session.toggleReaction("ch_general", "m1", "👍")
        session.toggleReaction("ch_general", "m_missing", "🔥")

        assertEquals(3, transport.sentFrames.size)
        assertTrue(transport.sentFrames.all { it.contains("\"op\":\"add\"") })
        assertTrue(transport.sentFrames[2].contains("\"message_id\":\"m_missing\""))
    }

    @Test
    fun openChannelFetchesNewestPageWhenNothingCached() {
        startWith(
            history = listOf(sessionMessage("m1", "ch_general", 1, "u_other")),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )

        assertEquals(1, transport.historyRequests.size)
        assertEquals("ch_general", transport.historyRequests[0].channelId)
        assertEquals(null, transport.historyRequests[0].beforeSeq)
    }

    @Test
    fun openChannelClearsUnreadAndSelectsTheChannel() {
        transport = FakeSessionTransport(
            me = "u_self" to sessionMember("u_self", "Self"),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
            channels = listOf(sessionChannel("ch_general"), sessionChannel("ch_pump")),
            history = listOf(sessionMessage("m1", "ch_general", 1, "u_other")),
        )
        session = KingdomSession(transport, scope, InMemoryLastVisitedStore(), InMemoryOutboxPersistence())
        session.start()
        session.openChannel("ch_general")
        emit(KingdomInbound.Message(sessionMessage("p1", "ch_pump", 1, "u_other")))

        val before = session.store.rail.value.categories
            .flatMap { it.channels }.first { it.id == "ch_pump" }
        assertEquals(1, before.unreadCount)

        session.openChannel("ch_pump")

        val after = session.store.rail.value.categories
            .flatMap { it.channels }.first { it.id == "ch_pump" }
        assertEquals(0, after.unreadCount)
        assertEquals("ch_pump", session.store.rail.value.lastVisitedChannelId)
    }

    @Test
    fun openChannelWithCachedMessagesIssuesNoFetch() {
        startWith(
            history = listOf(sessionMessage("m1", "ch_general", 1, "u_other")),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )
        transport.historyRequests.clear()

        session.openChannel("ch_general")

        assertTrue(transport.historyRequests.isEmpty())
    }

    @Test
    fun loadOlderWithNoCachedMessagesIsANoOp() {
        transport = FakeSessionTransport(
            me = "u_self" to sessionMember("u_self", "Self"),
            members = listOf(sessionMember("u_self", "Self")),
            channels = listOf(sessionChannel("ch_general")),
            history = listOf(sessionMessage("m1", "ch_general", 1, "u_other")),
        )
        session = KingdomSession(transport, scope, InMemoryLastVisitedStore(), InMemoryOutboxPersistence())
        session.start()

        session.loadOlder("ch_general")

        assertTrue(transport.historyRequests.isEmpty())
    }

    @Test
    fun loadOlderIsANoOpWhenNoOlderPages() {
        startWith(
            history = listOf(sessionMessage("m1", "ch_general", 1, "u_other")),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )
        transport.historyRequests.clear()

        session.loadOlder("ch_general")

        assertTrue(transport.historyRequests.isEmpty())
    }

    @Test
    fun loadOlderFetchesBeforeTheOldestCachedSeq() {
        transport = FakeSessionTransport(
            me = "u_self" to sessionMember("u_self", "Self"),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
            channels = listOf(sessionChannel("ch_general")),
            history = listOf(
                sessionMessage("m9", "ch_general", 9, "u_other"),
                sessionMessage("m5", "ch_general", 5, "u_other"),
            ),
        )
        transport.historyHasMore = true
        session = KingdomSession(transport, scope, InMemoryLastVisitedStore(), InMemoryOutboxPersistence())
        session.start()
        session.openChannel("ch_general")
        transport.historyRequests.clear()

        session.loadOlder("ch_general")

        assertEquals(1, transport.historyRequests.size)
        assertEquals("ch_general", transport.historyRequests[0].channelId)
        assertEquals(5L, transport.historyRequests[0].beforeSeq)
    }

    @Test
    fun openChannelReissuesThePendingPlanRequest() {
        startWith(
            history = listOf(sessionMessage("m1", "ch_general", 1, "u_other")),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
        )
        transport.historyRequests.clear()
        emit(KingdomInbound.Message(sessionMessage("m5", "ch_general", 5, "u_other")))

        session.openChannel("ch_general")

        assertTrue(
            transport.historyRequests.any { it.channelId == "ch_general" && it.beforeSeq == 5L },
        )
    }

    @Test
    fun retryChannelDropsTheErrorStatusAndRefetches() {
        transport = FakeSessionTransport(
            me = "u_self" to sessionMember("u_self", "Self"),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
            ),
            channels = listOf(sessionChannel("ch_general")),
            history = listOf(sessionMessage("m1", "ch_general", 1, "u_other")),
        )
        transport.historyError = KingdomHttpException(404, "missing")
        session = KingdomSession(transport, scope, InMemoryLastVisitedStore(), InMemoryOutboxPersistence())
        session.start()
        session.openChannel("ch_general")
        val rail = RailChannel("ch_general", "general", ChannelKind.Text, 0, ComposerState.Enabled)
        assertEquals(ChannelStatus.Error, session.channelState(rail, null).status)

        transport.historyError = null
        transport.historyRequests.clear()
        session.retryChannel("ch_general")

        assertEquals(1, transport.historyRequests.size)
        assertEquals(null, transport.historyRequests[0].beforeSeq)
        assertEquals(ChannelStatus.Ready, session.channelState(rail, null).status)
        assertEquals(listOf("m1"), rows().map { it.id })
    }
}
