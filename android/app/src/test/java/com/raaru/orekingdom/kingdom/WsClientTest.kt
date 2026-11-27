package com.raaru.orekingdom.kingdom

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * WsClient before any connection exists (C-25): the unconnected
 * surface — no socket, no output — must report closed, refuse
 * sends, and tolerate close. Skeleton + listener supervisor-authored;
 * the test methods are dictated units (see the T4 run artefacts).
 */
class WsClientTest {

    private object NoOpListener : WsClient.Listener {
        override fun onOpen() = Unit
        override fun onMessage(text: String) = Unit
        override fun onClosed(reason: String) = Unit
        override fun onFailure(error: Exception) = Unit
    }

    @Test
    fun freshClientIsNotOpen() {
        val client = WsClient("ws://localhost:9/never", listener = NoOpListener)
        assertFalse(client.isOpen)
    }

    @Test
    fun sendBeforeAnyConnectionReturnsFalse() {
        val client = WsClient("ws://localhost:9/never", listener = NoOpListener)
        assertFalse(client.send("hello"))
    }

    @Test
    fun closeBeforeAnyConnectionIsHarmlessAndIdempotent() {
        val client = WsClient("ws://localhost:9/never", listener = NoOpListener)
        client.close()
        client.close()
        assertFalse(client.isOpen)
    }
}
