package com.raaru.orekingdom.channel

import com.raaru.orekingdom.designsystem.components.OrePresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Realtime state-machine edges beyond ChannelRealtimeTest (DeepSeek
 * programme item 2): resync windows, history-plan paging, disconnect
 * semantics, typing expiry, presence merging. Supervisor-authored
 * after three empty DeepSeek drafts (see the programme record).
 */
class ChannelRealtimeEdgeTest {

    private fun rt(vararg ids: String, connected: Boolean = false): ChannelRealtime {
        val r = ChannelRealtime()
        if (connected) r.onConnected()
        r.applyEntitledChannels(ids.toList())
        return r
    }

    private fun ChannelRealtime.sync(id: String) = state.value.channels.getValue(id)

    @Test
    fun firstMessageWithoutSeedIsAcceptedAtAnySeq() {
        val r = rt("a")
        val d = r.onMessage("a", 42)
        assertTrue(d is MessageDecision.Accept)
        assertEquals(42L, r.sync("a").lastSeq)
        assertEquals(ChannelSyncMode.Live, r.sync("a").mode)
    }

    @Test
    fun gapWhileResumeInFlightFallsBackToHistoryPlan() {
        val r = rt("a")
        r.seedLastSeq("a", 10)
        val first = r.onMessage("a", 15)
        assertTrue(first is MessageDecision.Deferred)
        assertTrue(r.sync("a").resumeInFlight)
        // A second gapped frame while the replay is still in flight:
        // the machine stops waiting on the replay and pages history.
        val second = r.onMessage("a", 16)
        assertTrue(second is MessageDecision.Accept)
        val out = (second as MessageDecision.Accept).outbound
        assertEquals(1, out.size)
        val req = (out[0] as RealtimeOutbound.History).request
        assertEquals(16L, req.beforeSeq)
        val plan = r.sync("a").plan
        assertEquals(10L, plan?.untilSeq)
        assertFalse(plan!!.replaceWindow)
        assertEquals(16L, r.sync("a").lastSeq)
    }

    @Test
    fun resyncDefersLiveMessagesUntilReplacePageCompletes() {
        val r = rt("a")
        r.seedLastSeq("a", 10)
        val out = r.onResync("a", "behind")
        assertEquals(1, out.size)
        val req = (out[0] as RealtimeOutbound.History).request
        assertNull(req.beforeSeq)
        assertEquals(ChannelSyncMode.CatchUp, r.sync("a").mode)
        val deferred = r.onMessage("a", 11)
        assertTrue(deferred is MessageDecision.Deferred)
        assertTrue((deferred as MessageDecision.Deferred).outbound.isEmpty())
        val result = r.onHistoryPage("a", oldestSeq = 5, newestSeq = 20, hasMore = true)
        assertTrue(result.complete)
        assertTrue(result.replaceWindow)
        assertEquals(20L, r.sync("a").lastSeq)
        assertEquals(ChannelSyncMode.Live, r.sync("a").mode)
        assertTrue(r.onMessage("a", 21) is MessageDecision.Accept)
    }

    @Test
    fun resyncForUnknownChannelIsIgnored() {
        val r = rt("a")
        assertTrue(r.onResync("zzz", "gap").isEmpty())
        assertFalse(r.state.value.channels.containsKey("zzz"))
    }

    @Test
    fun historyPlanPagesBackToUntilSeqThenCompletes() {
        val r = rt("a")
        r.seedLastSeq("a", 10)
        r.onMessage("a", 700)
        val page1 = r.onHistoryPage("a", oldestSeq = 650, newestSeq = 699, hasMore = true)
        assertFalse(page1.complete)
        assertEquals(650L, page1.next?.request?.beforeSeq)
        val page2 = r.onHistoryPage("a", oldestSeq = 11, newestSeq = 649, hasMore = true)
        assertTrue(page2.complete)
        assertNull(page2.next)
        assertEquals(ChannelSyncMode.Live, r.sync("a").mode)
        assertNull(r.sync("a").plan)
    }

