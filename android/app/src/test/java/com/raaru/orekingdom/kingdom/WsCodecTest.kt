package com.raaru.orekingdom.kingdom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun pongSurfacesWithPayload() {
        val wire = WsCodec.encodeFrame(WsCodec.OP_PONG, "hb".toByteArray(), null)
        val frames = WsFrameAssembler().feed(wire)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WsFrame.Pong)
        val pong = frames[0] as WsFrame.Pong
        assertTrue(pong.payload.contentEquals("hb".toByteArray()))
    }

    @Test
    fun closeWithEmptyPayloadDecodes1005() {
        val wire = WsCodec.encodeFrame(WsCodec.OP_CLOSE, ByteArray(0), null)
        val frames = WsFrameAssembler().feed(wire)
        assertEquals(listOf(WsFrame.Close(1005, "")), frames)
    }

    @Test
    fun closeWithOneBytePayloadDecodes1005() {
        val wire = WsCodec.encodeFrame(WsCodec.OP_CLOSE, byteArrayOf(0x03), null)
        val frames = WsFrameAssembler().feed(wire)
        assertEquals(listOf(WsFrame.Close(1005, "")), frames)
    }

    @Test
    fun closeWithCodeOnlyDecodesEmptyReason() {
        val wire = WsCodec.encodeClose(1000, "", null)
        val frames = WsFrameAssembler().feed(wire)
        assertEquals(listOf(WsFrame.Close(1000, "")), frames)
    }

    @Test
    fun orphanContinuationIsDropped() {
        val assembler = WsFrameAssembler()
        val orphan = WsCodec.encodeFrame(WsCodec.OP_CONTINUATION, "lost".toByteArray(), null)
        assertTrue(assembler.feed(orphan).isEmpty())
        assertEquals(listOf(WsFrame.Text("after")), assembler.feed(serverText("after")))
    }

    @Test
    fun pingInterleavedInFragmentedMessageIsDeliveredImmediately() {
        val assembler = WsFrameAssembler()
        val part1 = WsCodec.encodeFrame(WsCodec.OP_TEXT, "Hello, ".toByteArray(), null)
        val frag1 = part1.copyOf().also { it[0] = (it[0].toInt() and 0x7F).toByte() }
        assertTrue(assembler.feed(frag1).isEmpty())
        val ping = WsCodec.encodeFrame(WsCodec.OP_PING, "hb".toByteArray(), null)
        val pingFrames = assembler.feed(ping)
        assertEquals(1, pingFrames.size)
        assertTrue(pingFrames[0] is WsFrame.Ping)
        val frag2 = WsCodec.encodeFrame(WsCodec.OP_CONTINUATION, "world".toByteArray(), null)
        assertEquals(listOf(WsFrame.Text("Hello, world")), assembler.feed(frag2))
    }

    @Test
    fun fragmentedBinaryDeliveredWholeOnce() {
        val assembler = WsFrameAssembler()
        val frag1 = WsCodec.encodeFrame(WsCodec.OP_BINARY, "ab".toByteArray(), null)
            .copyOf().also { it[0] = (it[0].toInt() and 0x7F).toByte() }
        assertTrue(assembler.feed(frag1).isEmpty())
        val frag2 = WsCodec.encodeFrame(WsCodec.OP_CONTINUATION, "cd".toByteArray(), null)
        val frames = assembler.feed(frag2)
        assertEquals(1, frames.size)
        assertTrue(frames[0] is WsFrame.Binary)
        assertTrue((frames[0] as WsFrame.Binary).bytes.contentEquals("abcd".toByteArray()))
    }

    @Test
    fun threeFragmentMessageAssemblesInOrder() {
        val assembler = WsFrameAssembler()
        val frag1 = WsCodec.encodeFrame(WsCodec.OP_TEXT, "one ".toByteArray(), null)
            .copyOf().also { it[0] = (it[0].toInt() and 0x7F).toByte() }
        assertTrue(assembler.feed(frag1).isEmpty())
        val frag2 = WsCodec.encodeFrame(WsCodec.OP_CONTINUATION, "two ".toByteArray(), null)
            .copyOf().also { it[0] = (it[0].toInt() and 0x7F).toByte() }
        assertTrue(assembler.feed(frag2).isEmpty())
        val frag3 = WsCodec.encodeFrame(WsCodec.OP_CONTINUATION, "three".toByteArray(), null)
        assertEquals(listOf(WsFrame.Text("one two three")), assembler.feed(frag3))
    }

    @Test
    fun frameAfterFragmentedMessageParsesCleanly() {
        val assembler = WsFrameAssembler()
        val frag1 = WsCodec.encodeFrame(WsCodec.OP_TEXT, "split ".toByteArray(), null)
            .copyOf().also { it[0] = (it[0].toInt() and 0x7F).toByte() }
        assertTrue(assembler.feed(frag1).isEmpty())
        val frag2 = WsCodec.encodeFrame(WsCodec.OP_CONTINUATION, "message".toByteArray(), null)
        assertEquals(listOf(WsFrame.Text("split message")), assembler.feed(frag2))
        assertEquals(listOf(WsFrame.Text("next")), assembler.feed(serverText("next")))
    }

    @Test
    fun lengthFormBoundary125vs126() {
        val shortText = "x".repeat(125)
        val shortWire = serverText(shortText)
        assertEquals(125, shortWire[1].toInt() and 0x7F)
        assertEquals(listOf(WsFrame.Text(shortText)), WsFrameAssembler().feed(shortWire))

        val longText = "x".repeat(126)
        val longWire = serverText(longText)
        assertEquals(126, longWire[1].toInt() and 0x7F)
        assertEquals(126, ((longWire[2].toInt() and 0xFF) shl 8) or (longWire[3].toInt() and 0xFF))
        assertEquals(listOf(WsFrame.Text(longText)), WsFrameAssembler().feed(longWire))
    }

    @Test
    fun maskKeyOfWrongSizeThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            WsCodec.encodeFrame(WsCodec.OP_TEXT, "x".toByteArray(), byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun truncatedFrameIsBufferedNotAnError() {
        val wire = serverText("hello")
        val assembler = WsFrameAssembler()
        assertTrue(assembler.feed(wire.copyOfRange(0, 2)).isEmpty())
        assertEquals(listOf(WsFrame.Text("hello")), assembler.feed(wire.copyOfRange(2, wire.size)))
    }

    @Test
    fun maskedFragmentedMessageAssembles() {
        val mask = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val assembler = WsFrameAssembler()
        val part1 = WsCodec.encodeFrame(WsCodec.OP_TEXT, "mask ".toByteArray(), mask)
        val frag1 = part1.copyOf().also { it[0] = (it[0].toInt() and 0x7F).toByte() }
        assertTrue(assembler.feed(frag1).isEmpty())
        val frag2 = WsCodec.encodeFrame(WsCodec.OP_CONTINUATION, "ed".toByteArray(), mask)
        assertEquals(listOf(WsFrame.Text("mask ed")), assembler.feed(frag2))
    }
}
