package com.raaru.orekingdom

import com.raaru.orekingdom.util.Assets
import com.raaru.orekingdom.util.Base58
import com.raaru.orekingdom.util.PaymentTx
import com.raaru.orekingdom.util.SolanaAddresses
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * M3 payment construction (R1–R6): the shapes the host verifier keys
 * on, pinned by parsing the compiled transactions back apart — a leg
 * is correct iff its transfer instruction carries the exact amount,
 * lands in the destination wallet's own ATA (token) or the wallet
 * itself (SOL), and rides its own reference as a read-only,
 * non-signer account.
 */
class PaymentTxTest {

    private val payer = Base58.decode("FRShnxX7vi64gtBoA6JPmH9MBuVqtheEQaxA1D17wKdP")
    private val author = Base58.decode("2hQv9N6FfDmHiwQXyok8uUJ4i5RNaZ5Y1iau2PJbHWpK")
    private val relay = Base58.decode("BiuPWZ4kiwJjyptxS5Uetr89wTb19TMdWnA4JyZEr1fK")
    private val tipReference = Base58.decode("GZzwVPjZ3AhnQznVpZoz14LM6YEBgtjZx7g9XYCXLBgQ")
    private val feeReference = Base58.decode("4Nd1mPzQzVVBmQqDwYvYxMQwuPNdN7yqBHnVvS5kA9rT")
    private val blockhash = ByteArray(32) { (it * 7 + 1).toByte() }

    // ---------- a minimal legacy-message parser ----------

    private class ParsedIx(val programId: ByteArray, val accounts: List<ByteArray>, val data: ByteArray)
    private class ParsedTx(
        val keys: List<ByteArray>,
        val numSigners: Int,
        val numReadonlyNonSigners: Int,
        val instructions: List<ParsedIx>
    )

