package com.raaru.orekingdom.channel

import com.raaru.orekingdom.designsystem.components.OreMessageDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Send-pipeline edges (DeepSeek programme item 2): ack idempotency,
 * refusal handling, disconnect/re-drain with stable idempotency keys.
 * Supervisor-authored after three empty DeepSeek drafts (see the
 * programme record); assertions were pre-designed in the call template.
 */
class ChannelSendControllerTest {

    private class Rig {
        val transmitted = mutableListOf<OutboxEntry>()
        val acked = mutableListOf<String>()
        private val keys = generateSequence(1) { it + 1 }.map { "k$it" }.iterator()
        val outbox = ChannelOutbox(
            keyFactory = { keys.next() },
            now = { "2026-11-26T17:00:00Z" },
        )
        val controller = ChannelSendController(
            outbox = outbox,
            transmit = { transmitted += it },
            onSendAck = { acked += it },
        )
    }

    private fun queued(r: OutboxEnqueueResult): OutboxEntry = (r as OutboxEnqueueResult.Queued).entry

    @Test
    fun submitOnlineTransmitsImmediatelyWithTheEntryKey() {
        val rig = Rig()
        val entry = queued(rig.controller.submit("ch", "hello", online = true))
        assertEquals("k1", entry.idempotencyKey)
        assertEquals(listOf("k1"), rig.transmitted.map { it.idempotencyKey })
        assertEquals(OreMessageDelivery.Sending, rig.controller.deliveryOf("k1"))
        assertEquals(1, rig.outbox.size)
    }

    @Test
    fun submitOfflineQueuesWithoutTransmitting() {
        val rig = Rig()
        queued(rig.controller.submit("ch", "later", online = false))
        assertTrue(rig.transmitted.isEmpty())
        assertEquals(OreMessageDelivery.Queued, rig.controller.deliveryOf("k1"))
        assertEquals(1, rig.outbox.size)
        rig.controller.drain()
        assertEquals(listOf("k1"), rig.transmitted.map { it.idempotencyKey })
        assertEquals(OreMessageDelivery.Sending, rig.controller.deliveryOf("k1"))
    }

    @Test
    fun drainIsFifoAndSkipsInFlightEntries() {
        val rig = Rig()
        rig.controller.submit("ch", "a", online = false)
        rig.controller.submit("ch", "b", online = false)
        rig.controller.submit("ch", "c", online = false)
        rig.controller.drain()
        assertEquals(listOf("a", "b", "c"), rig.transmitted.map { it.body })
        assertEquals(listOf("k1", "k2", "k3"), rig.transmitted.map { it.idempotencyKey })
        rig.controller.drain()
        assertEquals(3, rig.transmitted.size)
    }

    @Test
    fun retryUnackedResendsOnlyInFlightWithSameKeys() {
        val rig = Rig()
        rig.controller.submit("ch", "a", online = false)
        rig.controller.submit("ch", "b", online = false)
        rig.controller.drain()
        rig.controller.handleAck("k1")
        rig.controller.submit("ch", "c", online = false)
        rig.controller.retryUnacked()
        assertEquals(listOf("k1", "k2", "k2"), rig.transmitted.map { it.idempotencyKey })
        assertEquals(2, rig.outbox.peek("k2")!!.attempts)
        assertEquals(0, rig.outbox.peek("k3")!!.attempts)
        assertEquals(OreMessageDelivery.Queued, rig.controller.deliveryOf("k3"))
    }

    @Test
    fun ackFiresCallbackOnceAndDuplicateAckIsIgnored() {
        val rig = Rig()
        rig.controller.submit("ch", "a", online = true)
        rig.controller.handleAck("k1")
        assertEquals(listOf("k1"), rig.acked)
        assertEquals(0, rig.outbox.size)
        rig.controller.handleAck("k1")
        rig.controller.handleAck("missing")
        assertEquals(listOf("k1"), rig.acked)
    }

    @Test
    fun refusedRemovesEntryAndReturnsItWithoutCallback() {
        val rig = Rig()
        rig.controller.submit("ch", "a", online = true)
        rig.controller.submit("ch", "b", online = true)
        val removed = rig.controller.handleRefused("k1")
        assertEquals("a", removed?.body)
        assertEquals(1, rig.outbox.size)
        assertTrue(rig.acked.isEmpty())
        assertEquals(OreMessageDelivery.Queued, rig.controller.deliveryOf("k1"))
        assertNull(rig.controller.handleRefused("k1"))
    }

    @Test
    fun disconnectClearsInFlightAndRedrainResendsSameKeys() {
        val rig = Rig()
        rig.controller.submit("ch", "a", online = true)
        rig.controller.submit("ch", "b", online = true)
        rig.controller.onDisconnected()
        assertEquals(OreMessageDelivery.Queued, rig.controller.deliveryOf("k1"))
        assertEquals(OreMessageDelivery.Queued, rig.controller.deliveryOf("k2"))
        assertEquals(2, rig.outbox.size)
        rig.controller.drain()
        assertEquals(listOf("k1", "k2", "k1", "k2"), rig.transmitted.map { it.idempotencyKey })
    }

    @Test
    fun ackOfOneKeyLeavesTheOtherInFlight() {
        val rig = Rig()
        rig.controller.submit("ch", "a", online = true)
        rig.controller.submit("ch", "b", online = true)
        rig.controller.handleAck("k2")
        assertEquals(OreMessageDelivery.Sending, rig.controller.deliveryOf("k1"))
        assertEquals(OreMessageDelivery.Queued, rig.controller.deliveryOf("k2"))
        assertEquals(1, rig.outbox.size)
        assertTrue(rig.outbox.peek("k1") != null)
        assertNull(rig.outbox.peek("k2"))
        assertEquals(listOf("k2"), rig.acked)
    }
}
