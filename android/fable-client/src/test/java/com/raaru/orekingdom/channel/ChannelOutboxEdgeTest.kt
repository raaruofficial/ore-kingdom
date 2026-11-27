package com.raaru.orekingdom.channel

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Outbox restore/persistence edges beyond ChannelOutboxTest (DeepSeek
 * programme item 2): over-capacity restores, duplicate keys inside one
 * payload, and the file-backed persistence round trip. Supervisor-
 * authored after three empty DeepSeek drafts (see programme record).
 */
class ChannelOutboxEdgeTest {

    private fun outbox(prefix: String): ChannelOutbox {
        val keys = generateSequence(1) { it + 1 }.map { "$prefix$it" }.iterator()
        return ChannelOutbox(keyFactory = { keys.next() }, now = { "2026-11-26T17:00:00Z" })
    }

    @Test
    fun restoreBeyondCapacityIsKeptAndBlocksNewEnqueues() {
        val a = outbox("a")
        repeat(ChannelOutbox.MIN_CAPACITY) { a.enqueue("ch", "a$it") }
        val b = outbox("b")
        repeat(ChannelOutbox.MIN_CAPACITY) { b.enqueue("ch", "b$it") }

        val target = outbox("t")
        assertEquals(ChannelOutbox.MIN_CAPACITY, target.restore(a.serialize()))
        // Restored entries are never dropped for capacity: the second
        // snapshot lands whole even though the queue is already full.
        assertEquals(ChannelOutbox.MIN_CAPACITY, target.restore(b.serialize()))
        assertEquals(2 * ChannelOutbox.MIN_CAPACITY, target.size)
        assertTrue(target.enqueue("ch", "overflow") is OutboxEnqueueResult.Full)
    }

    @Test
    fun restoreSkipsDuplicateKeysInsideOnePayload() {
        // Wire format (ChannelOutbox companion): header, then per entry
        // seven length-prefixed fields; "-1:" is null.
        fun entry(key: String, body: String) =
            "${key.length}:$key" + "2:ch" + "${body.length}:$body" +
                "20:2026-11-26T17:00:00Z" + "-1:" + "-1:" + "1:0"
        val payload = "OKOB1\n" + entry("dup", "one") + entry("dup", "two")
        val target = outbox("t")
        assertEquals(1, target.restore(payload))
        assertEquals(1, target.size)
        assertEquals("one", target.peek()?.body)
    }

    @Test
    fun serializeOfEmptyQueueRestoresZero() {
        val target = outbox("t")
        assertEquals(0, target.restore(outbox("e").serialize()))
        assertEquals(0, target.size)
    }

    @Test
    fun filePersistenceRoundTripsThroughDisk() {
        val dir = Files.createTempDirectory("outbox-edge").toFile()
        try {
            val file = File(dir, "queue.txt")
            val persistence = FileOutboxPersistence(file)
            val first = ChannelOutbox(
                persistence = persistence,
                keyFactory = { "kf1" },
                now = { "2026-11-26T17:00:00Z" },
            )
            first.enqueue("ch", "on disk")
            assertTrue(file.exists())
            assertEquals(first.serialize(), persistence.read())

            val reborn = ChannelOutbox(persistence = FileOutboxPersistence(file))
            assertEquals(1, reborn.size)
            assertEquals("on disk", reborn.peek()?.body)
            assertEquals("kf1", reborn.peek()?.idempotencyKey)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun delimiterRichBodyRoundTripsByteIdentical() {
        val source = outbox("a")
        val body = "line one\nline two: with colon 🎉"
        source.enqueue("ch", body)
        val restored = outbox("t")
        assertEquals(1, restored.restore(source.serialize()))
        assertEquals(body, restored.peek()?.body)
        assertEquals(source.serialize(), restored.serialize())
    }

    @Test
    fun threadAndReplySurviveRoundTripIndependently() {
        val source = outbox("a")
        source.enqueue("ch", "threaded", threadId = "th_1")
        source.enqueue("ch", "reply", replyTo = "m_9")
        val restored = outbox("t")
        assertEquals(2, restored.restore(source.serialize()))
        val entries = restored.drainOrder()
        assertEquals("th_1", entries[0].threadId)
        assertEquals(null, entries[0].replyTo)
        assertEquals(null, entries[1].threadId)
        assertEquals("m_9", entries[1].replyTo)
    }

    @Test
    fun truncatedPayloadRestoresOnlyTheCompletePrefix() {
        val source = outbox("a")
        source.enqueue("ch", "first")
        source.enqueue("ch", "second")
        val cut = source.serialize().dropLast(3)
        val restored = outbox("t")
        assertEquals(1, restored.restore(cut))
        assertEquals("first", restored.peek()?.body)
    }
}