    private fun parseTx(tx: ByteArray): ParsedTx {
        var off = 0
        fun shortvec(): Int {
            var v = 0
            var shift = 0
            while (true) {
                val b = tx[off].toInt() and 0xFF
                off++
                v = v or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return v
        }
        val numSigs = shortvec()
        off += numSigs * 64
        val numSigners = tx[off].toInt() and 0xFF
        val numReadonlyNonSigners = tx[off + 2].toInt() and 0xFF
        off += 3
        val numKeys = shortvec()
        val keys = (0 until numKeys).map {
            val k = tx.copyOfRange(off, off + 32)
            off += 32
            k
        }
        off += 32 // blockhash
        val numIx = shortvec()
        val ixs = (0 until numIx).map {
            val programId = keys[tx[off].toInt() and 0xFF]
            off++
            val nAcc = shortvec()
            val accs = (0 until nAcc).map {
                val a = keys[tx[off].toInt() and 0xFF]
                off++
                a
            }
            val nData = shortvec()
            val data = tx.copyOfRange(off, off + nData)
            off += nData
            ParsedIx(programId, accs, data)
        }
        return ParsedTx(keys, numSigners, numReadonlyNonSigners, ixs)
    }

    private fun keyIndex(tx: ParsedTx, key: ByteArray): Int =
        tx.keys.indexOfFirst { it.contentEquals(key) }

    /** The host's reference predicate: present among the keys, never a
     *  signer, and in the read-only non-signer range of the header. */
    private fun assertReferenceReadonlyNonSigner(tx: ParsedTx, reference: ByteArray) {
        val idx = keyIndex(tx, reference)
        assertTrue("reference key must be present", idx >= 0)
        assertTrue("reference must not be a signer", idx >= tx.numSigners)
        assertTrue(
            "reference must be readonly",
            idx >= tx.keys.size - tx.numReadonlyNonSigners
        )
    }

    private fun le64(data: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun transfers(tx: ParsedTx): List<ParsedIx> =
        tx.instructions.filter {
            it.programId.contentEquals(SolanaAddresses.TOKEN_PROGRAM) &&
                it.data.isNotEmpty() && it.data[0].toInt() == 12
        }

    private fun systemTransfers(tx: ParsedTx): List<ParsedIx> =
        tx.instructions.filter {
            it.programId.contentEquals(SolanaAddresses.SYSTEM_PROGRAM) &&
                it.data.size == 12 && it.data[0].toInt() == 2
        }

    // ---------- the two-leg tip ----------

    @Test
    fun tipTwoLegTokenShape() {
        val oreMint = Base58.decode(Assets.ORE.mint)
        val tipBase = BigInteger("100000000000") // 1 ORE at 11dp
        val feeBase = BigInteger("25000000000") // 0.25 ORE
        val tx = parseTx(
            PaymentTx.buildTipPayment(
                payer, Assets.ORE, author, tipBase, tipReference,
                relay, feeBase, feeReference, blockhash
            )
        )
        // Two ATA creates + exactly two transfers, nothing else.
        assertEquals(4, tx.instructions.size)
        val legs = transfers(tx)
        assertEquals(2, legs.size)

        // Leg 1 — the tip: payer ATA -> author ATA, exact amount,
        // ORE decimals, the TIP reference attached last.
        val tipLeg = legs[0]
        assertEquals(5, tipLeg.accounts.size)
        assertArrayEquals(
            SolanaAddresses.associatedTokenAddress(payer, oreMint), tipLeg.accounts[0]
        )
        assertArrayEquals(oreMint, tipLeg.accounts[1])
        assertArrayEquals(
            SolanaAddresses.associatedTokenAddress(author, oreMint), tipLeg.accounts[2]
        )
        assertArrayEquals(payer, tipLeg.accounts[3])
        assertArrayEquals(tipReference, tipLeg.accounts[4])
        assertEquals(tipBase.toLong(), le64(tipLeg.data, 1))
        assertEquals(11, tipLeg.data[9].toInt())

        // Leg 2 — the fee: payer ATA -> Relay ATA, exact fee amount,
        // the FEE leg's own reference (not the tip's).
        val feeLeg = legs[1]
        assertEquals(5, feeLeg.accounts.size)
        assertArrayEquals(
            SolanaAddresses.associatedTokenAddress(relay, oreMint), feeLeg.accounts[2]
        )
        assertArrayEquals(feeReference, feeLeg.accounts[4])
        assertEquals(feeBase.toLong(), le64(feeLeg.data, 1))

        // Both references ride as readonly non-signers, and they are
        // two distinct keys.
        assertReferenceReadonlyNonSigner(tx, tipReference)
        assertReferenceReadonlyNonSigner(tx, feeReference)
        assertTrue(keyIndex(tx, tipReference) != keyIndex(tx, feeReference))
    }

    @Test
    fun tipTwoLegSolShape() {
        val tipBase = BigInteger("50000000") // 0.05 SOL
        val feeBase = BigInteger("2000000") // 0.002 SOL
        val tx = parseTx(
            PaymentTx.buildTipPayment(
                payer, Assets.SOL, author, tipBase, tipReference,
                relay, feeBase, feeReference, blockhash
            )
        )
        assertEquals(2, tx.instructions.size)
        val legs = systemTransfers(tx)
        assertEquals(2, legs.size)

        // Leg 1: payer -> author wallet itself, exact lamports, the
        // tip reference riding as the 4th account.
        assertArrayEquals(payer, legs[0].accounts[0])
        assertArrayEquals(author, legs[0].accounts[1])
        assertArrayEquals(tipReference, legs[0].accounts[3])
        assertEquals(tipBase.toLong(), le64(legs[0].data, 4))

        // Leg 2: payer -> Relay, exact fee lamports, fee reference.
        assertArrayEquals(relay, legs[1].accounts[1])
        assertArrayEquals(feeReference, legs[1].accounts[3])
        assertEquals(feeBase.toLong(), le64(legs[1].data, 4))

        assertReferenceReadonlyNonSigner(tx, tipReference)
        assertReferenceReadonlyNonSigner(tx, feeReference)
    }

    // ---------- single-leg session payments ----------

    @Test
    fun joinSessionPaymentIsOneExactTransfer() {
        // The join of record: 5,000 SKR = 5,000 * 10^6 base units to
        // the Relay, session reference attached.
        val skrMint = Base58.decode(Assets.SKR.mint)
        val tx = parseTx(
            PaymentTx.buildSessionPayment(
                payer, Assets.SKR, relay, BigInteger("5000000000"),
                tipReference, blockhash
            )
        )
        // ATA create + exactly ONE transfer.
        assertEquals(2, tx.instructions.size)
        val legs = transfers(tx)
        assertEquals(1, legs.size)
        val leg = legs.single()
        assertArrayEquals(
            SolanaAddresses.associatedTokenAddress(relay, skrMint), leg.accounts[2]
        )
        assertArrayEquals(tipReference, leg.accounts[4])
        assertEquals(5_000_000_000L, le64(leg.data, 1))
        assertEquals(6, leg.data[9].toInt())
        assertReferenceReadonlyNonSigner(tx, tipReference)
    }

    @Test
    fun prestigeSessionPaymentIsOneExactTransfer() {
        // 10,000 SKR one-time.
        val tx = parseTx(
            PaymentTx.buildSessionPayment(
                payer, Assets.SKR, relay, BigInteger("10000000000"),
                feeReference, blockhash
            )
        )
        val legs = transfers(tx)
        assertEquals(1, legs.size)
        assertEquals(10_000_000_000L, le64(legs.single().data, 1))
        assertReferenceReadonlyNonSigner(tx, feeReference)
    }

    @Test
    fun solSessionPaymentIsOneSystemTransfer() {
        // A thread fee paid in SOL: one system transfer of the exact
        // lamport amount to the Relay wallet itself, reference riding.
        val tx = parseTx(
            PaymentTx.buildSessionPayment(
                payer, Assets.SOL, relay, BigInteger("8750000"),
                tipReference, blockhash
            )
        )
        assertEquals(1, tx.instructions.size)
        val legs = systemTransfers(tx)
        assertEquals(1, legs.size)
        assertArrayEquals(payer, legs[0].accounts[0])
        assertArrayEquals(relay, legs[0].accounts[1])
        assertArrayEquals(tipReference, legs[0].accounts[3])
        assertEquals(8_750_000L, le64(legs[0].data, 4))
        assertReferenceReadonlyNonSigner(tx, tipReference)
    }

    @Test
    fun usdcSessionPaymentCarriesUsdcDecimals() {
        // A commercial thread paid in USDC ($50 = 50 * 10^6).
        val usdcMint = Base58.decode(Assets.USDC.mint)
        val tx = parseTx(
            PaymentTx.buildSessionPayment(
                payer, Assets.USDC, relay, BigInteger("50000000"),
                feeReference, blockhash
            )
        )
        val legs = transfers(tx)
        assertEquals(1, legs.size)
        assertArrayEquals(usdcMint, legs.single().accounts[1])
        assertArrayEquals(
            SolanaAddresses.associatedTokenAddress(relay, usdcMint),
            legs.single().accounts[2]
        )
        assertEquals(50_000_000L, le64(legs.single().data, 1))
        assertEquals(6, legs.single().data[9].toInt())
        assertReferenceReadonlyNonSigner(tx, feeReference)
    }
}
