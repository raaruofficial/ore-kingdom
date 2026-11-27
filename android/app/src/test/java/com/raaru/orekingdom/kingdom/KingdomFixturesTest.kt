package com.raaru.orekingdom.kingdom

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared contract fixtures (DeepSeek programme item 4): the SAME
 * golden frames file the server checks consume
 * (server/kingdom/fixtures/frames.json), read here so the client side
 * cannot drift from the wire either. Declared key sets must match the
 * golden frames themselves; server frames must decode with the
 * production decoders; client frames must rebuild with the production
 * builders to identical key sets. Fails loud if the file is missing.
 */
class KingdomFixturesTest {

    private fun fixturesFile(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(9) {
            val candidate = File(dir, "server/kingdom/fixtures/frames.json")
            if (candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        throw AssertionError("fixtures/frames.json not found walking up from ${System.getProperty("user.dir")}")
    }

    private fun entries(): List<JSONObject> {
        val doc = JSONObject(fixturesFile().readText())
        val frames = doc.getJSONArray("frames")
        return (0 until frames.length()).map { frames.getJSONObject(it) }
    }

    // Named sortedKeys, NOT keySet: org.json's JSONObject has a member
    // keySet() returning Set<String>, which shadows an extension of the
    // same name — a List-vs-Set assertEquals then fails on every entry.
    private fun JSONObject.sortedKeys(): List<String> {
        val out = ArrayList<String>()
        val keys = keys()
        while (keys.hasNext()) out += keys.next()
        return out.sorted()
    }

    private fun declared(entry: JSONObject, field: String): List<String> {
        val array = entry.getJSONArray(field)
        return (0 until array.length()).map { array.getString(it) }
    }

    @Test
    fun everyFixtureDeclaresItsOwnKeySetsHonestly() {
        val entries = entries()
        assertEquals(19, entries.size)
        entries.forEach { entry ->
            val frame = entry.getJSONObject("frame")
            val name = entry.getString("name")
            assertEquals(
                "envelope keys of $name",
                declared(entry, "envelope_keys"),
                frame.sortedKeys(),
            )
            assertEquals(
                "payload keys of $name",
                declared(entry, "payload_keys"),
                frame.getJSONObject("payload").sortedKeys(),
            )
        }
    }

    @Test
    fun serverGoldenFramesDecodeWithProductionDecoders() {
        val byName = entries().associateBy { it.getString("name") }

        val message = byName.getValue("message").getJSONObject("frame")
            .getJSONObject("payload").getJSONObject("message")
        val decoded = KingdomJson.parseMessage(message)
        assertEquals(1042L, decoded.seq)
        assertEquals("u_host", decoded.authorId)
        assertEquals(1, decoded.reactions.size)
        assertTrue(decoded.reactions[0].mine)

        val update = byName.getValue("message.update").getJSONObject("frame")
            .getJSONObject("payload").getJSONObject("message")
        assertNotNull(KingdomJson.parseMessage(update).editedAt)

        val roleUpdate = byName.getValue("role.update").getJSONObject("frame").getJSONObject("payload")
        val entitlements = KingdomJson.parseEntitlements(roleUpdate.getJSONObject("entitlements"))
        assertEquals("member", entitlements.role)
        assertEquals(listOf("ch_general"), entitlements.capabilities.post)
        val channel = KingdomJson.parseChannel(roleUpdate.getJSONArray("channels").getJSONObject(0))
        assertEquals("ch_general", channel.id)
        assertEquals(0, channel.slowmodeSec)

        val presence = byName.getValue("presence").getJSONObject("frame")
            .getJSONObject("payload").getJSONObject("presence")
        val parsedPresence = KingdomJson.parsePresence(presence)
        assertEquals("u_op", parsedPresence.userId)
        assertEquals("in a round", parsedPresence.activity)
    }

    @Test
    fun clientGoldenFramesRebuildWithProductionBuilders() {
        val byName = entries().associateBy { it.getString("name") }

        val send = byName.getValue("send").getJSONObject("frame")
        val rebuiltSend = JSONObject(
            KingdomJson.sendFrame(
                idempotencyKey = send.getString("id"),
                channelId = send.getString("channel_id"),
                body = send.getJSONObject("payload").getString("body"),
                threadId = null,
                replyTo = send.getJSONObject("payload").getString("reply_to"),
            ),
        )
        assertEquals(send.sortedKeys(), rebuiltSend.sortedKeys())
        assertEquals(
            send.getJSONObject("payload").sortedKeys(),
            rebuiltSend.getJSONObject("payload").sortedKeys(),
        )

        val subscribe = byName.getValue("subscribe").getJSONObject("frame")
        val ids = subscribe.getJSONObject("payload").getJSONArray("channel_ids")
        val rebuiltSubscribe = JSONObject(
            KingdomJson.subscribeFrame((0 until ids.length()).map { ids.getString(it) }),
        )
        assertEquals(subscribe.sortedKeys(), rebuiltSubscribe.sortedKeys())
        assertEquals(
            subscribe.getJSONObject("payload").sortedKeys(),
            rebuiltSubscribe.getJSONObject("payload").sortedKeys(),
        )

        val resume = byName.getValue("resume").getJSONObject("frame")
        val lastSeq = resume.getJSONObject("payload").getJSONObject("last_seq")
        val channelId = lastSeq.keys().next()
        val rebuiltResume = JSONObject(
            KingdomJson.resumeFrame(mapOf(channelId to lastSeq.getLong(channelId))),
        )
        assertEquals(resume.sortedKeys(), rebuiltResume.sortedKeys())
        assertEquals(
            resume.getJSONObject("payload").sortedKeys(),
            rebuiltResume.getJSONObject("payload").sortedKeys(),
        )
    }
}
