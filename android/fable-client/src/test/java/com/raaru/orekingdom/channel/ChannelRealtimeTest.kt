package com.raaru.orekingdom.channel

import com.raaru.orekingdom.designsystem.components.OrePresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelRealtimeTest {

    private fun rt(vararg ids: String, connected: Boolean = false): ChannelRealtime {
        val r = ChannelRealtime()
        if (connected) r.onConnected()
        r.applyEntitledChannels(ids.toList())
        return r
    }

    private fun ChannelRealtime.sync(id: String) = state.value.channels.getValue(id)

    @Test
    fun seedLastSeqSetsAndNeverMovesBackwards() {
        val r = rt("a")
        r.seedLastSeq("a", 10)
        assertEquals(10L, r.sync("a").lastSeq)
        r.seedLastSeq("a", 5)
        assertEquals(10L, r.sync("a").lastSeq)
        r.seedLastSeq("a", 10)
        assertEquals(10L, r.sync("a").lastSeq)
        r.seedLastSeq("a", 12)
        assertEquals(12L, r.sync("a").lastSeq)
        r.seedLastSeq("unknown", 3)
        assertFalse(r.state.value.channels.containsKey("unknown"))
    }

    @Test
    fun contiguousSeqIsAccepted() {
        val r = rt("a")
        r.seedLastSeq("a", 10)
        val d = r.onMessage("a", 11)
        assertTrue(d is MessageDecision.Accept)
        assertTrue((d as MessageDecision.Accept).outbound.isEmpty())
        assertEquals(11L, r.sync("a").lastSeq)
        assertEquals(ChannelSyncMode.Live, r.sync("a").mode)
    }

    @Test
    fun alreadySeenSeqIsDuplicate() {
        val r = rt("a")
        r.seedLastSeq("a", 10)
        assertEquals(MessageDecision.Duplicate, r.onMessage("a", 10))
        assertEquals(MessageDecision.Duplicate, r.onMessage("a", 3))
        assertEquals(10L, r.sync("a").lastSeq)
    }

    @Test
    fun smallGapDefersAndRequestsResumeFromLastSeq() {
        val r = rt("a")
        r.seedLastSeq("a", 10)
        val d = r.onMessage("a", 15)
        assertTrue(d is MessageDecision.Deferred)
        val out = (d as MessageDecision.Deferred).outbound
        assertEquals(listOf<RealtimeOutbound>(RealtimeOutbound.Resume(mapOf("a" to 10L))), out)
        assertEquals(10L, r.sync("a").lastSeq)
        assertEquals(ChannelSyncMode.CatchUp, r.sync("a").mode)
        assertTrue(r.sync("a").resumeInFlight)
    }

    @Test
    fun gapOfExactlyReplayLimitStillResumes() {
        val r = rt("a")
        r.seedLastSeq("a", 0)
        val d = r.onMessage("a", 501)
        assertTrue(d is MessageDecision.Deferred)
    }

    @Test
    fun gapOverReplayLimitProducesHistoryPlan() {
        val r = rt("a")
        r.seedLastSeq("a", 10)
        val d = r.onMessage("a", 700)
        assertTrue(d is MessageDecision.Accept)
        val out = (d as MessageDecision.Accept).outbound
        assertEquals(1, out.size)
        val req = (out[0] as RealtimeOutbound.History).request
        assertEquals("a", req.channelId)
        assertEquals(700L, req.beforeSeq)
        val plan = r.sync("a").plan
        assertNotNull(plan)
        assertEquals(10L, plan!!.untilSeq)
        assertFalse(plan.replaceWindow)
        assertEquals(ChannelSyncMode.CatchUp, r.sync("a").mode)
    }

    @Test
    fun unsubscribedChannelMessageIsIgnored() {
        val r = rt("a")
        assertEquals(MessageDecision.Ignore, r.onMessage("zzz", 1))
    }

    @Test
    fun removedChannelDropsSyncAndTypingAndIsReported() {
        val r = rt("a", "b")
        r.seedLastSeq("a", 4)
        r.applyTypingStub("a", "u1", true, 0L)
        assertEquals(setOf("u1"), r.state.value.typists("a"))
        val change = r.applyEntitledChannels(listOf("b"))
        assertEquals(setOf("a"), change.removedChannelIds)
        assertFalse(r.state.value.channels.containsKey("a"))
        assertFalse(r.state.value.typing.containsKey("a"))
        assertEquals(setOf("b"), r.state.value.subscribed)
        assertEquals(MessageDecision.Ignore, r.onMessage("a", 5))
    }

    @Test
    fun subscriptionOutboundOnlyWhenConnected() {
        val offline = rt("a")
        val c1 = offline.applyEntitledChannels(listOf("b"))
        assertTrue(c1.outbound.isEmpty())
        assertEquals(setOf("a"), c1.removedChannelIds)

        val online = rt("a", connected = true)
        val c2 = online.applyEntitledChannels(listOf("b"))
        assertTrue(c2.outbound.contains(RealtimeOutbound.Unsubscribe(listOf("a"))))
        assertTrue(c2.outbound.contains(RealtimeOutbound.Subscribe(listOf("b"))))
        assertTrue(c2.outbound.any { it is RealtimeOutbound.History && it.request.channelId == "b" })
    }

    @Test
    fun onConnectedResumesKnownAndFetchesHistoryForNeverLoaded() {
        val r = rt("a", "b")
        r.seedLastSeq("a", 42)
        val out = r.onConnected()
        assertTrue(r.state.value.connected)
        val sub = out.filterIsInstance<RealtimeOutbound.Subscribe>().single()
        assertEquals(setOf("a", "b"), sub.channelIds.toSet())
        val resume = out.filterIsInstance<RealtimeOutbound.Resume>().single()
        assertEquals(mapOf("a" to 42L), resume.lastSeq)
        val hist = out.filterIsInstance<RealtimeOutbound.History>().single()
        assertEquals("b", hist.request.channelId)
        assertNull(hist.request.beforeSeq)
        assertEquals(ChannelSyncMode.CatchUp, r.sync("a").mode)
        assertTrue(r.sync("a").resumeInFlight)
    }

    @Test
    fun onConnectedWithNoChannelsEmitsNothing() {
        val r = ChannelRealtime()
        assertTrue(r.onConnected().isEmpty())
        assertTrue(r.state.value.connected)
    }

    @Test
    fun subscribeRefusedDropsOnlyRefusedIds() {
        val r = rt("a", "b")
        r.applyTypingStub("a", "u1", true, 0L)
        val dropped = r.onSubscribeRefused(listOf("a", "notSubscribed"))
        assertEquals(setOf("a"), dropped)
        assertEquals(setOf("b"), r.state.value.subscribed)
        assertFalse(r.state.value.channels.containsKey("a"))
        assertFalse(r.state.value.typing.containsKey("a"))
    }

    @Test
    fun presenceIsRebuiltOnConnect() {
        val r = rt("a")
        r.applyPresence(ChannelRealtime.presenceOf("u1", "online", "pumping", false))
        assertEquals("pumping", r.state.value.presence.getValue("u1").activity)
        r.onConnected()
        assertTrue(r.state.value.presence.isEmpty())
        assertEquals(OrePresence.Offline, ChannelRealtime.presenceOf("u", "weird", null, false).state)
    }
}
