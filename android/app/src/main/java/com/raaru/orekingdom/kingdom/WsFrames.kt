package com.raaru.orekingdom.kingdom

import java.io.ByteArrayOutputStream

/** One complete websocket message or control frame, after fragment assembly. */
sealed interface WsFrame {
    data class Text(val text: String) : WsFrame
    data class Binary(val bytes: ByteArray) : WsFrame
    data class Ping(val payload: ByteArray) : WsFrame
    data class Pong(val payload: ByteArray) : WsFrame
    data class Close(val code: Int, val reason: String) : WsFrame
}

/**
 * Minimal RFC 6455 framing (INTEGRATION_MAP §3.1/§3.3: the host owns the
 * single WSS connection; :app carries no websocket library, so the codec
 * lives here). Pure byte work — no sockets, no Android — so the framing
 * rules are JVM-testable. Client frames are masked, per the RFC; server
 * frames are not.
 */
object WsCodec {
    const val OP_CONTINUATION = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    /** Encodes one FIN frame. [maskKey] non-null masks (client → server). */
    fun encodeFrame(opcode: Int, payload: ByteArray, maskKey: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x80 or opcode)
        val maskBit = if (maskKey != null) 0x80 else 0x00
        when {
            payload.size < 126 -> out.write(maskBit or payload.size)
            payload.size <= 0xFFFF -> {
                out.write(maskBit or 126)
                out.write((payload.size ushr 8) and 0xFF)
                out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(maskBit or 127)
                val size = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) out.write(((size ushr shift) and 0xFF).toInt())
            }
        }
        if (maskKey != null) {
            require(maskKey.size == 4) { "mask key must be 4 bytes" }
            out.write(maskKey)
            payload.forEachIndexed { i, b -> out.write(b.toInt() xor maskKey[i % 4].toInt()) }
        } else {
            out.write(payload)
        }
        return out.toByteArray()
    }

    fun encodeText(text: String, maskKey: ByteArray?): ByteArray =
        encodeFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8), maskKey)

    fun encodeClose(code: Int = 1000, reason: String = "", maskKey: ByteArray?): ByteArray {
        val reasonBytes = reason.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(2 + reasonBytes.size)
        payload[0] = ((code ushr 8) and 0xFF).toByte()
        payload[1] = (code and 0xFF).toByte()
        reasonBytes.copyInto(payload, 2)
        return encodeFrame(OP_CLOSE, payload, maskKey)
    }
}

/**
 * Incremental frame parser + fragment assembler. Feed raw socket bytes in
 * any chunking; complete messages come out. Control frames are delivered
 * immediately, never assembled. A fragmented message is delivered once,
 * whole, when its final fragment lands.
 */
class WsFrameAssembler {
    private var buffer = ByteArray(0)
    private var fragmentOpcode = -1
    private var fragments: ByteArrayOutputStream? = null

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<WsFrame> {
        val merged = ByteArray(buffer.size + length)
        buffer.copyInto(merged)
        bytes.copyInto(merged, buffer.size, offset, offset + length)
        buffer = merged
        val out = ArrayList<WsFrame>()
        while (true) {
            val frame = tryParseFrame() ?: break
            when (frame.opcode) {
                WsCodec.OP_PING -> out += WsFrame.Ping(frame.payload)
                WsCodec.OP_PONG -> out += WsFrame.Pong(frame.payload)
                WsCodec.OP_CLOSE -> {
                    val code = if (frame.payload.size >= 2) {
                        ((frame.payload[0].toInt() and 0xFF) shl 8) or (frame.payload[1].toInt() and 0xFF)
                    } else 1005
                    val reason = if (frame.payload.size > 2) {
                        String(frame.payload, 2, frame.payload.size - 2, Charsets.UTF_8)
                    } else ""
                    out += WsFrame.Close(code, reason)
                }
                WsCodec.OP_CONTINUATION -> {
                    val acc = fragments
                    if (acc != null) {
                        acc.write(frame.payload)
                        if (frame.fin) {
                            out += messageOf(fragmentOpcode, acc.toByteArray())
                            fragments = null
                            fragmentOpcode = -1
                        }
                    }
                }
                else -> {
                    if (frame.fin) {
                        out += messageOf(frame.opcode, frame.payload)
                    } else {
                        fragmentOpcode = frame.opcode
                        fragments = ByteArrayOutputStream().also { it.write(frame.payload) }
                    }
                }
            }
        }
        return out
    }

    private fun messageOf(opcode: Int, payload: ByteArray): WsFrame = when (opcode) {
        WsCodec.OP_TEXT -> WsFrame.Text(String(payload, Charsets.UTF_8))
        else -> WsFrame.Binary(payload)
    }

    private data class RawFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    /** Parses and consumes one frame from the head of [buffer]; null when incomplete. */
    private fun tryParseFrame(): RawFrame? {
        if (buffer.size < 2) return null
        val b0 = buffer[0].toInt() and 0xFF
        val b1 = buffer[1].toInt() and 0xFF
        val fin = b0 and 0x80 != 0
        val opcode = b0 and 0x0F
        val masked = b1 and 0x80 != 0
        var length = (b1 and 0x7F).toLong()
        var pos = 2
        when (length) {
            126L -> {
                if (buffer.size < pos + 2) return null
                length = (((buffer[pos].toInt() and 0xFF) shl 8) or (buffer[pos + 1].toInt() and 0xFF)).toLong()
                pos += 2
            }
            127L -> {
                if (buffer.size < pos + 8) return null
                length = 0
                for (i in 0 until 8) length = (length shl 8) or (buffer[pos + i].toInt() and 0xFF).toLong()
                pos += 8
            }
        }
        val maskKey = if (masked) {
            if (buffer.size < pos + 4) return null
            buffer.copyOfRange(pos, pos + 4).also { pos += 4 }
        } else null
        if (length > Int.MAX_VALUE || buffer.size < pos + length) return null
        val payload = buffer.copyOfRange(pos, (pos + length).toInt())
        if (maskKey != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
        buffer = buffer.copyOfRange((pos + length).toInt(), buffer.size)
        return RawFrame(fin, opcode, payload)
    }
}
