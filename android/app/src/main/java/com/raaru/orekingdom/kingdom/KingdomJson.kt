package com.raaru.orekingdom.kingdom

import com.raaru.orekingdom.channel.ChannelRealtime
import com.raaru.orekingdom.channel.PresenceUi
import com.raaru.orekingdom.shell.Capabilities
import com.raaru.orekingdom.shell.ChannelDto
import com.raaru.orekingdom.shell.Entitlements
import com.raaru.orekingdom.shell.SpotlightDto
import com.raaru.orekingdom.shell.SpotlightPostDto
import org.json.JSONArray
import org.json.JSONObject

/** CONTRACT.md §6 Message, field-for-field. The client library carries no message DTO of its own. */
data class ContractReaction(val emoji: String, val count: Int, val mine: Boolean)

data class ContractMessage(
    val id: String,
    val channelId: String,
    val seq: Long,
    val authorId: String,
    val body: String,
    val threadId: String?,
    val replyTo: String?,
    val createdAt: String,
    val editedAt: String?,
    val deletedAt: String?,
    val reactions: List<ContractReaction>,
    val commercial: Boolean,
)

/** CONTRACT.md §6 Thread, field-for-field. */
data class ContractThread(
    val id: String,
    val channelId: String,
    val anchorMessageId: String?,
    val title: String,
    val state: String,
    val replyCount: Int,
    val lastActivityAt: String,
)

/** CONTRACT.md §6 Member (render shape). */
data class ContractMember(
    val userId: String,
    val displayName: String,
    val role: String,
    val founding: Boolean,
    val moderator: Boolean,
)

/**
 * Decoders/encoders between org.json and the pinned contract shapes
 * (CONTRACT.md §2/§3/§6). Field names map one-to-one; nothing is
 * defaulted or invented — a missing required field throws, and the
 * transport treats that as a failed read, exactly as INTEGRATION_MAP
 * §3.1 prescribes.
 */
object KingdomJson {

    fun parseEntitlements(json: JSONObject): Entitlements {
        val caps = json.getJSONObject("capabilities")
        return Entitlements(
            role = json.getString("role"),
            capabilities = Capabilities(
                post = caps.getJSONArray("post").toStringList(),
                react = caps.getJSONArray("react").toStringList(),
                tip = caps.getBoolean("tip"),
                gift = caps.getBoolean("gift"),
                commercialPost = caps.getBoolean("commercial_post"),
                spotlightBid = caps.getBoolean("spotlight_bid"),
                pumpModule = caps.getString("pump_module"),
                pumpDayIncluded = caps.getBoolean("pump_day_included"),
                atmUnlockIncluded = caps.getBoolean("atm_unlock_included"),
                nightshiftConsole = caps.getBoolean("nightshift_console"),
                autopilotSurface = caps.getBoolean("autopilot_surface"),
                modLoft = caps.getBoolean("mod_loft"),
            ),
            expiresAt = json.optStringOrNull("expires_at"),
            founding = json.getBoolean("founding"),
            modules = json.getJSONObject("modules").toStringMap(),
        )
    }

    fun parseChannel(json: JSONObject): ChannelDto = ChannelDto(
        id = json.getString("id"),
        category = json.getString("category"),
        name = json.getString("name"),
        kind = json.getString("kind"),
        slowmodeSec = json.getInt("slowmode_sec"),
        minRoleRead = json.getString("min_role_read"),
        minRolePost = json.getString("min_role_post"),
        position = json.getInt("position"),
    )

    fun parseChannels(array: JSONArray): List<ChannelDto> =
        (0 until array.length()).map { parseChannel(array.getJSONObject(it)) }

    fun parseSpotlight(json: JSONObject): SpotlightDto {
        val post = if (json.isNull("post")) null else json.getJSONObject("post")
        return SpotlightDto(
            occupied = json.getBoolean("occupied"),
            post = post?.let {
                SpotlightPostDto(
                    authorId = it.getString("author_id"),
                    body = it.getString("body"),
                    commercial = it.getBoolean("commercial"),
                )
            },
            windowEndsAt = json.optStringOrNull("window_ends_at"),
            floorUsd = json.getInt("floor_usd"),
        )
    }

