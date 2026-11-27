package com.raaru.orekingdom.kingdom

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoder failure edges (DeepSeek programme item 2): a malformed
 * contract payload must fail the read loudly (the transport treats a
 * throw as a failed read), never decode into a half-shape. The one
 * softening that exists by design is `optStringOrNull`: an empty
 * string reads as null. Supervisor-authored (see programme record).
 */
class KingdomJsonMalformedTest {

    @Test
    fun messageMissingSeqThrows() {
        val json = JSONObject(
            """{ "id": "m_1", "channel_id": "ch_general",
                 "author_id": "u_1", "body": "hello",
                 "thread_id": null, "reply_to": null,
                 "created_at": "2026-11-26T17:00:00Z", "edited_at": null, "deleted_at": null,
                 "reactions": [], "commercial": false }""",
        )
        assertThrows(JSONException::class.java) { KingdomJson.parseMessage(json) }
    }

    @Test
    fun messageWithMalformedReactionEntryThrows() {
        val json = JSONObject(
            """{ "id": "m_1", "channel_id": "ch_general", "seq": 7,
                 "author_id": "u_1", "body": "hello",
                 "thread_id": null, "reply_to": null,
                 "created_at": "2026-11-26T17:00:00Z", "edited_at": null, "deleted_at": null,
                 "reactions": [ { "count": 3, "mine": true } ], "commercial": false }""",
        )
        assertThrows(JSONException::class.java) { KingdomJson.parseMessage(json) }
    }

    @Test
    fun entitlementsMissingCapabilitiesThrows() {
        val json = JSONObject(
            """{ "role": "member", "expires_at": null, "founding": false, "modules": {} }""",
        )
        assertThrows(JSONException::class.java) { KingdomJson.parseEntitlements(json) }
    }

    @Test
    fun threadMissingReplyCountThrows() {
        val json = JSONObject(
            """{ "id": "t_1", "channel_id": "ch_strategy", "anchor_message_id": null,
                 "title": "Motherlode watch", "state": "active",
                 "last_activity_at": "2026-11-26T17:00:00Z" }""",
        )
        assertThrows(JSONException::class.java) { KingdomJson.parseThread(json) }
    }

    @Test
    fun channelArrayWithMalformedEntryThrows() {
        // SW2-03 premise: the transport decodes a push's channels array with
        // parseChannels inside a runCatching — the throw is what makes a
        // malformed array fall back to a refetch instead of landing half-shape.
        val array = JSONArray(
            """[ { "id": "ch_general", "category": "THE FLOOR", "name": "general",
                  "kind": "text", "slowmode_sec": 0, "min_role_read": "free",
                  "min_role_post": "member", "position": 0 },
                { "id": "ch_help", "category": "THE FLOOR" } ]""",
        )
        assertThrows(JSONException::class.java) { KingdomJson.parseChannels(array) }
    }

    @Test
    fun emptyStringNullableReadsAsNull() {
        val message = KingdomJson.parseMessage(
            JSONObject(
                """{ "id": "m_1", "channel_id": "ch_general", "seq": 7,
                     "author_id": "u_1", "body": "hello",
                     "thread_id": "", "reply_to": "",
                     "created_at": "2026-11-26T17:00:00Z", "edited_at": "", "deleted_at": null,
                     "reactions": [], "commercial": false }""",
            ),
        )
        assertNull(message.threadId)
        assertNull(message.replyTo)
        assertNull(message.editedAt)
    }

    @Test
    fun messageWithoutReactionsKeyDecodesEmptyReactions() {
        val message = KingdomJson.parseMessage(
            JSONObject(
                """{ "id": "m_1", "channel_id": "ch_general", "seq": 7, "author_id": "u_1", "body": "hello", "thread_id": null, "reply_to": null, "created_at": "2026-11-26T17:00:00Z", "edited_at": null, "deleted_at": null, "commercial": false }""",
            ),
        )
        assertTrue(message.reactions.isEmpty())
    }

    @Test
    fun messageWithoutCommercialKeyDecodesCommercialFalse() {
        val message = KingdomJson.parseMessage(
            JSONObject(
                """{ "id": "m_1", "channel_id": "ch_general", "seq": 7, "author_id": "u_1", "body": "hello", "thread_id": null, "reply_to": null, "created_at": "2026-11-26T17:00:00Z", "edited_at": null, "deleted_at": null, "reactions": [] }""",
            ),
        )
        assertEquals(false, message.commercial)
    }

