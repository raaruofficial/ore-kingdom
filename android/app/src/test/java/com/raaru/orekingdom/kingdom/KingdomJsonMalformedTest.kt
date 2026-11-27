package com.raaru.orekingdom.kingdom

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
}
