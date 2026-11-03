package com.raaru.orekingdom

import com.raaru.orekingdom.data.TipCheckout
import com.raaru.orekingdom.data.TipFee
import com.raaru.orekingdom.data.tipConfirmArmed
import com.raaru.orekingdom.util.Assets
import com.raaru.orekingdom.util.baseUnitsToDecimal
import com.raaru.orekingdom.util.decimalToBaseUnits
import com.raaru.orekingdom.util.formatAssetAmount
import com.raaru.orekingdom.util.formatGrams
import com.raaru.orekingdom.util.formatOreDual
import com.raaru.orekingdom.util.formatUsdCents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * The money layer (util/Money): conversions must be the host's own
 * definitions inverted exactly — the bundle serves a checkout amount
 * as baseUnitsToDecimal(amountBaseUnits) and stores amountBaseUnits;
 * the client derives the base units back with decimalToBaseUnits and
 * pins the round trip here, because those derived units are what get
 * signed.
 */
class MoneyTest {

    @Test
    fun decimalToBaseUnitsVectors() {
        assertEquals(BigInteger("12500000"), decimalToBaseUnits("12.5", 6))
        assertEquals(BigInteger("50250000"), decimalToBaseUnits("50.25", 6))
        assertEquals(BigInteger("25000000000"), decimalToBaseUnits("0.25", 11))
        assertEquals(BigInteger("3000000000"), decimalToBaseUnits("3", 9))
        assertEquals(BigInteger.ONE, decimalToBaseUnits("0.00000000001", 11))
        assertEquals(BigInteger("5000000000"), decimalToBaseUnits("5000", 6))
    }

    @Test
    fun decimalToBaseUnitsTruncatesLikeTheBundle() {
        // The bundle pads/truncates the fraction to the decimals —
        // never rounds. The client's derivation matches by definition.
        assertEquals(BigInteger("1234567"), decimalToBaseUnits("1.23456789", 6))
    }

    @Test
    fun baseUnitsToDecimalTrimsZeros() {
        assertEquals("12.5", baseUnitsToDecimal(BigInteger("12500000"), 6))
        assertEquals("1", baseUnitsToDecimal(BigInteger("1000000"), 6))
        assertEquals("0.00000000001", baseUnitsToDecimal(BigInteger.ONE, 11))
        assertEquals("0", baseUnitsToDecimal(BigInteger.ZERO, 6))
        assertEquals("5000", baseUnitsToDecimal(BigInteger("5000000000"), 6))
    }

    @Test
    fun servedDisplayAmountsRoundTripExactly() {
        // For every asset shape the host serves: display -> base must
        // be the exact inverse of base -> display (the stored amount).
        val cases = listOf(
            Triple("0.05", 9, "50000000"), // a $10 SOL tip quote
            Triple("0.002", 9, "2000000"), // its $1 fee leg in SOL
            Triple("0.25", 11, "25000000000"), // an ORE-denominated leg
            Triple("12.5", 6, "12500000") // an SKR/USDC quote
        )
        for ((display, decimals, base) in cases) {
            assertEquals(BigInteger(base), decimalToBaseUnits(display, decimals))
            assertEquals(display, baseUnitsToDecimal(BigInteger(base), decimals))
        }
    }

    @Test
    fun displayFormatting() {
        assertEquals("5,000 SKR", formatAssetAmount(BigInteger("5000000000"), Assets.SKR))
        assertEquals("0.25 ORE", formatAssetAmount(BigInteger("25000000000"), Assets.ORE))
        assertEquals("25,000,000,000 grams", formatGrams(BigInteger("25000000000")))
        assertEquals(
            "0.25 ORE (25,000,000,000 grams)",
            formatOreDual(BigInteger("25000000000"))
        )
        assertEquals("1 grams", formatGrams(BigInteger.ONE))
    }

    @Test
    fun usdCentsFormatting() {
        assertEquals("$3.50", formatUsdCents(350))
        assertEquals("$50.00", formatUsdCents(5000))
        assertEquals("$1.00", formatUsdCents(100))
        assertEquals("$250.00", formatUsdCents(25000))
        assertEquals("$500.00", formatUsdCents(50000))
        assertEquals("$0.25", formatUsdCents(25))
    }

    // ---------- the double opt-in arming check ----------

    private fun checkout(fee: TipFee?) = TipCheckout(
        checkoutId = "tip_test",
        asset = Assets.SOL,
        tipAmountDisplay = "0.05",
        tipAmountBase = BigInteger("50000000"),
        amountUsdCents = 1000,
        recipientWallet = "2hQv9N6FfDmHiwQXyok8uUJ4i5RNaZ5Y1iau2PJbHWpK",
        recipientSkrName = "author.skr",
        tipReference = "GZzwVPjZ3AhnQznVpZoz14LM6YEBgtjZx7g9XYCXLBgQ",
        fee = fee
    )

    private fun fullFee(reference: String? = "4Nd1mPzQzVVBmQqDwYvYxMQwuPNdN7yqBHnVvS5kA9rT") = TipFee(
        asset = Assets.SOL,
        amountBase = BigInteger("2000000"),
        usdCents = 100,
        reference = reference,
        destination = null,
        note = null
    )

    @Test
    fun confirmArmsWhenSheetTotalEqualsStoredTotal() {
        val c = checkout(fullFee())
        assertEquals(BigInteger("52000000"), c.reviewTotalBase())
        assertTrue(tipConfirmArmed(c, BigInteger("52000000")))
    }

    @Test
    fun confirmStaysDisarmedWhenTheFeeLegIsUnserved() {
        val c = checkout(fee = null)
        assertEquals(null, c.reviewTotalBase())
        assertFalse(tipConfirmArmed(c, BigInteger("50000000")))
    }

    @Test
    fun confirmStaysDisarmedWhenTheFeeReferenceIsUnserved() {
        // The staged host's current shape: the injection carries the
        // fee's amount but not its reference. The two-leg transfer
        // cannot be built verifiably, so the confirm must not arm.
        val c = checkout(fullFee(reference = null))
        assertFalse(tipConfirmArmed(c, BigInteger("52000000")))
    }

    @Test
    fun confirmStaysDisarmedOnATotalMismatch() {
        val c = checkout(fullFee())
        assertFalse(tipConfirmArmed(c, BigInteger("51000000")))
        assertFalse(tipConfirmArmed(c, BigInteger("50000000")))
    }
}
