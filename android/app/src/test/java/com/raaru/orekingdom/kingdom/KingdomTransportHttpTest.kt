package com.raaru.orekingdom.kingdom

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * HTTP error-path pins for KingdomTransport's readJson (DeepSeek
 * full-spend programme Q-26; the connection factory is seam S-SEAM-1).
 */
class KingdomTransportHttpTest {

    private class FakeConnection(
        url: URL,
        private val code: Int,
        private val body: String,
        private val errorBody: String?,
    ) : HttpURLConnection(url) {
        override fun connect() {
            connected = true
        }

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = code

        override fun getInputStream(): InputStream =
            ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8))

        override fun getErrorStream(): InputStream? =
            errorBody?.let { ByteArrayInputStream(it.toByteArray(StandardCharsets.UTF_8)) }
    }

    private fun transport(code: Int, body: String = "", errorBody: String? = null): KingdomTransport =
        KingdomTransport(
            "http://localhost",
            connectionFactory = { url -> FakeConnection(url, code, body, errorBody) },
        )

    @Test
    fun notFoundThrowsWithCodeAndBody() {
        val thrown = assertThrows(KingdomHttpException::class.java) { runBlocking { transport(404, errorBody = "{\"error\":\"not_found\"}").getJson("/api/missing") } }
        assertEquals(404, thrown.code)
        assertEquals("{\"error\":\"not_found\"}", thrown.body)
    }

    @Test
    fun serverErrorThrowsWithCodeAndBody() {
        val thrown = assertThrows(KingdomHttpException::class.java) { runBlocking { transport(500, errorBody = "{\"error\":\"boom\"}").getJson("/api/broken") } }
        assertEquals(500, thrown.code)
        assertEquals("{\"error\":\"boom\"}", thrown.body)
    }

    @Test
    fun blankOkBodyReadsAsEmptyObject() {
        val json = runBlocking { transport(200, body = "").getJson("/api/empty") }
        assertEquals(0, json.length())
    }

    @Test
    fun malformedOkBodyThrowsJsonException() {
        assertThrows(JSONException::class.java) { runBlocking { transport(200, body = "not json").getJson("/api/bad") } }
    }

    @Test
    fun errorWithoutErrorStreamThrowsWithEmptyBody() {
        val thrown = assertThrows(KingdomHttpException::class.java) { runBlocking { transport(503).getJson("/api/down") } }
        assertEquals(503, thrown.code)
        assertEquals("", thrown.body)
    }
}
