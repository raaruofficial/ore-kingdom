package com.raaru.orekingdom.channel

import com.raaru.orekingdom.designsystem.components.OreMessageDelivery
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One composed-but-unacked message. [idempotencyKey] is generated exactly once, at compose time,
 * and is the frame `id` on every send attempt for this message. It is never regenerated.
 */
data class OutboxEntry(
    val idempotencyKey: String,
    val channelId: String,
    val body: String,
    val queuedAt: String,
    val threadId: String? = null,
    val replyTo: String? = null,
    val attempts: Int = 0,
) {
    /** Label data for the UI: an outbox entry is never rendered as delivered before its ack. */
    val label: String get() = ChannelOutbox.QUEUED_LABEL
    val delivery: OreMessageDelivery get() = OreMessageDelivery.Queued
}

sealed interface OutboxEnqueueResult {
    data class Queued(val entry: OutboxEntry) : OutboxEnqueueResult
    /** The outbox is at capacity. Nothing was dropped or evicted; the caller keeps the draft. */
    object Full : OutboxEnqueueResult
}

/** Durable storage hook. [write] is called synchronously on every mutation. */
interface OutboxPersistence {
    fun write(serialized: String)
    fun read(): String?
}

/** File-backed persistence: write-to-temp then rename, so a kill mid-write keeps the prior queue. */
class FileOutboxPersistence(private val file: File) : OutboxPersistence {
    override fun write(serialized: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(serialized, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(serialized, Charsets.UTF_8)
            tmp.delete()
        }
    }

    override fun read(): String? = if (file.exists()) file.readText(Charsets.UTF_8) else null
}

/**
 * Offline outbox. Local client state only: no networking, no frames.
 * FIFO in compose order; entries leave only on [ack] or explicit [remove]. Nothing is ever evicted.
 */
