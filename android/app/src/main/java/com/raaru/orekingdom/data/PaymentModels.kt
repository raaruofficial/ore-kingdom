package com.raaru.orekingdom.data

import com.raaru.orekingdom.util.AssetSpec
import com.raaru.orekingdom.util.Assets
import com.raaru.orekingdom.util.decimalToBaseUnits
import org.json.JSONObject
import java.math.BigInteger

/**
 * Models for the M3 payment surfaces (R1–R6), parsed tolerantly from
 * the host's served shapes: every field the client acts on comes from
 * the response itself — amounts, destinations, references, windows —
 * never from a client-side price table. Where the host does not serve
 * a field the flow needs, the model carries null and the flow halts
 * BEFORE signing with a truthful named state (see M3_CLIENT_REPORT).
 *
 * Shapes: staging wrapper server.ts (PaySessionRow / JoinSessionRow /
 * CommercialSessionRow) and bundle actions (tip / pin checkouts).
 */

private fun jsonBaseUnits(o: JSONObject, key: String): BigInteger? {
    if (!o.has(key) || o.isNull(key)) return null
    return try {
        when (val v = o.get(key)) {
            is Number -> BigInteger.valueOf(v.toLong())
            is String -> try {
                BigInteger(v)
            } catch (e: Exception) {
                null
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

private fun optString(o: JSONObject, vararg keys: String): String? {
    for (k in keys) {
        if (o.has(k) && !o.isNull(k)) {
            val v = o.optString(k, "")
            if (v.isNotEmpty()) return v
        }
    }
    return null
}

/**
 * A wrapper-native payment session (post / join / prestige /
 * commercial share the row shape): the quote IS the session — its
 * amount, destination and reference are the transfer's parameters.
 * Status of record: pending -> verified -> dispatched -> consumed,
 * with expired / forward_applied as terminal siblings (A7–A10).
 */
data class PaySession(
    val id: String,
    val kind: String,
    val asset: AssetSpec?,
    val amountBase: BigInteger,
    val usdCents: Int?,
    val destination: String,
    val reference: String,
    val status: String,
    val payExpiresAt: Long?,
    val consumeExpiresAt: Long?,
    val skrPrice: Int?,
    val prestigeClaimed: Boolean?,
    val resultRef: String?
) {
    companion object {
        fun fromJson(o: JSONObject): PaySession? {
            val id = optString(o, "id") ?: return null
            val assetCode = optString(o, "asset")
            return PaySession(
                id = id,
                kind = o.optString("kind", ""),
                asset = assetCode?.let { Assets.byCode(it) },
                amountBase = jsonBaseUnits(o, "amount") ?: return null,
                usdCents = if (o.has("usdCents") && !o.isNull("usdCents")) o.optInt("usdCents") else null,
                destination = optString(o, "destination") ?: return null,
                reference = optString(o, "reference") ?: return null,
                status = o.optString("status", "pending"),
                payExpiresAt = if (o.has("payExpiresAt") && !o.isNull("payExpiresAt")) o.optLong("payExpiresAt") else null,
                consumeExpiresAt = if (o.has("consumeExpiresAt") && !o.isNull("consumeExpiresAt")) o.optLong("consumeExpiresAt") else null,
                skrPrice = if (o.has("skrPrice") && !o.isNull("skrPrice")) o.optInt("skrPrice") else null,
                prestigeClaimed = if (o.has("prestige") && !o.isNull("prestige")) o.optBoolean("prestige") else null,
                resultRef = optString(o, "resultRef")
            )
        }
    }
}

/** createPostSession answers either a session or an exemption (A12:
 *  a prestige holder's thread, a founder-audience post) — the exempt
 *  answer is a first-class result, never an error. */
sealed interface PostSessionReply {
    data class Exempt(val note: String?, val kind: String, val audience: String) : PostSessionReply
    data class Session(val session: PaySession) : PostSessionReply

    companion object {
        fun fromJson(o: JSONObject): PostSessionReply? {
            if (o.optBoolean("exempt", false)) {
                return Exempt(
                    note = optString(o, "note"),
                    kind = o.optString("kind", ""),
                    audience = o.optString("audience", "public")
                )
            }
            return PaySession.fromJson(o)?.let { Session(it) }
        }
    }
}

/** The tip fee leg as the wrapper injects it into the create response
 *  (staging wrapper server.ts: tip checkout post-dispatch injection).
 *  [reference] and [destination] are parsed tolerantly: the package
 *  (§2.3) designed them served with the injection; the current
 *  staging injection carries amount + usdCents + note only, so until
 *  the host serves the leg's reference the flow halts before signing
 *  (a transfer built without it can never verify — see report). */
data class TipFee(
    val asset: AssetSpec?,
    val amountBase: BigInteger,
    val usdCents: Int,
    val reference: String?,
    val destination: String?,
    val note: String?
) {
    companion object {
        fun fromJson(o: JSONObject): TipFee? {
            val amount = jsonBaseUnits(o, "amount") ?: return null
            return TipFee(
                asset = optString(o, "asset")?.let { Assets.byCode(it) },
                amountBase = amount,
                usdCents = o.optInt("usdCents", 0),
                reference = optString(o, "reference", "referencePubkey", "reference_pubkey"),
                destination = optString(o, "destination", "destinationWallet"),
                note = optString(o, "note")
            )
        }
    }
}

/**
 * A tip checkout (bundle session + the wrapper's fee injection).
 * The bundle serves the tip amount as a DISPLAY decimal string; its
 * own storage derived that string from the session's base units
 * (simulatedQuote), so [tipAmountBase] — derived back here with the
 * exact inverse — is the session's stored amount, not an estimate.
 */
data class TipCheckout(
    val checkoutId: String,
    val asset: AssetSpec,
    val tipAmountDisplay: String,
    val tipAmountBase: BigInteger,
    val amountUsdCents: Int,
    val recipientWallet: String,
    val recipientSkrName: String?,
    val tipReference: String,
    val fee: TipFee?
) {
    /** The review sheet's total debit: tip + fee, both served. Null
     *  when the fee leg was not served at all. */
    fun reviewTotalBase(): BigInteger? = fee?.let { tipAmountBase + it.amountBase }

    companion object {
        fun fromJson(o: JSONObject): TipCheckout? {
            val checkoutId = optString(o, "checkoutId") ?: return null
            val asset = optString(o, "asset")?.let { Assets.byCode(it) } ?: return null
            val display = optString(o, "amount") ?: return null
            val tipBase = try {
                decimalToBaseUnits(display, asset.decimals)
            } catch (e: Exception) {
                return null
            }
            return TipCheckout(
                checkoutId = checkoutId,
                asset = asset,
                tipAmountDisplay = display,
                tipAmountBase = tipBase,
                amountUsdCents = o.optInt("amountUsdCents", 0),
                recipientWallet = optString(o, "recipientWallet") ?: return null,
                recipientSkrName = optString(o, "recipientSkrName"),
                tipReference = optString(o, "referencePubkey") ?: return null,
                fee = if (o.has("platformFee") && !o.isNull("platformFee")) {
                    o.optJSONObject("platformFee")?.let { TipFee.fromJson(it) }
                } else {
                    null
                }
            )
        }
    }
}

/**
 * The double opt-in arming check (tip safety ruling, 2026-11-02):
 * the confirm arms ONLY when (a) the fee leg is fully served —
 * amount AND its own reference key, the two things the two-leg
 * transfer is built from — and (b) the total on the review sheet
 * equals the checkout's stored total (tip + fee, as served). Any
 * missing piece or any mismatch leaves the confirm disarmed; the
 * wallet is never asked to sign a checkout the host cannot verify.
 */
fun tipConfirmArmed(checkout: TipCheckout, sheetTotalBase: BigInteger): Boolean {
    val fee = checkout.fee ?: return false
    if (fee.reference.isNullOrEmpty()) return false
    val stored = checkout.reviewTotalBase() ?: return false
    return stored == sheetTotalBase
}

/**
 * A paid-pin checkout (bundle). [reference] is parsed tolerantly:
 * the handler generates the checkout's reference server-side, but
 * the current response schema does not serve it (see report) — the
 * purchase halts before signing until it is served, exactly like
 * the tip fee leg.
 */
data class PinCheckout(
    val checkoutId: String,
    val asset: AssetSpec,
    val amountDisplay: String,
    val amountBase: BigInteger,
    val amountUsdCents: Int,
    val hours: Int,
    val destinationWallet: String,
    val reference: String?
) {
    val confirmable: Boolean get() = !reference.isNullOrEmpty()

    companion object {
        fun fromJson(o: JSONObject): PinCheckout? {
            val checkoutId = optString(o, "checkoutId") ?: return null
            val asset = optString(o, "asset")?.let { Assets.byCode(it) } ?: return null
            val display = optString(o, "amount") ?: return null
            val base = try {
                decimalToBaseUnits(display, asset.decimals)
            } catch (e: Exception) {
                return null
            }
            return PinCheckout(
                checkoutId = checkoutId,
                asset = asset,
                amountDisplay = display,
                amountBase = base,
                amountUsdCents = o.optInt("amountUsdCents", 0),
                hours = o.optInt("hours", 0),
                destinationWallet = optString(o, "destinationWallet") ?: return null,
                reference = optString(o, "referencePubkey", "reference")
            )
        }
    }
}

/** One spotlight slot as the getPinSlots read serves it (M2 host
 *  fixes): the spotlight renders from this read, not from a payload
 *  derivation. */
sealed interface ServedPinSlot {
    data class Filled(
        val pinId: String?,
        val threadId: String,
        val threadTitle: String,
        val startsAt: Long,
        val endsAt: Long
    ) : ServedPinSlot

    data object Vacant : ServedPinSlot
}

data class PinSlots(
    val cap: Int,
    val slots: List<ServedPinSlot>,
    val activeCount: Int,
    val deferredCount: Int
) {
    companion object {
        fun fromJson(o: JSONObject): PinSlots? {
            val arr = o.optJSONArray("slots") ?: return null
            val slots = ArrayList<ServedPinSlot>()
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                if (s.optString("state") == "filled") {
                    val threadId = optString(s, "threadId") ?: continue
                    slots += ServedPinSlot.Filled(
                        pinId = optString(s, "pinId"),
                        threadId = threadId,
                        threadTitle = s.optString("threadTitle", ""),
                        startsAt = s.optLong("startsAt", 0),
                        endsAt = s.optLong("endsAt", 0)
                    )
                } else {
                    slots += ServedPinSlot.Vacant
                }
            }
            return PinSlots(
                cap = o.optInt("cap", slots.size),
                slots = slots,
                activeCount = o.optInt("activeCount", 0),
                deferredCount = o.optInt("deferredCount", 0)
            )
        }
    }
}

/**
 * A parked payment flow (R4): everything needed to finish a payment
 * after the app closes mid-flow — the session/checkout id, the
 * draft it pays for, and the signature once one exists. Parked
 * BEFORE the completion call is attempted, so an abandoned wallet
 * approval or a killed app never strands a paid action silently.
 */
data class ParkedFlow(
    val kind: String, // post | reply | commercial | join | prestige | tip | pin
    val id: String,
    val wallet: String,
    val signature: String?,
    val label: String,
    val createdAt: Long,
    val title: String? = null,
    val body: String? = null,
    val categoryId: Int? = null,
    val threadId: Int? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind)
        put("id", id)
        put("wallet", wallet)
        signature?.let { put("signature", it) }
        put("label", label)
        put("createdAt", createdAt)
        title?.let { put("title", it) }
        body?.let { put("body", it) }
        categoryId?.let { put("categoryId", it) }
        threadId?.let { put("threadId", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): ParkedFlow? {
            val kind = optString(o, "kind") ?: return null
            val id = optString(o, "id") ?: return null
            val wallet = optString(o, "wallet") ?: return null
            return ParkedFlow(
                kind = kind,
                id = id,
                wallet = wallet,
                signature = optString(o, "signature"),
                label = o.optString("label", kind),
                createdAt = o.optLong("createdAt", 0),
                title = optString(o, "title"),
                body = optString(o, "body"),
                categoryId = if (o.has("categoryId") && !o.isNull("categoryId")) o.optInt("categoryId") else null,
                threadId = if (o.has("threadId") && !o.isNull("threadId")) o.optInt("threadId") else null
            )
        }
    }
}
