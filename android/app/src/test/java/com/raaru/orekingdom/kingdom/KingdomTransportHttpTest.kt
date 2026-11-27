package com.raaru.orekingdom.kingdom

import com.raaru.orekingdom.channel.HistoryPageRequest
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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

    @Test
    fun factoryReceivesTheVerbatimUrl() {
        var seen: URL? = null
        val t = KingdomTransport("http://localhost", connectionFactory = { url -> seen = url; FakeConnection(url, 200, "{}", null) })
        runBlocking { t.getJson("/api/me") }
        assertEquals("http://localhost/api/me", seen.toString())
    }

    @Test
    fun openedConnectionCarriesMethodAndJsonHeaders() {
        var conn: FakeConnection? = null
        val t = KingdomTransport("http://localhost", connectionFactory = { url -> FakeConnection(url, 200, "{}", null).also { conn = it } })
        runBlocking { t.getJson("/api/me") }
        assertEquals("GET", conn?.requestMethod)
        assertEquals("application/json", conn?.getRequestProperty("Accept"))
        assertEquals("application/json", conn?.getRequestProperty("Content-Type"))
    }

    @Test
    fun historyUrlCarriesChannelAndLimitOnlyWithoutCursor() {
        var seen: URL? = null
        val t = KingdomTransport(
            "http://localhost",
            connectionFactory = { url -> seen = url; FakeConnection(url, 200, "{\"messages\":[],\"has_more\":false}", null) },
        )

        val read = runBlocking { t.getHistory(HistoryPageRequest("ch_general", null)) }

        assertEquals("http://localhost/api/history?channel_id=ch_general&limit=50", seen.toString())
        assertEquals(false, read.hasMore)
        assertTrue(read.messages.isEmpty())
    }

    @Test
    fun historyUrlPlacesBeforeSeqBetweenChannelAndLimit() {
        var seen: URL? = null
        val t = KingdomTransport(
            "http://localhost",
            connectionFactory = { url -> seen = url; FakeConnection(url, 200, "{\"messages\":[],\"has_more\":true}", null) },
        )

        val read = runBlocking { t.getHistory(HistoryPageRequest("ch_general", 42L)) }

        assertEquals("http://localhost/api/history?channel_id=ch_general&before_seq=42&limit=50", seen.toString())
        assertEquals(true, read.hasMore)
    }

    @Test
    fun threadHistoryUrlCarriesChannelThreadAndLimit200() {
        var seen: URL? = null
        val t = KingdomTransport(
            "http://localhost",
            connectionFactory = { url -> seen = url; FakeConnection(url, 200, "{\"messages\":[]}", null) },
        )

        val messages = runBlocking { t.getThreadHistory("ch_general", "th_1") }

        assertEquals("http://localhost/api/history?channel_id=ch_general&thread_id=th_1&limit=200", seen.toString())
        assertTrue(messages.isEmpty())
    }

    @Test
    fun searchQueryIsFormEncodedNeverRaw() {
        var seen: URL? = null
        val t = KingdomTransport(
            "http://localhost",
            connectionFactory = { url -> seen = url; FakeConnection(url, 200, "{\"messages\":[],\"threads\":[]}", null) },
        )

        runBlocking { t.getSearch("ore & more") }

        assertEquals("http://localhost/api/search?q=ore+%26+more", seen.toString())
    }

    @Test
    fun threadsChannelIdIsFormEncoded() {
        var seen: URL? = null
        val t = KingdomTransport(
            "http://localhost",
            connectionFactory = { url -> seen = url; FakeConnection(url, 200, "{\"threads\":[]}", null) },
        )

        runBlocking { t.getThreads("ch a&b") }

        assertEquals("http://localhost/api/threads?channel_id=ch+a%26b", seen.toString())
    }
}
