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
    val isFounder: Boolean,
    val isFoundingMember: Boolean,
    val foundingNumber: Int?,
    val foundingTranche: String?
) {
    companion object {
        fun fromJson(o: JSONObject) = Member(
            id = o.optInt("id"),
            wallet = o.optString("wallet"),
            displayName = o.optString("displayName"),
            skrName = o.optString("skrName"),
            reputation = o.optInt("reputation"),
            tier = o.optString("tier"),
            isFounder = o.optBoolean("isFounder"),
            isFoundingMember = o.optBoolean("isFoundingMember"),
            foundingNumber = if (o.isNull("foundingNumber")) null else o.optInt("foundingNumber"),
            foundingTranche = if (o.isNull("foundingTranche")) null else o.optString("foundingTranche")
        )
    }
}

data class Category(
    val id: Int,
    val slug: String,
    val name: String,
    val description: String,
    val count: Int
) {
    companion object {
        fun fromJson(o: JSONObject) = Category(
            id = o.optInt("id"),
            slug = o.optString("slug"),
            name = o.optString("name"),
            description = o.optString("description"),
            count = o.optInt("count")
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
    val authorIsFounder: Boolean,
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
            authorIsFounder = o.optBoolean("authorIsFounder"),
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
    val threads: List<ForumThread>
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
    val authorIsFounder: Boolean,
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
            authorIsFounder = o.optBoolean("authorIsFounder"),
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
    val authorIsFounder: Boolean,
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
            authorIsFounder = o.optBoolean("authorIsFounder"),
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

fun shortWallet(wallet: String?): String {
    if (wallet.isNullOrBlank()) return "—"
    return if (wallet.length <= 10) wallet else "${wallet.take(4)}…${wallet.takeLast(4)}"
}

/** ISO-8601 -> the date portion, for list rows. Display-only. */
fun shortDate(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    return iso.substringBefore("T").ifBlank { iso }
}
