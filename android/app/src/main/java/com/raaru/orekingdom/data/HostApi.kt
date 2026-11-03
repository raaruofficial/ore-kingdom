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
     * {ok, id?, error?} — callers must read the ok flag.
     *
     * M3: the paid path attaches exactly one session id — a
     * [postSessionId] (the thread fee) or a [commercialSessionId]
     * (the $50 commercial thread), never both (the host refuses the
     * pair). The wrapper consumes the id in its gate pre-dispatch and
     * strips it before the bundle call. A prestige holder's thread and
     * a VIP-board thread pass the gate session-free by the wrapper's
     * own status read, so those callers attach nothing.
     */
    suspend fun createThread(
        wallet: String,
        categoryId: Int,
        title: String,
        body: String,
        siwsIdentity: JSONObject,
        postSessionId: String? = null,
        commercialSessionId: String? = null
    ): ApiResult<JSONObject> {
        val args = JSONObject()
            .put("wallet", wallet)
            .put("categoryId", categoryId)
            .put("title", title)
            .put("body", body)
            .put("audience", "public")
            .put("siwsIdentity", siwsIdentity)
        postSessionId?.let { args.put("postSessionId", it) }
        commercialSessionId?.let { args.put("commercialSessionId", it) }
        return call("createThread", args)
    }

    /** Composer — createReply (bundle actions.ts:789), same gate
     *  discipline as createThread; M3 attaches [postSessionId] when
     *  the host's gate charges for the reply. {data} carries
     *  {ok, error?}. */
    suspend fun createReply(
        wallet: String,
        threadId: Int,
        body: String,
        siwsIdentity: JSONObject,
        postSessionId: String? = null
    ): ApiResult<JSONObject> {
        val args = JSONObject()
            .put("wallet", wallet)
            .put("threadId", threadId)
            .put("body", body)
            .put("siwsIdentity", siwsIdentity)
        postSessionId?.let { args.put("postSessionId", it) }
        return call("createReply", args)
    }

    // ---------- M3: payment surfaces (staging wrapper contract) ----------
    //
    // Wrapper-native calls validate their arg objects strictly — the
    // wrappers below send exactly the spec'd keys (staging wrapper
    // server.ts validateWrapperArgs). Every gated call carries the
    // siwsIdentity proof; the host derives the acting wallet from it.
    // Completion answers ride in {data} with an ok flag the caller
    // MUST read (R6): forward-apply and pending states are HTTP-200
    // {ok:false} answers, not transport errors.

    /** Post fee session (wrapper-native createPostSession). Answers
     *  either a session row or {exempt:true, note} — see
     *  [PostSessionReply]. */
    suspend fun createPostSession(
        wallet: String,
        kind: String,
        audience: String,
        asset: String?,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> {
        val args = JSONObject()
            .put("wallet", wallet)
            .put("kind", kind)
            .put("audience", audience)
            .put("siwsIdentity", siwsIdentity)
        asset?.let { args.put("asset", it) }
        return call("createPostSession", args)
    }

    /** The session read behind resume-after-abandon (R4). */
    suspend fun getPostSession(
        wallet: String,
        sessionId: String?,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> {
        val args = JSONObject().put("wallet", wallet).put("siwsIdentity", siwsIdentity)
        sessionId?.let { args.put("sessionId", it) }
        return call("getPostSession", args)
    }

    /** The join gate session (wrapper-native createJoinSession): the
     *  single priced door into membership — 5,000 SKR, the price of
     *  record, served by the session itself. */
    suspend fun createJoinSession(
        wallet: String,
        displayName: String?,
        skrName: String?,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> {
        val args = JSONObject().put("wallet", wallet).put("siwsIdentity", siwsIdentity)
        displayName?.takeIf { it.isNotBlank() }?.let { args.put("displayName", it) }
        skrName?.takeIf { it.isNotBlank() }?.let { args.put("skrName", it) }
        return call("createJoinSession", args)
    }

    /** Join completion verifies BY REFERENCE (no signature is
     *  presented): the host looks up the finalized transfer carrying
     *  the session's reference. {data} may be {ok:false,
     *  forwardApplied:true} — read the flags. */
    suspend fun completeJoinSession(
        wallet: String,
        sessionId: String,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> =
        call(
            "completeJoinSession",
            JSONObject()
                .put("wallet", wallet)
                .put("sessionId", sessionId)
                .put("siwsIdentity", siwsIdentity)
        )

    suspend fun getJoinSession(
        wallet: String,
        sessionId: String?,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> {
        val args = JSONObject().put("wallet", wallet).put("siwsIdentity", siwsIdentity)
        sessionId?.let { args.put("sessionId", it) }
        return call("getJoinSession", args)
    }

    /** Prestige (wrapper-native): 10,000 SKR one-time, 100 places. */
    suspend fun createPrestigeSession(
        wallet: String,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> =
        call(
            "createPrestigeSession",
            JSONObject().put("wallet", wallet).put("siwsIdentity", siwsIdentity)
        )

    suspend fun completePrestigeSession(
        wallet: String,
        sessionId: String,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> =
        call(
            "completePrestigeSession",
            JSONObject()
                .put("wallet", wallet)
                .put("sessionId", sessionId)
                .put("siwsIdentity", siwsIdentity)
        )

    /** The caller's prestige state read: {prestige, sessions[]} — the
     *  holder flag the prestige surface renders from. */
    suspend fun getPrestigeSession(
        wallet: String,
        sessionId: String?,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> {
        val args = JSONObject().put("wallet", wallet).put("siwsIdentity", siwsIdentity)
        sessionId?.let { args.put("sessionId", it) }
        return call("getPrestigeSession", args)
    }

    /** Commercial thread session (wrapper-native): $50 flat instead
     *  of the thread fee, labeled commercial on reads. */
    suspend fun createCommercialSession(
        wallet: String,
        asset: String?,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> {
        val args = JSONObject().put("wallet", wallet).put("siwsIdentity", siwsIdentity)
        asset?.let { args.put("asset", it) }
        return call("createCommercialSession", args)
    }

    suspend fun getCommercialSession(
        wallet: String,
        sessionId: String?,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> {
        val args = JSONObject().put("wallet", wallet).put("siwsIdentity", siwsIdentity)
        sessionId?.let { args.put("sessionId", it) }
        return call("getCommercialSession", args)
    }

    /**
     * Tip checkout (bundle createTipCheckout, SIWS-gated): USD-
     * denominated, asset SOL | ORE only (the bundle's assetSchema).
     * The wrapper injects the platform fee leg ({platformFee}) into
     * the response — the second leg of the two-leg transaction.
     */
    suspend fun createTipCheckout(
        wallet: String,
        asset: String,
        threadId: Int?,
        replyId: Int?,
        amountUsdCents: Int,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> {
        val args = JSONObject()
            .put("wallet", wallet)
            .put("asset", asset)
            .put("amountUsdCents", amountUsdCents)
            .put("siwsIdentity", siwsIdentity)
        threadId?.let { args.put("threadId", it) }
        replyId?.let { args.put("replyId", it) }
        return call("createTipCheckout", args)
    }

    /** Tip completion presents the two-leg transaction's signature;
     *  the wrapper verifies the fee leg in it and the bundle the tip
     *  leg (A7). Idempotent on an already-verified checkout. */
    suspend fun completeTipCheckout(
        wallet: String,
        checkoutId: String,
        signature: String?,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> {
        val args = JSONObject()
            .put("wallet", wallet)
            .put("checkoutId", checkoutId)
            .put("siwsIdentity", siwsIdentity)
        signature?.let { args.put("signature", it) }
        return call("completeTipCheckout", args)
    }

    /** The A7 fallback settle: pays an OUTSTANDING fee leg (one whose
     *  tip landed but whose fee never verified) so the member can tip
     *  again. The signature must carry the fee leg alone. */
    suspend fun payOutstandingTipFee(
        wallet: String,
        checkoutId: String,
        signature: String,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> =
        call(
            "payOutstandingTipFee",
            JSONObject()
                .put("wallet", wallet)
                .put("checkoutId", checkoutId)
                .put("signature", signature)
                .put("siwsIdentity", siwsIdentity)
        )

    /** Paid pin checkout (bundle createPaidPinCheckout, SIWS-gated):
     *  own threads only, asset SOL | ORE. Refused at create when both
     *  slots are spoken for — nothing is charged. */
    suspend fun createPaidPinCheckout(
        wallet: String,
        threadId: Int,
        asset: String,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> =
        call(
            "createPaidPinCheckout",
            JSONObject()
                .put("wallet", wallet)
                .put("threadId", threadId)
                .put("asset", asset)
                .put("siwsIdentity", siwsIdentity)
        )

    /** Pin completion presents the payment signature; the wrapper may
     *  answer {pinDeferred:true} when both slots filled between
     *  create and completion — the pin activates when a slot frees. */
    suspend fun completePaidPinCheckout(
        wallet: String,
        checkoutId: String,
        signature: String,
        siwsIdentity: JSONObject
    ): ApiResult<JSONObject> =
        call(
            "completePaidPinCheckout",
            JSONObject()
                .put("wallet", wallet)
                .put("checkoutId", checkoutId)
                .put("signature", signature)
                .put("siwsIdentity", siwsIdentity)
        )

    /** The spotlight's slot-state read (wrapper-native getPinSlots):
     *  cap, per-slot filled/vacant state, deferred count. Ungated —
     *  the wallet is optional context. */
    suspend fun getPinSlots(wallet: String? = null): ApiResult<JSONObject> {
        val args = JSONObject()
        wallet?.let { args.put("wallet", it) }
        return call("getPinSlots", args)
    }
}