    @Test
    fun historyPlanCompletesWhenServerHasNoMorePages() {
        val r = rt("a")
        r.seedLastSeq("a", 10)
        r.onMessage("a", 700)
        val result = r.onHistoryPage("a", oldestSeq = 650, newestSeq = 699, hasMore = false)
        assertTrue(result.complete)
        assertEquals(ChannelSyncMode.Live, r.sync("a").mode)
    }

    @Test
    fun pendingHistoryReissuesThePlansCurrentCursor() {
        val r = rt("a")
        r.seedLastSeq("a", 10)
        assertNull(r.pendingHistory("a"))
        r.onMessage("a", 700)
        assertEquals(700L, r.pendingHistory("a")?.request?.beforeSeq)
        r.onHistoryPage("a", oldestSeq = 650, newestSeq = 699, hasMore = true)
        assertEquals(650L, r.pendingHistory("a")?.request?.beforeSeq)
        r.onHistoryPage("a", oldestSeq = 11, newestSeq = 649, hasMore = true)
        assertNull(r.pendingHistory("a"))
        assertNull(r.pendingHistory("unknown"))
    }

    @Test
    fun reconnectAfterPlanDropsPlanAndResumesFromLastSeq() {
        val r = rt("a")
        r.seedLastSeq("a", 10)
        r.onMessage("a", 700)
        r.onDisconnected()
        val out = r.onConnected()
        val resume = out.filterIsInstance<RealtimeOutbound.Resume>().single()
        assertEquals(mapOf("a" to 10L), resume.lastSeq)
        assertTrue(out.none { it is RealtimeOutbound.History })
        assertNull(r.sync("a").plan)
        assertTrue(r.sync("a").resumeInFlight)
    }

    @Test
    fun disconnectKeepsSeqAndPresenceButDropsTypingAndCatchUp() {
        val r = rt("a", connected = true)
        r.seedLastSeq("a", 10)
        r.applyPresence(ChannelRealtime.presenceOf("u1", "online", "pumping", false))
        r.applyTypingStub("a", "u2", true, 0L)
        r.onMessage("a", 15)
        assertTrue(r.sync("a").resumeInFlight)
        r.onDisconnected()
        assertFalse(r.state.value.connected)
        assertEquals(10L, r.sync("a").lastSeq)
        assertFalse(r.sync("a").resumeInFlight)
        assertNull(r.sync("a").plan)
        assertTrue(r.state.value.typists("a").isEmpty())
        // Presence is rebuilt on connect, not dropped on disconnect.
        assertEquals(OrePresence.Online, r.state.value.presence.getValue("u1").state)
        r.onConnected()
        assertTrue(r.state.value.presence.isEmpty())
    }

    @Test
    fun typingStubRespectsSubscriptionAndExpiryBoundary() {
        val r = rt("a")
        r.applyTypingStub("zzz", "u1", true, 0L)
        assertFalse(r.state.value.typing.containsKey("zzz"))
        r.applyTypingStub("a", "u1", true, 1_000L)
        assertEquals(setOf("u1"), r.state.value.typists("a"))
        r.expireTyping(6_999L)
        assertEquals(setOf("u1"), r.state.value.typists("a"))
        // Expiry is exclusive: an entry expiring at 7000 is gone at 7000.
        r.expireTyping(7_000L)
        assertTrue(r.state.value.typists("a").isEmpty())
        r.applyTypingStub("a", "u2", true, 0L)
        r.applyTypingStub("a", "u2", false, 0L)
        assertTrue(r.state.value.typists("a").isEmpty())
    }

    @Test
    fun presenceSnapshotMergesPerUser() {
        val r = rt("a")
        r.applyPresence(ChannelRealtime.presenceOf("u1", "online", "pumping", false))
        r.applyPresence(ChannelRealtime.presenceOf("u3", "online", null, false))
        r.applyPresenceSnapshot(
            listOf(
                ChannelRealtime.presenceOf("u1", "idle", null, false),
                ChannelRealtime.presenceOf("u2", "online", "in a round", false),
            ),
        )
        val presence = r.state.value.presence
        assertEquals(OrePresence.Idle, presence.getValue("u1").state)
        assertEquals("in a round", presence.getValue("u2").activity)
        assertEquals(OrePresence.Online, presence.getValue("u3").state)
    }
}
