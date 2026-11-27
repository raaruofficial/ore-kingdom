package com.raaru.orekingdom.kingdom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RFC 6455 framing rules, proven on the JVM (host integration stage 1). */
class WsCodecTest {

    private fun serverText(text: String): ByteArray =
        WsCodec.encodeText(text, maskKey = null)

    @Test
    fun shortTextRoundTrips() {
        val frames = WsFrameAssembler().feed(serverText("hello"))
        assertEquals(listOf(WsFrame.Text("hello")), frames)
    }

    @Test
    fun mediumTextUses16BitLength() {
        val text = "x".repeat(300)
        val frames = WsFrameAssembler().feed(serverText(text))
        assertEquals(listOf(WsFrame.Text(text)), frames)
    }

    @Test
    fun longTextUses64BitLength() {
        val text = "y".repeat(70_000)
        val frames = WsFrameAssembler().feed(serverText(text))
        assertEquals(1, frames.size)
        assertEquals(text, (frames[0] as WsFrame.Text).text)
    }

    @Test
    fun maskedClientFrameDecodes() {
        val mask = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val wire = WsCodec.encodeText("masked payload", mask)
        val frames = WsFrameAssembler().feed(wire)
        assertEquals(listOf(WsFrame.Text("masked payload")), frames)
    }

    @Test
    fun byteByByteFeedingAssemblesOneFrame() {
        val wire = serverText("drip fed")
        val assembler = WsFrameAssembler()
        val frames = ArrayList<WsFrame>()
        for (b in wire) frames += assembler.feed(byteArrayOf(b))
        assertEquals(listOf(WsFrame.Text("drip fed")), frames)
    }

    @Test
    fun fragmentedMessageDeliveredWholeOnce() {
        val assembler = WsFrameAssembler()
        val part1 = WsCodec.encodeFrame(WsCodec.OP_TEXT, "Hello, ".toByteArray(), null)
        // Rebuild part1 as a non-FIN frame: encodeFrame always sets FIN, so hand-roll.
        val frag1 = part1.copyOf().also { it[0] = (it[0].toInt() and 0x7F).toByte() }
        val frag2 = WsCodec.encodeFrame(WsCodec.OP_CONTINUATION, "world".toByteArray(), null)
        assertTrue(assembler.feed(frag1).isEmpty())
        val frames = assembler.feed(frag2)
        assertEquals(listOf(WsFrame.Text("Hello, world")), frames)
    }

    @Test
    fun pingAndCloseSurfaceAsControlFrames() {
        val assembler = WsFrameAssembler()
        val ping = WsCodec.encodeFrame(WsCodec.OP_PING, "hb".toByteArray(), null)
        val frames = assembler.feed(ping)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WsFrame.Ping)

        val close = WsCodec.encodeClose(1001, "going away", null)
        val closeFrames = assembler.feed(close)
        assertEquals(listOf(WsFrame.Close(1001, "going away")), closeFrames)
    }

    @Test
    fun twoFramesInOneChunkBothDelivered() {
        val wire = serverText("one") + serverText("two")
        val frames = WsFrameAssembler().feed(wire)
        assertEquals(listOf(WsFrame.Text("one"), WsFrame.Text("two")), frames)
    }
}
