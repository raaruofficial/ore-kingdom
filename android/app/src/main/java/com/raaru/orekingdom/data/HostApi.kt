package com.raaru.orekingdom.data

import android.util.Base64
import com.raaru.orekingdom.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Client for the Ore Kingdom host wire contract (host/deploy/server.ts):
 * POST {base}/actions with the envelope {action, args, actionCallId};
 * responses are {data} or {error}. Adapted from the Seeker Citadel
 * shell's HostApi — the call pattern (Ok / Refused / Unreachable) is the
 * estate's; the envelope differs (no session token: this host's identity
 * is the SIWS proof itself, and reads take the wallet as an arg).
 *
 * Action arg shapes are re-derived from the bundle source of record
 * (the-claim-build/server/src/actions.ts), per the M0 census — they are
 * NOT Citadel's shapes.
 */
sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Refused(val verdict: String) : ApiResult<Nothing>
    data object Unreachable : ApiResult<Nothing>
}

class HostApi(private val baseUrl: String = BuildConfig.HOST_URL) {

    suspend fun call(action: String, args: JSONObject = JSONObject()): ApiResult<JSONObject> =
        withContext(Dispatchers.IO) {
            val conn = try {
                (URL("$baseUrl/actions").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            } catch (e: Exception) {
                return@withContext ApiResult.Unreachable
            }
            try {
                val envelope = JSONObject()
                    .put("action", action)
                    .put("args", args)
                    .put("actionCallId", UUID.randomUUID().toString())
                conn.outputStream.use {
                    it.write(envelope.toString().toByteArray(StandardCharsets.UTF_8))
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.let {
                    BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText()
                } ?: ""
                val json = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    return@withContext ApiResult.Unreachable
                }
                when {
                    json.has("error") -> ApiResult.Refused(json.optString("error", "internal error"))
                    json.has("data") -> ApiResult.Ok(json.optJSONObject("data") ?: JSONObject())
                    else -> ApiResult.Unreachable
                }
            } catch (e: Exception) {
                ApiResult.Unreachable
            } finally {
                conn.disconnect()
            }
        }

    // ---------- typed wrappers (bundle actions.ts shapes) ----------

    /** The client's config read: prices/thresholds/allowlist broadcast. */
    suspend fun getPublicConfig(): ApiResult<JSONObject> = call("getPublicConfig")

    /**
     * Single-shot SIWS (actions.ts:229): the signed message bytes, the
     * signature, and the account public key, all base64. The host parses
     * the message, verifies the signature, and binds wallet <-> .skr.
     * The result rides in {data} as {ok, wallet, skrName} or
     * {ok:false, error} — callers must read the ok flag (R6 truthfulness).
     */
    suspend fun authenticateSiws(
        wallet: String,
        message: ByteArray,
        signature: ByteArray,
        publicKey: ByteArray
    ): ApiResult<JSONObject> {
        fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return call(
            "authenticateSiws",
            JSONObject()
                .put("wallet", wallet)
                .put("messageBase64", b64(message))
                .put("signatureBase64", b64(signature))
                .put("publicKeyBase64", b64(publicKey))
        )
    }

    /**
     * The forum read (actions.ts:606): one unpaginated payload — member
     * (nullable), categories, all visible public threads in the host's
     * order (pinned first, then updatedAt desc). wallet is optional;
     * cold browsing passes none, exactly as the sealed build behaves.
     *
     * M2: an optional SIWS proof rides along. Reads stay public, but the
     * wrapper releases VIP-board content only to a SIWS-verified
     * prestige holder (M2_HOST_REPORT §4b) — the proof is verified and
     * consumed by the wrapper, never forwarded to the bundle.
     */
    suspend fun listForum(
        wallet: String? = null,
        categoryId: Int? = null,
        search: String? = null,
        siwsIdentity: JSONObject? = null
    ): ApiResult<JSONObject> {
        val args = JSONObject()
        wallet?.let { args.put("wallet", it) }
        categoryId?.let { args.put("categoryId", it) }
        search?.takeIf { it.isNotBlank() }?.let { args.put("search", it) }
        siwsIdentity?.let { args.put("siwsIdentity", it) }
        return call("listForum", args)
    }

    /** Thread + replies (actions.ts:718). wallet is a required arg in
     *  the bundle schema; cold browsing passes the empty string. A SIWS
     *  proof unlocks a VIP-board thread for a verified holder, exactly
     *  as on listForum. */
    suspend fun getThread(
        wallet: String,
        threadId: Int,
        siwsIdentity: JSONObject? = null
    ): ApiResult<JSONObject> {
        val args = JSONObject().put("wallet", wallet).put("threadId", threadId)
        siwsIdentity?.let { args.put("siwsIdentity", it) }
        return call("getThread", args)
    }

    /** Membership state read (actions.ts:460). */
    suspend fun getSubscriptionStatus(wallet: String): ApiResult<JSONObject> =
        call("getSubscriptionStatus", JSONObject().put("wallet", wallet))

    /** R10's read (actions.ts:841): {unread, items[]} with server-held
     *  read state. Wired into the client in M2. */
    suspend fun listNotifications(wallet: String): ApiResult<JSONObject> =
        call("listNotifications", JSONObject().put("wallet", wallet))

    /** Marks all (no ids) or the given ids read (actions.ts:852). */
    suspend fun markNotificationsRead(wallet: String, ids: List<Int>? = null): ApiResult<JSONObject> {
        val args = JSONObject().put("wallet", wallet)
        ids?.let {
            val arr = JSONArray()
            it.forEach { id -> arr.put(id) }
            args.put("ids", arr)
        }
        return call("markNotificationsRead", args)
    }

    // ---------- M2: nav core set + composer (staging wrapper contract) ----------

    /**
     * The B2 identity carriage (wrapper server.ts: the bundle's
     * authenticateSiws field shape, minus wallet — the acting wallet is
     * derived from the proof, never trusted from args). Gated actions
     * and VIP read release both take exactly this object.
     */
    fun siwsIdentity(message: ByteArray, signature: ByteArray, publicKey: ByteArray): JSONObject {
        fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return JSONObject()
            .put("messageBase64", b64(message))
            .put("signatureBase64", b64(signature))
            .put("publicKeyBase64", b64(publicKey))
    }

    /**
     * R8 — the Unanswered Rail (wrapper-native listUnanswered): zero-reply
     * threads computed server-side with listForum's visibility predicate
     * minus the VIP board, longest-waiting first. Entries are
     * listForum-shaped summaries; {threads, total}.
     */
    suspend fun listUnanswered(wallet: String? = null): ApiResult<JSONObject> {
        val args = JSONObject()
        wallet?.let { args.put("wallet", it) }
        return call("listUnanswered", args)
    }

    /**
     * R9 — the Catch-Up read (wrapper-native getCatchUp, SIWS-gated).
     * A pure function of (member, server watermark): the read never
     * moves the watermark; the response's `through` is what the client
     * advances with after display.
     */
    suspend fun getCatchUp(wallet: String, siwsIdentity: JSONObject): ApiResult<JSONObject> =
        call(
            "getCatchUp",
            JSONObject().put("wallet", wallet).put("siwsIdentity", siwsIdentity)
        )

    /** R9 — the only watermark mover (wrapper-native advanceCatchUp,
     *  SIWS-gated): sets the watermark to the read's `through`, forward
     *  only; a repeat advance with the same value is a host no-op. */
    suspend fun advanceCatchUp(
        wallet: String,
        through: Long,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> =
        call(
            "advanceCatchUp",
            JSONObject()
                .put("wallet", wallet)
                .put("through", through)
                .put("siwsIdentity", siwsIdentity)
        )

    /**
     * Composer — createThread (bundle actions.ts:746) through the
     * wrapper's post gate (B2 SIWS-gated; the wrapper re-derives the
     * acting wallet from the proof). The result rides in {data} as
     * {ok, id?, error?} — callers must read the ok flag. Payment-session
     * wiring (the thread fee for non-holders) is M3; a prestige holder's
     * thread passes the gate session-free by the wrapper's own status
     * read, and a VIP-board thread is a createThread into the VIP
     * category.
     */
    suspend fun createThread(
        wallet: String,
        categoryId: Int,
        title: String,
        body: String,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> =
        call(
            "createThread",
            JSONObject()
                .put("wallet", wallet)
                .put("categoryId", categoryId)
                .put("title", title)
                .put("body", body)
                .put("audience", "public")
                .put("siwsIdentity", siwsIdentity)
        )

    /** Composer — createReply (bundle actions.ts:789), same gate
     *  discipline as createThread. {data} carries {ok, error?}. */
    suspend fun createReply(
        wallet: String,
        threadId: Int,
        body: String,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> =
        call(
            "createReply",
            JSONObject()
                .put("wallet", wallet)
                .put("threadId", threadId)
                .put("body", body)
                .put("siwsIdentity", siwsIdentity)
        )
}
