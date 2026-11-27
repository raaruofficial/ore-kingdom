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

    @Test
    fun passesNullSeatsStayNullAndValuedSeatsParse() {
        val nullSeats = runBlocking {
            transport(200, body = "{\"lifetime_holder\":true,\"prestige_seats_remaining\":null}").getPasses()
        }
        assertEquals(true, nullSeats.lifetimeHolder)
        assertEquals(null, nullSeats.prestigeSeatsRemaining)

        val valued = runBlocking {
            transport(200, body = "{\"lifetime_holder\":false,\"prestige_seats_remaining\":7}").getPasses()
        }
        assertEquals(false, valued.lifetimeHolder)
        assertEquals(7, valued.prestigeSeatsRemaining)
    }

    @Test
    fun crownWithBothFieldsMissingReadsNullNull() {
        val empty = runBlocking { transport(200, body = "{}").getCrown() }
        assertEquals(null, empty.holderName)
        assertEquals(null, empty.month)

        val held = runBlocking {
            transport(200, body = "{\"holder_name\":\"Other\",\"month\":\"2026-11\"}").getCrown()
        }
        assertEquals("Other", held.holderName)
        assertEquals("2026-11", held.month)
    }

    @Test
    fun sanctionDefaultsForAbsentAppealNullDurationAndAbsentId() {
        val body = "{\"sanctions\":[" +
            "{\"rung\":\"mute\",\"reason_category\":\"harassment\",\"duration_sec\":null}," +
            "{\"sanction_id\":\"s_9\",\"rung\":\"ban\",\"reason_category\":\"scam\",\"duration_sec\":86400,\"appeal\":{\"state\":\"pending\"}}" +
            "]}"
        val sanctions = runBlocking { transport(200, body = body).getSanctions() }

        assertEquals(2, sanctions.size)
        val first = sanctions[0]
        assertEquals(null, first.sanctionId)
        assertEquals(null, first.durationSec)
        assertEquals("none", first.appealState)
        val second = sanctions[1]
        assertEquals("s_9", second.sanctionId)
        assertEquals(86400L, second.durationSec)
        assertEquals("pending", second.appealState)
    }

    @Test
    fun modQueueItemDefaultsForAbsentContextHistoryAndNullArms() {
        val body = "{\"items\":[{" +
            "\"report_id\":\"rep_1\",\"state\":\"open\",\"claimed_by\":null,\"reason\":\"scam\",\"reporter_count\":2," +
            "\"snapshot\":{\"body\":\"flagged\",\"author_id\":\"u_a\",\"channel_id\":\"ch_general\",\"created_at\":\"2026-11-27T01:00:00Z\"}," +
            "\"appeal\":null}]}"
        val items = runBlocking { transport(200, body = body).getModQueue() }

        val item = items.single()
        assertEquals("rep_1", item.reportId)
        assertTrue(item.snapshot.context.isEmpty())
        assertTrue(item.authorHistory.isEmpty())
        assertEquals(null, item.appeal)
        assertEquals(null, item.claimedBy)
    }

    @Test
    fun modQueueItemFullShapeParsesFieldForField() {
        val body = "{\"items\":[{" +
            "\"report_id\":\"rep_2\",\"state\":\"claimed\",\"claimed_by\":\"u_mod\",\"reason\":\"harassment\",\"reporter_count\":3," +
            "\"snapshot\":{\"body\":\"flagged\",\"author_id\":\"u_a\",\"channel_id\":\"ch_general\",\"created_at\":\"2026-11-27T01:00:00Z\"," +
            "\"context\":[{\"id\":\"c_1\",\"channel_id\":\"ch_general\",\"seq\":4,\"author_id\":\"u_b\",\"body\":\"ctx body\",\"created_at\":\"2026-11-27T00:59:00Z\"}]}," +
            "\"author_history\":[{\"rung\":\"mute\",\"reason_category\":\"spam\",\"at\":\"2026-11-20T01:00:00Z\"}]," +
            "\"appeal\":{\"statement\":\"please\",\"sanction_by\":\"u_mod\",\"state\":\"pending\"}}]}"
        val items = runBlocking { transport(200, body = body).getModQueue() }

        val item = items.single()
        assertEquals("claimed", item.state)
        assertEquals("u_mod", item.claimedBy)
        assertEquals(3, item.reporterCount)
        assertEquals("c_1", item.snapshot.context.single().id)
        assertEquals("ctx body", item.snapshot.context.single().body)
        assertEquals("mute", item.authorHistory.single().rung)
        assertEquals("please", item.appeal?.statement)
        assertEquals("pending", item.appeal?.state)
    }
}
