package com.raaru.orekingdom.kingdom

import com.raaru.orekingdom.channel.ChannelMessageUi
import com.raaru.orekingdom.designsystem.components.ComposerState
import com.raaru.orekingdom.designsystem.components.OreReaction
import com.raaru.orekingdom.designsystem.components.OreRole
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
}
