package com.raaru.orekingdom.data

import org.json.JSONObject

/**
 * Ore Kingdom host payload models, parsed from the bundle's response
 * schemas (the-claim-build/server/src/actions.ts listForum / getThread).
 * Parsing is tolerant: opt* accessors throughout, so a host payload that
 * grows a field never crashes the client, and a missing field degrades
 * to a default instead of a blank screen.
 */

data class Member(
    val id: Int,
    val wallet: String,
    val displayName: String,
    val skrName: String,
    val reputation: Int,
    val tier: String,
    val isFoundingMember: Boolean
) {
    companion object {
        fun fromJson(o: JSONObject) = Member(
            id = o.optInt("id"),
            wallet = o.optString("wallet"),
            displayName = o.optString("displayName"),
            skrName = o.optString("skrName"),
            reputation = o.optInt("reputation"),
            tier = o.optString("tier"),
            isFoundingMember = o.optBoolean("isFoundingMember")
        )
    }
}

data class Category(
    val id: Int,
    val slug: String,
    val name: String,
    val description: String,
    val count: Int,
    /** The VIP-board marker the wrapper serves on category rows
     *  (forumReadTransform, M2 host fixes). Tolerant: absent on an
     *  older host, where it defaults to false. */
    val isVip: Boolean = false
) {
    companion object {
        fun fromJson(o: JSONObject) = Category(
            id = o.optInt("id"),
            slug = o.optString("slug"),
            name = o.optString("name"),
            description = o.optString("description"),
            count = o.optInt("count"),
            isVip = o.optBoolean("isVip")
        )
    }
}

data class ForumThread(
    val id: Int,
    val categoryId: Int,
    val categoryName: String,
    val title: String,
    val body: String,
    val authorName: String,
    val authorIsFoundingMember: Boolean,
    val isPinned: Boolean,
    val isPaidPinned: Boolean,
    val paidPinEndsAt: String?,
    val canPaidPin: Boolean,
    val canTip: Boolean,
    val tipCount: Int,
    val isLocked: Boolean,
    val isSeed: Boolean,
    val isAnnouncement: Boolean,
    /** The wrapper's commercial marker (commercial_threads is the marker
     *  of record). Parsed tolerantly: the staging wrapper records the
     *  marker but does not yet serve it on reads — see M2_CLIENT_REPORT —
     *  so this is false until the host emits it, never guessed. */
    val isCommercial: Boolean,
    val likes: Int,
    val replies: Int,
    val createdAt: String
) {
    companion object {
        fun fromJson(o: JSONObject) = ForumThread(
            id = o.optInt("id"),
            categoryId = o.optInt("categoryId"),
            categoryName = o.optString("categoryName"),
            title = o.optString("title"),
            body = o.optString("body"),
            authorName = o.optString("authorName"),
            authorIsFoundingMember = o.optBoolean("authorIsFoundingMember"),
            isPinned = o.optBoolean("isPinned"),
            isPaidPinned = o.optBoolean("isPaidPinned"),
            paidPinEndsAt = if (o.isNull("paidPinEndsAt")) null else o.optString("paidPinEndsAt"),
            canPaidPin = o.optBoolean("canPaidPin"),
            canTip = o.optBoolean("canTip"),
            tipCount = o.optInt("tipCount"),
            isLocked = o.optBoolean("isLocked"),
            isSeed = o.optBoolean("isSeed"),
            isAnnouncement = o.optBoolean("isAnnouncement"),
            isCommercial = o.optBoolean("isCommercial"),
            likes = o.optInt("likes"),
            replies = o.optInt("replies"),
            createdAt = o.optString("createdAt")
        )
    }
}

data class ForumPayload(
    val locked: Boolean,
    val member: Member?,
    val categories: List<Category>,
    val threads: List<ForumThread>,
    /** Whether the CALLER holds prestige, served on the listForum
     *  payload by the M2 host fixes (forumReadTransform). Null when
     *  the host did not serve it — the composer then attempts the
     *  session-free post and lets the gate decide, never charging
     *  toward a VIP-board post on an unknown holder state. */
    val callerPrestige: Boolean? = null
) {
    companion object {
        fun fromJson(o: JSONObject): ForumPayload {
            val cats = o.optJSONArray("categories")
            val threads = o.optJSONArray("threads")
            return ForumPayload(
                locked = o.optBoolean("locked"),
                member = o.optJSONObject("member")?.let { Member.fromJson(it) },
                categories = (0 until (cats?.length() ?: 0)).mapNotNull {
                    cats?.optJSONObject(it)?.let { c -> Category.fromJson(c) }
                },
                threads = (0 until (threads?.length() ?: 0)).mapNotNull {
                    threads?.optJSONObject(it)?.let { t -> ForumThread.fromJson(t) }
                },
                callerPrestige = if (o.has("callerPrestige") && !o.isNull("callerPrestige")) {
                    o.optBoolean("callerPrestige")
                } else {
                    null
                }
            )
        }
    }
}

