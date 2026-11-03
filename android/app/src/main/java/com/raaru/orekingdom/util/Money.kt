package com.raaru.orekingdom.util

import java.math.BigInteger
import java.text.NumberFormat
import java.util.Locale

/**
 * Money primitives for the M3 payment flows (R1–R6): the asset table,
 * exact decimal <-> base-unit conversion, and display formatting.
 * Pure JVM (BigInteger only) and unit-tested — every money figure the
 * client shows or signs is computed here, never inline in a screen.
 *
 * The asset table mirrors the host's own tables verbatim (staging
 * wrapper ASSETS, server.ts:606; bundle TRANSFER_ASSETS, payment.ts):
 * SKR classic SPL 6dp, ORE classic SPL 11dp, SOL as lamports 9dp,
 * USDC classic SPL 6dp. Amounts on the wire are base units; display
 * strings derive from them here, server-style (trailing fractional
 * zeros trimmed — the wrapper's formatBaseUnits discipline).
 */
data class AssetSpec(
    val code: String,
    /** The token mint; for SOL this is the wrapped-SOL mint id the host
     *  table carries, but SOL moves as lamports (see [lamports]). */
    val mint: String,
    val decimals: Int,
    val lamports: Boolean
)

object Assets {
    val SKR = AssetSpec("SKR", "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3", 6, lamports = false)
    val ORE = AssetSpec("ORE", "oreoU2P8bN6jkk3jbaiVxYnG1dCXcYxwhwyK9jSybcp", 11, lamports = false)
    val SOL = AssetSpec("SOL", "So11111111111111111111111111111111111111112", 9, lamports = true)
    val USDC = AssetSpec("USDC", "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v", 6, lamports = false)

    /** The full payment menu the wrapper-native sessions quote in
     *  (post / commercial checkouts). */
    val MENU: List<AssetSpec> = listOf(SKR, ORE, SOL, USDC)

    /** The bundle checkouts' menu: createTipCheckout /
     *  createPaidPinCheckout take the bundle's assetSchema, which is
     *  exactly SOL | ORE (bundle actions.ts:11) — the client offers
     *  what the host can accept, nothing wider. */
    val BUNDLE_MENU: List<AssetSpec> = listOf(SOL, ORE)

    fun byCode(code: String): AssetSpec? = MENU.firstOrNull { it.code == code }
}

/** The Relay (HERMES) — the treasury of record every house leg pays
 *  (packet §2.1, ruled 2026-11-01; build-package Part 2 row 15 names
 *  the client-side treasury constant explicitly). Session responses
 *  also serve their destination; this constant is the fee leg's
 *  destination, which the tip create response does not serve. */
const val RELAY_WALLET = "BiuPWZ4kiwJjyptxS5Uetr89wTb19TMdWnA4JyZEr1fK"

/**
 * Decimal string -> base units, exactly as the bundle's own
 * decimalToBaseUnits (subscriptions.ts) defines it: the fraction is
 * padded/truncated to [decimals] digits, never rounded. This is what
 * makes a served display amount (the bundle checkouts serve
 * `amount` as baseUnitsToDecimal(amountBaseUnits)) convert back to
 * the session's exact stored base units.
 */
fun decimalToBaseUnits(value: String, decimals: Int): BigInteger {
    require(Regex("^\\d+(?:\\.\\d+)?$").matches(value)) { "Invalid decimal amount: $value" }
    val parts = value.split(".")
    val whole = parts[0]
    val fraction = if (parts.size > 1) parts[1] else ""
    val padded = (fraction + "0".repeat(decimals)).substring(0, decimals)
    return BigInteger(whole) * BigInteger.TEN.pow(decimals) +
        if (padded.isEmpty()) BigInteger.ZERO else BigInteger(padded)
}

/** Base units -> decimal string, trailing fractional zeros trimmed
 *  (the wrapper's formatBaseUnits shape: 12500000 at 6dp -> "12.5"). */
fun baseUnitsToDecimal(amount: BigInteger, decimals: Int): String {
    val negative = amount < BigInteger.ZERO
    val v = if (negative) amount.negate() else amount
    val base = BigInteger.TEN.pow(decimals)
    val whole = v / base
    val frac = v % base
    val text = if (frac == BigInteger.ZERO) {
        whole.toString()
    } else {
        whole.toString() + "." + frac.toString().padStart(decimals, '0').trimEnd('0')
    }
    return if (negative) "-$text" else text
}

private fun groupWhole(decimal: String): String {
    val parts = decimal.split(".")
    val whole = try {
        NumberFormat.getIntegerInstance(Locale.US).format(parts[0].toLong())
    } catch (e: Exception) {
        parts[0]
    }
    return if (parts.size > 1) "$whole.${parts[1]}" else whole
}

/** "12.5 SKR" / "0.01 ORE" — the display form of a base-unit amount. */
fun formatAssetAmount(amountBase: BigInteger, asset: AssetSpec): String =
    "${groupWhole(baseUnitsToDecimal(amountBase, asset.decimals))} ${asset.code}"

/** ORE's protocol name for its base unit (packet §1.10): 1 ORE =
 *  10^11 grams, so a base-unit count IS the gram count. */
fun formatGrams(amountBase: BigInteger): String =
    groupWhole(baseUnitsToDecimal(amountBase, 0)) + " grams"

/** The dual ORE display the tip safety ruling requires: ORE and
 *  grams side by side, so a grams-sized figure can never be misread
 *  as an ORE-sized one (the unit-confusion case). */
fun formatOreDual(amountBase: BigInteger): String =
    "${formatAssetAmount(amountBase, Assets.ORE)} (${formatGrams(amountBase)})"

/** USD cents -> "$3.50" (the price-of-record rendering). */
fun formatUsdCents(cents: Int): String {
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    return "$sign\$${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}

/**
 * The tip ceiling the client blocks at compose time (tip safety
 * ruling, 2026-11-02 12:16: tip_max_usd_cents, proposed 50000 =
 * $500, owner-set; the wrapper is the guarantee, the client also
 * blocks). The host's config surface does NOT serve a tip maximum —
 * getPublicConfig's response schema (bundle actions.ts:188) carries
 * no such field as of this build — so the client carries the
 * proposed figure of record here until the host serves one.
 */
const val TIP_MAX_USD_CENTS = 50_000

/** The tip floor, enforced wrapper-side at create (OK_TIP_MIN_USD_CENTS,
 *  §2.5) and mirrored here so the compose screen refuses before a
 *  round-trip: $5.00. */
const val TIP_MIN_USD_CENTS = 500