    @Test
    fun reactionWithoutMineKeyDecodesMineFalse() {
        val message = KingdomJson.parseMessage(
            JSONObject(
                """{ "id": "m_1", "channel_id": "ch_general", "seq": 7, "author_id": "u_1", "body": "hello", "thread_id": null, "reply_to": null, "created_at": "2026-11-26T17:00:00Z", "edited_at": null, "deleted_at": null, "reactions": [ { "emoji": "🔥", "count": 2 } ], "commercial": false }""",
            ),
        )
        assertEquals(1, message.reactions.size)
        assertEquals(2, message.reactions[0].count)
        assertEquals(false, message.reactions[0].mine)
    }

    @Test
    fun reactionMissingCountThrows() {
        val json = JSONObject(
            """{ "id": "m_1", "channel_id": "ch_general", "seq": 7, "author_id": "u_1", "body": "hello", "thread_id": null, "reply_to": null, "created_at": "2026-11-26T17:00:00Z", "edited_at": null, "deleted_at": null, "reactions": [ { "emoji": "🔥", "mine": true } ], "commercial": false }""",
        )
        assertThrows(JSONException::class.java) { KingdomJson.parseMessage(json) }
    }

    @Test
    fun memberWithoutFoundingModeratorKeysDecodesBothFalse() {
        val member = KingdomJson.parseMember(JSONObject("""{ "user_id": "u_1", "display_name": "Host", "role": "member" }"""))
        assertEquals(false, member.founding)
        assertEquals(false, member.moderator)
    }

    @Test
    fun memberMissingDisplayNameThrows() {
        val json = JSONObject(
            """{ "user_id": "u_1", "role": "member" }""",
        )
        assertThrows(JSONException::class.java) { KingdomJson.parseMember(json) }
    }

    @Test
    fun presenceWithEmptyActivityDecodesActivityNull() {
        val presence = KingdomJson.parsePresence(JSONObject("""{ "user_id": "u_1", "state": "online", "activity": "", "invisible": false }"""))
        assertNull(presence.activity)
    }

    @Test
    fun presenceMissingStateThrows() {
        val json = JSONObject(
            """{ "user_id": "u_1", "activity": null, "invisible": false }""",
        )
        assertThrows(JSONException::class.java) { KingdomJson.parsePresence(json) }
    }

    @Test
    fun presenceWithoutInvisibleKeyDecodesInvisibleFalse() {
        val presence = KingdomJson.parsePresence(JSONObject("""{ "user_id": "u_1", "state": "online", "activity": null }"""))
        assertEquals(false, presence.invisible)
    }

    @Test
    fun spotlightPostMissingBodyThrows() {
        val json = JSONObject(
            """{ "occupied": true, "post": { "author_id": "u_1", "commercial": false }, "window_ends_at": null, "floor_usd": 50 }""",
        )
        assertThrows(JSONException::class.java) { KingdomJson.parseSpotlight(json) }
    }

    @Test
    fun spotlightWithNullPostAndOccupiedDecodesPostNull() {
        val spotlight = KingdomJson.parseSpotlight(JSONObject("""{ "occupied": true, "post": null, "window_ends_at": "2026-11-26T18:00:00Z", "floor_usd": 50 }"""))
        assertTrue(spotlight.occupied)
        assertNull(spotlight.post)
        assertEquals(50, spotlight.floorUsd)
    }

    @Test
    fun entitlementsCapabilitiesMissingModLoftThrows() {
        val json = JSONObject(
            """{ "role": "member", "capabilities": { "post": [], "react": [], "tip": false, "gift": false, "commercial_post": false, "spotlight_bid": false, "pump_module": "none", "pump_day_included": false, "atm_unlock_included": false, "nightshift_console": false, "autopilot_surface": false }, "expires_at": null, "founding": false, "modules": {} }""",
        )
        assertThrows(JSONException::class.java) { KingdomJson.parseEntitlements(json) }
    }

    @Test
    fun channelMissingPositionThrows() {
        val json = JSONObject(
            """{ "id": "ch_general", "category": "THE FLOOR", "name": "general", "kind": "text", "slowmode_sec": 0, "min_role_read": "free", "min_role_post": "member" }""",
        )
        assertThrows(JSONException::class.java) { KingdomJson.parseChannel(json) }
    }
}