    fun parseMessage(json: JSONObject): ContractMessage = ContractMessage(
        id = json.getString("id"),
        channelId = json.getString("channel_id"),
        seq = json.getLong("seq"),
        authorId = json.getString("author_id"),
        body = json.getString("body"),
        threadId = json.optStringOrNull("thread_id"),
        replyTo = json.optStringOrNull("reply_to"),
        createdAt = json.getString("created_at"),
        editedAt = json.optStringOrNull("edited_at"),
        deletedAt = json.optStringOrNull("deleted_at"),
        reactions = json.optJSONArray("reactions")?.let { array ->
            (0 until array.length()).map {
                val r = array.getJSONObject(it)
                ContractReaction(r.getString("emoji"), r.getInt("count"), r.optBoolean("mine", false))
            }
        } ?: emptyList(),
        commercial = json.optBoolean("commercial", false),
    )

    fun parseMember(json: JSONObject): ContractMember = ContractMember(
        userId = json.getString("user_id"),
        displayName = json.getString("display_name"),
        role = json.getString("role"),
        founding = json.optBoolean("founding", false),
        moderator = json.optBoolean("moderator", false),
    )

    fun parseThread(json: JSONObject): ContractThread = ContractThread(
        id = json.getString("id"),
        channelId = json.getString("channel_id"),
        anchorMessageId = json.optStringOrNull("anchor_message_id"),
        title = json.getString("title"),
        state = json.getString("state"),
        replyCount = json.getInt("reply_count"),
        lastActivityAt = json.getString("last_activity_at"),
    )

    fun parsePresence(json: JSONObject): PresenceUi = ChannelRealtime.presenceOf(
        userId = json.getString("user_id"),
        state = json.getString("state"),
        activity = json.optStringOrNull("activity"),
        invisible = json.optBoolean("invisible", false),
    )

    /* ---- Outbound frames (CONTRACT.md §2 envelope, §3 shapes) ---- */

    fun frame(type: String, payload: JSONObject, channelId: String? = null, id: String? = null): String {
        val envelope = JSONObject()
            .put("type", type)
            .put("payload", payload)
        if (id != null) envelope.put("id", id)
        if (channelId != null) envelope.put("channel_id", channelId)
        return envelope.toString()
    }

    fun subscribeFrame(channelIds: List<String>): String =
        frame("subscribe", JSONObject().put("channel_ids", JSONArray(channelIds)))

    fun unsubscribeFrame(channelIds: List<String>): String =
        frame("unsubscribe", JSONObject().put("channel_ids", JSONArray(channelIds)))

    fun resumeFrame(lastSeq: Map<String, Long>): String {
        val map = JSONObject()
        lastSeq.forEach { (id, seq) -> map.put(id, seq) }
        return frame("resume", JSONObject().put("last_seq", map))
    }

    fun sendFrame(idempotencyKey: String, channelId: String, body: String, threadId: String?, replyTo: String?): String {
        val payload = JSONObject().put("body", body)
        if (threadId != null) payload.put("thread_id", threadId)
        if (replyTo != null) payload.put("reply_to", replyTo)
        return frame("send", payload, channelId = channelId, id = idempotencyKey)
    }

    fun editFrame(channelId: String, messageId: String, body: String): String =
        frame("edit", JSONObject().put("message_id", messageId).put("body", body), channelId = channelId)

    fun deleteFrame(channelId: String, messageId: String): String =
        frame("delete", JSONObject().put("message_id", messageId), channelId = channelId)

    fun reactFrame(channelId: String, messageId: String, emoji: String, add: Boolean): String =
        frame(
            "react",
            JSONObject().put("message_id", messageId).put("emoji", emoji).put("op", if (add) "add" else "remove"),
            channelId = channelId,
        )

    fun typingFrame(channelId: String, typing: Boolean): String =
        frame("typing", JSONObject().put("state", if (typing) "start" else "stop"), channelId = channelId)

    fun presenceSetFrame(invisible: Boolean): String =
        frame("presence.set", JSONObject().put("invisible", invisible))
}

fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

fun JSONObject.toStringMap(): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        out[key] = getString(key)
    }
    return out
}