class ChannelOutbox(
    val capacity: Int = MIN_CAPACITY,
    private val persistence: OutboxPersistence? = null,
    private val keyFactory: () -> String = { "c_" + UUID.randomUUID().toString() },
    private val now: () -> String = ::utcNow,
) {
    init {
        require(capacity >= MIN_CAPACITY) { "Outbox capacity must be at least $MIN_CAPACITY" }
    }

    private val lock = Any()
    private val queue = ArrayList<OutboxEntry>()
    private val _entries = MutableStateFlow<List<OutboxEntry>>(emptyList())

    /** Observable FIFO snapshot for rendering queued rows. */
    val entries: StateFlow<List<OutboxEntry>> = _entries.asStateFlow()

    val size: Int get() = synchronized(lock) { queue.size }
    val isEmpty: Boolean get() = size == 0
    val isFull: Boolean get() = synchronized(lock) { queue.size >= capacity }

    init {
        persistence?.let { store -> runCatching { store.read() }.getOrNull()?.let { restore(it) } }
    }

    /** Queues one composed message and generates its single idempotency key. */
    fun enqueue(
        channelId: String,
        body: String,
        threadId: String? = null,
        replyTo: String? = null,
    ): OutboxEnqueueResult = synchronized(lock) {
        if (queue.size >= capacity) return OutboxEnqueueResult.Full
        var key = keyFactory()
        while (queue.any { it.idempotencyKey == key }) key = keyFactory()
        val entry = OutboxEntry(key, channelId, body, now(), threadId, replyTo)
        queue.add(entry)
        commit()
        OutboxEnqueueResult.Queued(entry)
    }

    /** Head of the queue (next to drain), or null when empty. */
    fun peek(): OutboxEntry? = synchronized(lock) { queue.firstOrNull() }

    fun peek(idempotencyKey: String): OutboxEntry? =
        synchronized(lock) { queue.firstOrNull { it.idempotencyKey == idempotencyKey } }

    /** FIFO drain order snapshot. The sender walks this in order, reusing each entry's key. */
    fun drainOrder(): List<OutboxEntry> = synchronized(lock) { queue.toList() }

    fun forChannel(channelId: String): List<OutboxEntry> =
        synchronized(lock) { queue.filter { it.channelId == channelId } }

    /**
     * Records a send attempt and returns the entry to send. The key is unchanged:
     * a retry always carries the SAME idempotency key. The entry stays queued until [ack].
     */
    fun markAttempt(idempotencyKey: String): OutboxEntry? = synchronized(lock) {
        val index = queue.indexOfFirst { it.idempotencyKey == idempotencyKey }
        if (index < 0) return null
        val updated = queue[index].copy(attempts = queue[index].attempts + 1)
        queue[index] = updated
        commit()
        updated
    }

    /** The server acked this key (`ack.for_id`). Removes the entry; returns false if unknown. */
    fun ack(idempotencyKey: String): Boolean = remove(idempotencyKey)

    /** Removes an entry by key (ack, user discard, or a server refusal handled by the caller). */
    fun remove(idempotencyKey: String): Boolean = synchronized(lock) {
        val removed = queue.removeAll { it.idempotencyKey == idempotencyKey }
        if (removed) commit()
        removed
    }

    /** Serializes the whole queue, in order, to a self-contained string. */
    fun serialize(): String = synchronized(lock) { encode(queue) }

    /**
     * Restores entries from [serialized], preserving order and original idempotency keys.
     * Entries already present (same key) are skipped. Restored entries are never dropped for
     * capacity: capacity limits new enqueues only. Returns the number of entries restored.
     */
    fun restore(serialized: String): Int = synchronized(lock) {
        val known = queue.mapTo(HashSet()) { it.idempotencyKey }
        val incoming = decode(serialized).filter { known.add(it.idempotencyKey) }
        if (incoming.isEmpty()) return 0
        queue.addAll(incoming)
        commit()
        incoming.size
    }

    private fun commit() {
        _entries.value = queue.toList()
        persistence?.let { store -> runCatching { store.write(encode(queue)) } }
    }

    companion object {
        const val MIN_CAPACITY = 100
        const val QUEUED_LABEL = "Queued, not sent"
        private const val HEADER = "OKOB1"
        private const val NULL_FIELD = "-1:"

        private fun utcNow(): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date())

        // Format: header, newline, then per entry seven length-prefixed fields ("<len>:<chars>").
        private fun encode(entries: List<OutboxEntry>): String = buildString {
            append(HEADER).append('\n')
            entries.forEach { e ->
                field(e.idempotencyKey); field(e.channelId); field(e.body); field(e.queuedAt)
                field(e.threadId); field(e.replyTo); field(e.attempts.toString())
            }
        }

        private fun StringBuilder.field(value: String?) {
            if (value == null) append(NULL_FIELD) else append(value.length).append(':').append(value)
        }

        /** Decodes every complete entry; a truncated or corrupt tail is ignored, never fatal. */
        private fun decode(serialized: String): List<OutboxEntry> {
            val prefix = HEADER + "\n"
            if (!serialized.startsWith(prefix)) return emptyList()
            val out = ArrayList<OutboxEntry>()
            var pos = prefix.length
            fun next(): String? {
                val colon = serialized.indexOf(':', pos)
                if (colon < 0) throw IllegalStateException()
                val len = serialized.substring(pos, colon).toInt()
                pos = colon + 1
                if (len < 0) return null
                if (pos + len > serialized.length) throw IllegalStateException()
                return serialized.substring(pos, pos + len).also { pos += len }
            }
            try {
                while (pos < serialized.length) {
                    val key = next() ?: break
                    val channelId = next() ?: break
                    val body = next() ?: break
                    val queuedAt = next() ?: break
                    val threadId = next()
                    val replyTo = next()
                    val attempts = next()?.toIntOrNull() ?: 0
                    out.add(OutboxEntry(key, channelId, body, queuedAt, threadId, replyTo, attempts))
                }
            } catch (_: RuntimeException) {
                // Keep the entries decoded so far.
            }
            return out
        }
    }
}
