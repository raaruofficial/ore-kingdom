package com.raaru.orekingdom.kingdom

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract decoders proven against CONTRACT.md §6's own example shapes. */
class KingdomJsonTest {

    @Test
    fun entitlementsDecodeFieldForField() {
        val json = JSONObject(
            """
            { "role": "member",
              "capabilities": {
                "post": ["ch_general", "ch_help_desk"],
                "react": ["ch_general"],
                "tip": true, "gift": true,
                "commercial_post": true, "spotlight_bid": true,
                "pump_module": "enter",
                "pump_day_included": false, "atm_unlock_included": false,
                "nightshift_console": false, "autopilot_surface": false,
                "mod_loft": false },
              "expires_at": "2026-12-26T17:00:00Z",
              "founding": true,
              "modules": { "pump": "on", "nightshift": "preview" } }
            """.trimIndent(),
        )
        val ents = KingdomJson.parseEntitlements(json)
        assertEquals("member", ents.role)
        assertEquals(listOf("ch_general", "ch_help_desk"), ents.capabilities.post)
        assertEquals(listOf("ch_general"), ents.capabilities.react)
        assertTrue(ents.capabilities.tip)
        assertEquals("enter", ents.capabilities.pumpModule)
        assertEquals("2026-12-26T17:00:00Z", ents.expiresAt)
        assertTrue(ents.founding)
        assertEquals(mapOf("pump" to "on", "nightshift" to "preview"), ents.modules)
    }

    @Test
    fun entitlementsNullExpiryDecodes() {
        val json = JSONObject(
            """
            { "role": "free",
              "capabilities": {
                "post": [], "react": [],
                "tip": false, "gift": false,
                "commercial_post": false, "spotlight_bid": false,
                "pump_module": "none",
                "pump_day_included": false, "atm_unlock_included": false,
                "nightshift_console": false, "autopilot_surface": false,
                "mod_loft": false },
              "expires_at": null,
              "founding": false,
              "modules": {} }
            """.trimIndent(),
        )
        assertNull(KingdomJson.parseEntitlements(json).expiresAt)
    }

    @Test
    fun channelAndMessageDecode() {
        val channel = KingdomJson.parseChannel(
            JSONObject(
                """{ "id": "ch_general", "category": "THE FLOOR", "name": "general",
                     "kind": "text", "slowmode_sec": 30,
                     "min_role_read": "free", "min_role_post": "member", "position": 0 }""",
            ),
        )
        assertEquals(30, channel.slowmodeSec)
        assertEquals("member", channel.minRolePost)

        val message = KingdomJson.parseMessage(
            JSONObject(
                """{ "id": "m_1", "channel_id": "ch_general", "seq": 1042,
                     "author_id": "u_1", "body": "hello",
                     "thread_id": null, "reply_to": null,
                     "created_at": "2026-11-26T17:00:00Z", "edited_at": null, "deleted_at": null,
                     "reactions": [ { "emoji": "🔥", "count": 3, "mine": true } ],
                     "commercial": false }""",
            ),
        )
        assertEquals(1042L, message.seq)
        assertNull(message.threadId)
        assertEquals(1, message.reactions.size)
        assertEquals(3, message.reactions[0].count)
        assertTrue(message.reactions[0].mine)
    }

    @Test
    fun threadDecodesFieldForField() {
        val thread = KingdomJson.parseThread(
            JSONObject(
                """{ "id": "t_1", "channel_id": "ch_strategy", "anchor_message_id": null,
                     "title": "Motherlode watch", "state": "active", "reply_count": 2,
                     "last_activity_at": "2026-11-26T17:00:00Z" }""",
            ),
        )
        assertEquals("t_1", thread.id)
        assertEquals("ch_strategy", thread.channelId)
        assertNull(thread.anchorMessageId)
        assertEquals(2, thread.replyCount)
        assertEquals("2026-11-26T17:00:00Z", thread.lastActivityAt)

        val anchored = KingdomJson.parseThread(
            JSONObject(
                """{ "id": "t_2", "channel_id": "ch_general", "anchor_message_id": "m_9",
                     "title": "Rig talk", "state": "archived", "reply_count": 0,
                     "last_activity_at": "2026-11-26T18:00:00Z" }""",
            ),
        )
        assertEquals("m_9", anchored.anchorMessageId)
        assertEquals("archived", anchored.state)
    }

    @Test
    fun sendFrameCarriesIdempotencyKeyAsEnvelopeId() {
        val frame = JSONObject(KingdomJson.sendFrame("c_key1", "ch_general", "hi", null, "m_9"))
        assertEquals("send", frame.getString("type"))
        assertEquals("c_key1", frame.getString("id"))
        assertEquals("ch_general", frame.getString("channel_id"))
        val payload = frame.getJSONObject("payload")
        assertEquals("hi", payload.getString("body"))
        assertEquals("m_9", payload.getString("reply_to"))
    }

    @Test
    fun resumeFrameCarriesLastSeqMap() {
        val frame = JSONObject(KingdomJson.resumeFrame(mapOf("ch_general" to 41L)))
        assertEquals("resume", frame.getString("type"))
        assertEquals(41L, frame.getJSONObject("payload").getJSONObject("last_seq").getLong("ch_general"))
    }
}
