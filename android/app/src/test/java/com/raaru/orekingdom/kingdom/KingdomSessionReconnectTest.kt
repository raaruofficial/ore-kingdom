package com.raaru.orekingdom.kingdom

import com.raaru.orekingdom.channel.OutboxEnqueueResult
import com.raaru.orekingdom.designsystem.components.OreMessageDelivery
import com.raaru.orekingdom.shell.InMemoryLastVisitedStore
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
 * Reconnect-edge pins for KingdomSession (DeepSeek full-spend
 * programme Q-24): what Disconnected / Connected frames do to the
 * send pipeline and the subscription plan. Harness in
 * KingdomSessionTestSupport.kt (S-SEAM-2).
 */
class KingdomSessionReconnectTest {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private lateinit var transport: FakeSessionTransport
    private lateinit var session: KingdomSession

    @After
    fun tearDown() {
        job.cancel()
    }

    private fun startWith(history: List<ContractMessage>) {
        transport = FakeSessionTransport(
            me = "u_self" to sessionMember("u_self", "Self"),
            members = listOf(sessionMember("u_self", "Self"), sessionMember("u_other", "Other")),
            channels = listOf(sessionChannel("ch_general")),
            history = history,
        )
        session = KingdomSession(transport, scope, InMemoryLastVisitedStore(), InMemoryOutboxPersistence())
        session.start()
        session.openChannel("ch_general")
    }

    private fun emit(frame: KingdomInbound) = runBlocking { transport.framesFlow.emit(frame) }

    @Test
    fun disconnectedKeepsQueuedSendsQueued() {
        startWith(history = listOf(sessionMessage("m1", "ch_general", 7, "u_other")))
        emit(KingdomInbound.Connected)
        assertTrue(session.realtime.state.value.connected)
        val result = session.sendController.submit("ch_general", "held body", online = false)
        assertTrue(result is OutboxEnqueueResult.Queued)
        val key = session.outbox.forChannel("ch_general").single().idempotencyKey
        assertEquals(OreMessageDelivery.Queued, session.sendController.deliveryOf(key))
        emit(KingdomInbound.Disconnected)
        assertFalse(session.realtime.state.value.connected)
        assertEquals(OreMessageDelivery.Queued, session.sendController.deliveryOf(key))
        assertEquals(1, session.outbox.forChannel("ch_general").size)
    }

    @Test
    fun connectedReissuesSubscribeAndResumeForOpenChannel() {
        startWith(history = listOf(sessionMessage("m1", "ch_general", 7, "u_other")))
        transport.sentFrames.clear()
        emit(KingdomInbound.Connected)
        assertTrue(session.realtime.state.value.connected)
        assertTrue(transport.sentFrames.any { it.contains("\"subscribe\"") && it.contains("ch_general") })
        assertTrue(transport.sentFrames.any { it.contains("\"resume\"") && it.contains("ch_general") && it.contains("7") })
    }
}
