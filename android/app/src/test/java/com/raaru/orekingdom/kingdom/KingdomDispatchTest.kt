package com.raaru.orekingdom.kingdom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Dispatch robustness pins (DeepSeek full-spend programme Q-27; the
 * guard + visibility change is seam S-SEAM-3): a malformed frame is
 * dropped — never thrown out of dispatch, nothing emitted — and the
 * next well-formed frame still routes. Each drop test proves the
 * negative with a well-formed barrier frame collected afterwards.
 * The methods below are supervisor-authored from the programme
 * dictation: the drafting call returned empty and the tranche's
 * stop rule closed firing (see the tranche-2 scorecard).
 */
class KingdomDispatchTest {

    private fun transport() = KingdomTransport("http://localhost")

    @Test
    fun ackMissingSeqIsDropped() = runBlocking {
        val t = transport()
        val seen = async(Dispatchers.Unconfined) { t.frames.take(1).toList() }
        t.dispatch("""{"type":"ack","payload":{"for_id":"c_1","message_id":"m_1"}}""")
        t.dispatch("""{"type":"ack","payload":{"for_id":"c_1","message_id":"m_1","seq":7}}""")
        assertEquals(listOf(KingdomInbound.Ack("c_1", "m_1", 7L)), withTimeout(5_000) { seen.await() })
    }

    @Test
    fun messageDeleteMissingMessageIdIsDropped() = runBlocking {
        val t = transport()
        val seen = async(Dispatchers.Unconfined) { t.frames.take(1).toList() }
        t.dispatch("""{"type":"message.delete","payload":{"channel_id":"ch_general","seq":9}}""")
        t.dispatch("""{"type":"message.delete","payload":{"channel_id":"ch_general","message_id":"m_1","seq":9}}""")
        assertEquals(listOf(KingdomInbound.MessageDelete("ch_general", "m_1", 9L)), withTimeout(5_000) { seen.await() })
    }

    @Test
    fun resyncMissingChannelIdIsDropped() = runBlocking {
        val t = transport()
        val seen = async(Dispatchers.Unconfined) { t.frames.take(1).toList() }
        t.dispatch("""{"type":"resync","payload":{"reason":"gap"}}""")
        t.dispatch("""{"type":"resync","payload":{"channel_id":"ch_general","reason":"gap"}}""")
        assertEquals(listOf(KingdomInbound.Resync("ch_general", "gap")), withTimeout(5_000) { seen.await() })
    }

    @Test
    fun wellFormedFramesRouteInOrder() = runBlocking {
        val t = transport()
        val seen = async(Dispatchers.Unconfined) { t.frames.take(3).toList() }
        t.dispatch("""{"type":"ack","payload":{"for_id":"c_1","message_id":"m_1","seq":7}}""")
        t.dispatch("""{"type":"message.delete","payload":{"channel_id":"ch_general","message_id":"m_1","seq":9}}""")
        t.dispatch("""{"type":"resync","payload":{"channel_id":"ch_general","reason":"gap"}}""")
        assertEquals(
            listOf(
                KingdomInbound.Ack("c_1", "m_1", 7L),
                KingdomInbound.MessageDelete("ch_general", "m_1", 9L),
                KingdomInbound.Resync("ch_general", "gap"),
            ),
            withTimeout(5_000) { seen.await() },
        )
    }

    @Test
    fun nonJsonAndUnknownTypeFramesAreIgnored() = runBlocking {
        val t = transport()
        val seen = async(Dispatchers.Unconfined) { t.frames.take(1).toList() }
        t.dispatch("not json at all")
        t.dispatch("""{"type":"mystery","payload":{}}""")
        t.dispatch("""{"type":"ack","payload":{"for_id":"c_1","message_id":"m_1","seq":7}}""")
        assertEquals(listOf(KingdomInbound.Ack("c_1", "m_1", 7L)), withTimeout(5_000) { seen.await() })
    }
}
