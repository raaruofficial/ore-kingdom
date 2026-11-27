package com.raaru.orekingdom.kingdom

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import javax.net.ssl.SSLSocketFactory

/**
 * The host's single websocket connection (CONTRACT.md §1: one connection
 * per device, authenticated at upgrade). Blocking socket on its own
 * thread; [KingdomTransport] owns reconnect/backoff policy — this class
 * only connects, speaks, and reports. No third-party websocket library:
 * the framing is [WsCodec]'s.
 */
class WsClient(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen()
        fun onMessage(text: String)
        fun onClosed(reason: String)
        fun onFailure(error: Exception)
    }

    private val random = SecureRandom()
    private val writeLock = Any()

    @Volatile private var socket: Socket? = null
    @Volatile private var output: BufferedOutputStream? = null
    @Volatile private var closedByUs = false

    val isOpen: Boolean get() = socket?.isConnected == true && socket?.isClosed == false && output != null

    /** Blocking connect + read loop. Call on a background thread; returns when the connection ends. */
    fun run() {
        var failure: Exception? = null
        var closeReason = "closed"
        try {
            val uri = URI(url)
            val secure = uri.scheme == "wss" || uri.scheme == "https"
            val port = when {
                uri.port > 0 -> uri.port
                secure -> 443
                else -> 80
            }
            val host = uri.host ?: throw IllegalArgumentException("websocket url has no host: $url")
            val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") +
                (uri.rawQuery?.let { "?$it" } ?: "")
            val sock = if (secure) {
                SSLSocketFactory.getDefault().createSocket(host, port) as Socket
            } else {
                Socket(host, port)
            }
            socket = sock
            val input = BufferedInputStream(sock.getInputStream())
            val out = BufferedOutputStream(sock.getOutputStream())
            output = out

            val keyBytes = ByteArray(16).also { random.nextBytes(it) }
            val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append(':').append(port).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                append("\r\n")
            }
            synchronized(writeLock) {
                out.write(request.toByteArray(Charsets.US_ASCII))
                out.flush()
            }
            val statusLine = readHttpLine(input)
                ?: throw IllegalStateException("websocket upgrade: no response")
            if (!statusLine.contains(" 101")) {
                // Drain the response headers before failing.
                while (true) {
                    val line = readHttpLine(input) ?: break
                    if (line.isEmpty()) break
                }
                throw IllegalStateException("websocket upgrade refused: $statusLine")
            }
            while (true) {
                val line = readHttpLine(input) ?: throw IllegalStateException("websocket upgrade truncated")
                if (line.isEmpty()) break
            }

            listener.onOpen()
            val assembler = WsFrameAssembler()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                if (read == 0) continue
                for (frame in assembler.feed(chunk, 0, read)) {
                    when (frame) {
                        is WsFrame.Text -> listener.onMessage(frame.text)
                        is WsFrame.Ping -> sendFrame(WsCodec.OP_PONG, frame.payload)
                        is WsFrame.Close -> {
                            closeReason = "server close ${frame.code} ${frame.reason}".trim()
                            return
                        }
                        else -> Unit // binary + pong: no contract traffic uses them
                    }
                }
            }
        } catch (e: Exception) {
            failure = e
        } finally {
            try { socket?.close() } catch (_: Exception) {
            }
            socket = null
            output = null
        }
        if (closedByUs) {
            listener.onClosed("closed locally")
        } else if (failure != null) {
            listener.onFailure(failure)
        } else {
            listener.onClosed(closeReason)
        }
    }

    /** Sends one text frame. Returns false when the connection is not open. */
    fun send(text: String): Boolean = sendFrame(WsCodec.OP_TEXT, text.toByteArray(Charsets.UTF_8))

    private fun sendFrame(opcode: Int, payload: ByteArray): Boolean {
        val out = output ?: return false
        val maskKey = ByteArray(4).also { random.nextBytes(it) }
        val frame = WsCodec.encodeFrame(opcode, payload, maskKey)
        return try {
            synchronized(writeLock) {
                out.write(frame)
                out.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun close() {
        closedByUs = true
        val out = output
        if (out != null) {
            try {
                val maskKey = ByteArray(4).also { random.nextBytes(it) }
                synchronized(writeLock) {
                    out.write(WsCodec.encodeClose(1000, "", maskKey))
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }
        try { socket?.close() } catch (_: Exception) {
        }
    }

    /** Reads one CRLF-terminated line from the upgrade response (headers are ASCII). */
    private fun readHttpLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (prev == '\r'.code && b == '\n'.code) {
                return sb.substring(0, sb.length - 1)
            }
            sb.append(b.toChar())
            prev = b
        }
    }
}