data class ThreadFull(
    val id: Int,
    val title: String,
    val body: String,
    val authorName: String,
    val authorIsFoundingMember: Boolean,
    val canTip: Boolean,
    val tipCount: Int,
    val categoryName: String,
    val likes: Int,
    val isLocked: Boolean,
    val isSeed: Boolean,
    val isAnnouncement: Boolean,
    val audience: String,
    val createdAt: String
) {
    companion object {
        fun fromJson(o: JSONObject) = ThreadFull(
            id = o.optInt("id"),
            title = o.optString("title"),
            body = o.optString("body"),
            authorName = o.optString("authorName"),
            authorIsFoundingMember = o.optBoolean("authorIsFoundingMember"),
            canTip = o.optBoolean("canTip"),
            tipCount = o.optInt("tipCount"),
            categoryName = o.optString("categoryName"),
            likes = o.optInt("likes"),
            isLocked = o.optBoolean("isLocked"),
            isSeed = o.optBoolean("isSeed"),
            isAnnouncement = o.optBoolean("isAnnouncement"),
            audience = o.optString("audience", "public"),
            createdAt = o.optString("createdAt")
        )
    }
}

data class Reply(
    val id: Int,
    val parentReplyId: Int?,
    val body: String,
    val authorName: String,
    val authorIsFoundingMember: Boolean,
    val canTip: Boolean,
    val tipCount: Int,
    val reputation: Int,
    val likes: Int,
    val createdAt: String
) {
    companion object {
        fun fromJson(o: JSONObject) = Reply(
            id = o.optInt("id"),
            parentReplyId = if (o.isNull("parentReplyId")) null else o.optInt("parentReplyId"),
            body = o.optString("body"),
            authorName = o.optString("authorName"),
            authorIsFoundingMember = o.optBoolean("authorIsFoundingMember"),
            canTip = o.optBoolean("canTip"),
            tipCount = o.optInt("tipCount"),
            reputation = o.optInt("reputation"),
            likes = o.optInt("likes"),
            createdAt = o.optString("createdAt")
        )
    }
}

