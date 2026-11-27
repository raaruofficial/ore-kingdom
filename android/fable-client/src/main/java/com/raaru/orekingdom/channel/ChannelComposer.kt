package com.raaru.orekingdom.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.Composer
import com.raaru.orekingdom.designsystem.components.ComposerState
import com.raaru.orekingdom.designsystem.components.OreMessageDelivery
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal object ChannelComposerCopy {
    const val OUTBOX_FULL = "Your outbox is full. Your draft is kept here until queued messages send."
    const val ACK_RETRY_MS = 5_000L
    const val TYPING_IDLE_MS = 5_000L
}

/**
 * Send pipeline over [ChannelOutbox]. Every composed message enters the outbox first and leaves
 * only on ack (or an explicit refusal). [transmit] writes the contract `send` frame with
 * `id = entry.idempotencyKey`; every retry passes the SAME entry key. No networking lives here.
 * The host feeds server results in via [handleAck] / [handleRefused] / [onDisconnected].
 */
class ChannelSendController(
    private val outbox: ChannelOutbox,
    private val transmit: (OutboxEntry) -> Unit,
    private val onSendAck: (idempotencyKey: String) -> Unit,
) {
    private val _inFlight = MutableStateFlow<Set<String>>(emptySet())

    /** Keys transmitted and awaiting ack: these render as sending; all other entries as queued. */
    val inFlight: StateFlow<Set<String>> = _inFlight.asStateFlow()

    /** Sending while awaiting ack; otherwise queued, not sent. Never delivered before the ack. */
    fun deliveryOf(idempotencyKey: String): OreMessageDelivery =
        if (idempotencyKey in _inFlight.value) OreMessageDelivery.Sending else OreMessageDelivery.Queued

    /** Enqueues one composed message (one key). Online: drains in FIFO order, older entries first. */
    fun submit(
        channelId: String,
        body: String,
        online: Boolean,
        threadId: String? = null,
        replyTo: String? = null,
    ): OutboxEnqueueResult {
        val result = outbox.enqueue(channelId, body, threadId, replyTo)
        if (result is OutboxEnqueueResult.Queued && online) drain()
        return result
    }

    /** Transmits every not-yet-in-flight entry in FIFO order with its original key. */
    fun drain() {
        outbox.drainOrder().forEach { entry ->
            if (entry.idempotencyKey !in _inFlight.value) send(entry.idempotencyKey)
        }
    }

    /** Re-transmits unacked in-flight entries, in order, with the SAME keys. */
    fun retryUnacked() {
        outbox.drainOrder().forEach { entry ->
            if (entry.idempotencyKey in _inFlight.value) {
                outbox.markAttempt(entry.idempotencyKey)?.let(transmit)
            }
        }
    }

    /** Connection lost: nothing is in flight any more; all entries render as queued, not sent. */
    fun onDisconnected() {
        _inFlight.value = emptySet()
    }

    /** `ack.for_id` arrived. Idempotent: a duplicate ack for the same key is ignored. */
    fun handleAck(forId: String) {
        _inFlight.update { it - forId }
        if (outbox.ack(forId)) onSendAck(forId)
    }

    /**
     * The server refused this send (`error.for_id`). Removes the entry and returns it so the host
     * can render the outcome (e.g. the read-only downgrade shape on `not_entitled`).
     */
    fun handleRefused(forId: String): OutboxEntry? {
        _inFlight.update { it - forId }
        return outbox.peek(forId)?.also { outbox.remove(forId) }
    }

    private fun send(idempotencyKey: String) {
        val entry = outbox.markAttempt(idempotencyKey) ?: return
        _inFlight.update { it + idempotencyKey }
        transmit(entry)
    }
}

@Composable
fun rememberChannelSendController(
    outbox: ChannelOutbox,
    onTransmit: (OutboxEntry) -> Unit,
    onSendAck: (idempotencyKey: String) -> Unit,
): ChannelSendController {
    val transmit by rememberUpdatedState(onTransmit)
    val acked by rememberUpdatedState(onSendAck)
    return remember(outbox) { ChannelSendController(outbox, { transmit(it) }, { acked(it) }) }
}

/**
 * Composer host. Enabled callers send through the outbox; slowmode shows the F1 countdown;
 * read-only callers get the F1 Upsell / Disabled shape (F-IA-1) — never a blank, never an error
 * string. Offline sends queue and are labelled "queued, not sent".
 */
@Composable
fun ChannelComposer(
    channelId: String,
    composerState: ComposerState,
    outbox: ChannelOutbox,
    onSendAck: (idempotencyKey: String) -> Unit,
    modifier: Modifier = Modifier,
    online: Boolean = true,
    threadId: String? = null,
    onTransmit: (OutboxEntry) -> Unit = {},
    onTyping: (typing: Boolean) -> Unit = {},
    onUpsellClick: () -> Unit = {},
    onSlowmodeElapsed: () -> Unit = {},
    controller: ChannelSendController = rememberChannelSendController(outbox, onTransmit, onSendAck),
) {
    val space = OreTheme.space
    val draftKey = channelId + "/" + (threadId ?: "")
    var draft by rememberSaveable(draftKey) { mutableStateOf("") }
    var outboxFull by remember(draftKey) { mutableStateOf(false) }
    var typing by remember(draftKey) { mutableStateOf(false) }
    val emitTyping by rememberUpdatedState(onTyping)
    val entries by outbox.entries.collectAsState()
    val inFlight by controller.inFlight.collectAsState()
    val canSend = composerState is ComposerState.Enabled
    val queuedHere = entries.count {
        it.channelId == channelId && it.threadId == threadId && it.idempotencyKey !in inFlight
    }

    LaunchedEffect(online, controller) {
        if (online) controller.drain() else controller.onDisconnected()
    }
    LaunchedEffect(online, inFlight.isNotEmpty(), controller) {
        while (online && inFlight.isNotEmpty()) {
            delay(ChannelComposerCopy.ACK_RETRY_MS)
            controller.retryUnacked()
        }
    }
    LaunchedEffect(draft, canSend, online) {
        if (canSend && online && draft.isNotBlank()) {
            if (!typing) { typing = true; emitTyping(true) }
            delay(ChannelComposerCopy.TYPING_IDLE_MS)
            typing = false
            emitTyping(false)
        } else if (typing) {
            typing = false
            emitTyping(false)
        }
    }
    DisposableEffect(draftKey) {
        onDispose { if (typing) emitTyping(false) }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(space.xxs)) {
        if (queuedHere > 0) {
            OreText(
                text = "$queuedHere · ${ChannelOutbox.QUEUED_LABEL}",
                style = OreTheme.type.caption,
                color = OreTheme.color.message.queued.content,
                modifier = Modifier.padding(horizontal = space.md),
                maxLines = 1,
            )
        }
        if (outboxFull) {
            OreText(
                text = ChannelComposerCopy.OUTBOX_FULL,
                style = OreTheme.type.caption,
                color = OreTheme.color.status.warning.content,
                modifier = Modifier.padding(horizontal = space.md),
            )
        }
        Composer(
            value = draft,
            onValueChange = { draft = it },
            onSend = {
                val body = draft.trim()
                if (canSend && body.isNotEmpty()) {
                    when (controller.submit(channelId, body, online, threadId)) {
                        is OutboxEnqueueResult.Queued -> { draft = ""; outboxFull = false }
                        OutboxEnqueueResult.Full -> outboxFull = true
                    }
                }
            },
            state = composerState,
            modifier = Modifier.fillMaxWidth(),
            onUpsellClick = onUpsellClick,
            onSlowmodeElapsed = onSlowmodeElapsed,
        )
    }
}
