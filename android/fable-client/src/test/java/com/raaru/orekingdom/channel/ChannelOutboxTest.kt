package com.raaru.orekingdom.channel

import com.raaru.orekingdom.designsystem.components.OreMessageDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelOutboxTest {

    private class FakePersistence(var stored: String? = null) : OutboxPersistence {
        var writes = 0
        override fun write(serialized: String) {
            writes++
            stored = serialized
        }
        override fun read(): String? = stored
    }

    private fun outbox(
        capacity: Int = ChannelOutbox.MIN_CAPACITY,
        persistence: OutboxPersistence? = null,
        keys: Iterator<String> = generateSequence(1) { it + 1 }.map { "k$it" }.iterator(),
    ) = ChannelOutbox(
        capacity = capacity,
        persistence = persistence,
        keyFactory = { keys.next() },
        now = { "2026-11-26T17:00:00Z" },
    )

    private fun queued(r: OutboxEnqueueResult): OutboxEntry = (r as OutboxEnqueueResult.Queued).entry

    @Test
    fun enqueuePreservesFifoOrder() {
        val o = outbox()
        o.enqueue("ch_a", "one")
        o.enqueue("ch_b", "two")
        o.enqueue("ch_a", "three")
        assertEquals(listOf("one", "two", "three"), o.drainOrder().map { it.body })
        assertEquals("one", o.peek()?.body)
        assertEquals(listOf("one", "three"), o.forChannel("ch_a").map { it.body })
        assertEquals(listOf("k1", "k2", "k3"), o.entries.value.map { it.idempotencyKey })
    }

    @Test
    fun duplicateFactoryKeyIsRegeneratedNotDuplicated() {
        val o = outbox(keys = listOf("dup", "dup", "other").iterator())
        val first = queued(o.enqueue("ch", "a"))
        val second = queued(o.enqueue("ch", "b"))
        assertEquals("dup", first.idempotencyKey)
        assertEquals("other", second.idempotencyKey)
        assertEquals(2, o.size)
    }

    @Test
    fun ackRemovesOnlyKeyedEntryAndReportsAbsence() {
        val o = outbox()
        o.enqueue("ch", "a")
        o.enqueue("ch", "b")
        o.enqueue("ch", "c")
        assertTrue(o.ack("k2"))
        assertEquals(listOf("a", "c"), o.drainOrder().map { it.body })
        assertFalse(o.ack("k2"))
        assertFalse(o.ack("missing"))
        assertFalse(o.remove("missing"))
        assertEquals(2, o.size)
        assertNull(o.peek("k2"))
        assertNotNull(o.peek("k1"))
    }

    @Test
    fun fifoHoldsAcrossInterleavedEnqueueAndAck() {
        val o = outbox()
        o.enqueue("ch", "a")
        o.enqueue("ch", "b")
        o.ack("k1")
        o.enqueue("ch", "c")
        assertEquals(listOf("b", "c"), o.drainOrder().map { it.body })
        assertEquals("b", o.peek()?.body)
    }

    @Test
    fun markAttemptIncrementsKeyedEntryOnlyAndKeepsKey() {
        val o = outbox()
        o.enqueue("ch", "a")
        o.enqueue("ch", "b")
        val updated = o.markAttempt("k2")
        assertEquals(1, updated?.attempts)
        assertEquals("k2", updated?.idempotencyKey)
        o.markAttempt("k2")
        assertEquals(0, o.peek("k1")?.attempts)
        assertEquals(2, o.peek("k2")?.attempts)
        assertEquals(2, o.size)
        assertNull(o.markAttempt("missing"))
    }

    @Test
    fun defaultCapacityMeetsContractFloor() {
        assertTrue(ChannelOutbox().capacity >= 100)
        assertEquals(ChannelOutbox.MIN_CAPACITY, ChannelOutbox().capacity)
        assertThrows(IllegalArgumentException::class.java) { ChannelOutbox(capacity = 99) }
    }

    @Test
    fun enqueueAtCapacityReturnsFullAndEvictsNothing() {
        val o = outbox()
        repeat(ChannelOutbox.MIN_CAPACITY) { assertTrue(o.enqueue("ch", "m$it") is OutboxEnqueueResult.Queued) }
        assertTrue(o.isFull)
        assertTrue(o.enqueue("ch", "overflow") is OutboxEnqueueResult.Full)
        assertEquals(ChannelOutbox.MIN_CAPACITY, o.size)
        assertEquals("m0", o.peek()?.body)
        assertTrue(o.drainOrder().none { it.body == "overflow" })
        o.ack("k1")
        assertFalse(o.isFull)
        assertTrue(o.enqueue("ch", "fits") is OutboxEnqueueResult.Queued)
    }

    @Test
    fun serializeRestoreRoundTripPreservesEverything() {
        val src = outbox()
        src.enqueue("ch_a", "hello: 5:world\nline2 ✓ 🔥")
        src.enqueue("ch_b", "", threadId = "t_1", replyTo = "m_9")
        src.enqueue("ch_c", "third", threadId = null, replyTo = "m_2")
        src.markAttempt("k1")
        src.markAttempt("k1")
        src.markAttempt("k3")

        val dst = outbox()
        assertEquals(3, dst.restore(src.serialize()))
        assertEquals(src.drainOrder(), dst.drainOrder())
        val second = dst.peek("k2")!!
        assertEquals("", second.body)
        assertEquals("t_1", second.threadId)
        assertEquals("m_9", second.replyTo)
        assertEquals(2, dst.peek("k1")?.attempts)
        assertEquals(1, dst.peek("k3")?.attempts)
        assertNull(dst.peek("k3")?.threadId)
    }

    @Test
    fun restoreSkipsKeysAlreadyPresent() {
        val o = outbox()
        o.enqueue("ch", "a")
        val snapshot = o.serialize()
        assertEquals(0, o.restore(snapshot))
        assertEquals(1, o.size)
    }

    @Test
    fun restoreOfMalformedInputDoesNotCrashAndCountsHonestly() {
        val o = outbox()
        assertEquals(0, o.restore(""))
        assertEquals(0, o.restore("garbage"))
        assertEquals(0, o.restore("OKOB1\nxx:abc"))
        assertEquals(0, o.size)

        val good = outbox()
        good.enqueue("ch", "first")
        good.enqueue("ch", "second")
        val truncated = good.serialize().dropLast(5)
        val target = outbox()
        assertEquals(1, target.restore(truncated))
        assertEquals("first", target.peek()?.body)
    }

    @Test
    fun entriesExposeQueuedNotSentLabelAndDelivery() {
        val o = outbox()
        val e = queued(o.enqueue("ch", "a"))
        assertEquals("Queued, not sent", e.label)
        assertEquals(ChannelOutbox.QUEUED_LABEL, e.label)
        assertEquals(OreMessageDelivery.Queued, e.delivery)
    }

    @Test
    fun everyMutationWritesPersistence() {
        val p = FakePersistence()
        val o = outbox(persistence = p)
        o.enqueue("ch", "a")
        assertEquals(1, p.writes)
        o.markAttempt("k1")
        assertEquals(2, p.writes)
        o.enqueue("ch", "b")
        assertEquals(3, p.writes)
        o.ack("k1")
        assertEquals(4, p.writes)
        o.ack("k1")
        assertEquals(4, p.writes)
        assertEquals(o.serialize(), p.stored)
    }

    @Test
    fun newOutboxRestoresFromPersistenceOnConstruction() {
        val p = FakePersistence()
        val first = outbox(persistence = p)
        first.enqueue("ch", "survives")
        first.markAttempt("k1")
        val reborn = outbox(persistence = p)
        assertEquals(1, reborn.size)
        assertEquals("survives", reborn.peek()?.body)
        assertEquals("k1", reborn.peek()?.idempotencyKey)
        assertEquals(1, reborn.peek()?.attempts)
    }
}