data class ThreadDetail(
    val thread: ThreadFull,
    val replies: List<Reply>
) {
    companion object {
        /** Returns null when the host answered {ok:false} or the thread
         *  is absent — the caller renders the host's error, not a guess. */
        fun fromJson(o: JSONObject): ThreadDetail? {
            if (!o.optBoolean("ok", false)) return null
            val thread = o.optJSONObject("thread") ?: return null
            val replies = o.optJSONArray("replies")
            return ThreadDetail(
                thread = ThreadFull.fromJson(thread),
                replies = (0 until (replies?.length() ?: 0)).mapNotNull {
                    replies?.optJSONObject(it)?.let { r -> Reply.fromJson(r) }
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// M2 models — Inbox (R10) and Catch-Up (R9).
// Shapes are the staging wrapper's / bundle's served payloads
// (M2_HOST_REPORT + actions.ts listNotifications), parsed tolerantly.
// ---------------------------------------------------------------------------

/** One inbox entry (bundle listNotifications, actions.ts:841). The type
 *  enum is the bundle's: thread_reply / reply_reply / tip / mention /
 *  founder_poll / founding_milestone. Read state is server-held. */
data class NotificationItem(
    val id: Int,
    val type: String,
    val title: String,
    val detail: String,
    val threadId: Int?,
    val read: Boolean,
    val createdAt: String
) {
    companion object {
        fun fromJson(o: JSONObject) = NotificationItem(
            id = o.optInt("id"),
            type = o.optString("type"),
            title = o.optString("title"),
            detail = o.optString("detail"),
            threadId = if (o.isNull("threadId")) null else o.optInt("threadId"),
            read = o.optBoolean("read"),
            createdAt = o.optString("createdAt")
        )
    }
}

data class InboxPayload(
    val unread: Int,
    val items: List<NotificationItem>
) {
    companion object {
        fun fromJson(o: JSONObject): InboxPayload {
            val items = o.optJSONArray("items")
            return InboxPayload(
                unread = o.optInt("unread"),
                items = (0 until (items?.length() ?: 0)).mapNotNull {
                    items?.optJSONObject(it)?.let { n -> NotificationItem.fromJson(n) }
                }
            )
        }
    }
}

data class CatchUpThread(
    val id: Int,
    val title: String,
    val categoryName: String,
    val authorName: String,
    val createdAt: String
) {
    companion object {
        fun fromJson(o: JSONObject) = CatchUpThread(
            id = o.optInt("id"),
            title = o.optString("title"),
            categoryName = o.optString("categoryName", "Forum"),
            authorName = o.optString("authorName", "Member"),
            createdAt = o.optString("createdAt")
        )
    }
}

data class CatchUpReply(
    val id: Int,
    val threadId: Int,
    val threadTitle: String,
    val authorName: String,
    val createdAt: String
) {
    companion object {
        fun fromJson(o: JSONObject) = CatchUpReply(
            id = o.optInt("id"),
            threadId = o.optInt("threadId"),
            threadTitle = o.optString("threadTitle"),
            authorName = o.optString("authorName", "Member"),
            createdAt = o.optString("createdAt")
        )
    }
}

data class CatchUpTip(
    val id: Int,
    val threadId: Int?,
    val replyId: Int?,
    val asset: String,
    /** Base units as a string — the wrapper serves the integer as text. */
    val amountBaseUnits: String,
    /** The host's own display rendering of the amount (M2 host fixes:
     *  derived server-side from the asset's decimals — "12.5" for
     *  12,500,000 SKR base units). Null when the host doesn't serve
     *  it; the Catch-Up then falls back to the raw base units. */
    val displayAmount: String? = null,
    /** The asset decimals the host derived [displayAmount] with. */
    val decimals: Int? = null,
    val senderName: String,
    val createdAt: String
) {
    companion object {
        fun fromJson(o: JSONObject) = CatchUpTip(
            id = o.optInt("id"),
            threadId = if (o.isNull("threadId")) null else o.optInt("threadId"),
            replyId = if (o.isNull("replyId")) null else o.optInt("replyId"),
            asset = o.optString("asset"),
            amountBaseUnits = o.optString("amountBaseUnits"),
            displayAmount = if (o.isNull("displayAmount")) null else o.optString("displayAmount"),
            decimals = if (o.isNull("decimals")) null else o.optInt("decimals"),
            senderName = o.optString("senderName", "Member"),
            createdAt = o.optString("createdAt")
        )
    }

    /** What the Catch-Up renders for the amount: the host's display
     *  figure when served, the raw base units otherwise (P1). */
    val displayAmountOrRaw: String get() = displayAmount ?: amountBaseUnits
}

/** A pin change in the Catch-Up. The host derives exactly two honest
 *  event kinds — "activated" and "expired"; rotation is the client-side
 *  display of the active set, not an event (M2_HOST_REPORT ambiguity 5). */
data class CatchUpPinChange(
    val type: String,
    val pinId: Int,
    val threadId: Int,
    val threadTitle: String?,
    val startsAt: String,
    val endsAt: String
) {
    companion object {
        fun fromJson(o: JSONObject) = CatchUpPinChange(
            type = o.optString("type"),
            pinId = o.optInt("pinId"),
            threadId = o.optInt("threadId"),
            threadTitle = if (o.isNull("threadTitle")) null else o.optString("threadTitle"),
            startsAt = o.optString("startsAt"),
            endsAt = o.optString("endsAt")
        )
    }
}

data class CatchUp(
    /** Epoch ms — the member's server-held last-seen watermark. */
    val watermark: Long,
    /** Epoch ms — this read's server time; the client advances with
     *  exactly this value after display (M2 host contract). */
    val through: Long,
    val hasNews: Boolean,
    val newThreads: List<CatchUpThread>,
    val repliesToMine: List<CatchUpReply>,
    val tipsReceived: List<CatchUpTip>,
    val pinChanges: List<CatchUpPinChange>
) {
    val totalNews: Int
        get() = newThreads.size + repliesToMine.size + tipsReceived.size + pinChanges.size

    companion object {
        fun fromJson(o: JSONObject): CatchUp {
            fun <T> list(key: String, parse: (JSONObject) -> T): List<T> {
                val arr = o.optJSONArray(key)
                return (0 until (arr?.length() ?: 0)).mapNotNull {
                    arr?.optJSONObject(it)?.let(parse)
                }
            }
            return CatchUp(
                watermark = o.optLong("watermark"),
                through = o.optLong("through"),
                hasNews = o.optBoolean("hasNews"),
                newThreads = list("newThreads") { CatchUpThread.fromJson(it) },
                repliesToMine = list("repliesToMine") { CatchUpReply.fromJson(it) },
                tipsReceived = list("tipsReceived") { CatchUpTip.fromJson(it) },
                pinChanges = list("pinChanges") { CatchUpPinChange.fromJson(it) }
            )
        }
    }
}

fun shortWallet(wallet: String?): String {
    if (wallet.isNullOrBlank()) return "—"
    return if (wallet.length <= 10) wallet else "${wallet.take(4)}…${wallet.takeLast(4)}"
}

/** ISO-8601 -> the date portion, for list rows. Display-only. */
fun shortDate(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return iso.substringBefore("T").ifBlank { iso }
}

/** SKR base units (6dp) -> the grouped display form ("1,000 SKR",
 *  "0.5 SKR"). Delegates to the money layer so every surface renders
 *  a served amount the same way. */
fun formatSkr(baseUnits: Long): String =
    com.raaru.orekingdom.util.formatAssetAmount(
        java.math.BigInteger.valueOf(baseUnits),
        com.raaru.orekingdom.util.Assets.SKR
    )
