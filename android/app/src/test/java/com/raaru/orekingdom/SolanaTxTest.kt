package com.raaru.orekingdom

import com.raaru.orekingdom.data.formatSkr
import com.raaru.orekingdom.util.Base58
import com.raaru.orekingdom.util.SolanaAddresses
import com.raaru.orekingdom.util.SolanaTx
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Payment transaction construction (§17). The ATA vectors were computed
 * independently of this implementation (a second, Python implementation
 * of the PDA derivation with its own ed25519 curve math) — the two
 * agreeing is the vector.
 */
class SolanaTxTest {

    private val skrMint = Base58.decode("SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3")
    private val receivingWallet = Base58.decode("2hQv9N6FfDmHiwQXyok8uUJ4i5RNaZ5Y1iau2PJbHWpK")
    private val payerWallet = Base58.decode("FRShnxX7vi64gtBoA6JPmH9MBuVqtheEQaxA1D17wKdP")

    @Test
    fun shortvecVectors() {
        assertArrayEquals(byteArrayOf(0), SolanaTx.shortvec(0))
        assertArrayEquals(byteArrayOf(1), SolanaTx.shortvec(1))
        assertArrayEquals(byteArrayOf(127), SolanaTx.shortvec(127))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 1), SolanaTx.shortvec(128))
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 2), SolanaTx.shortvec(300))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x80.toByte(), 1), SolanaTx.shortvec(16384))
    }

    @Test
    fun ataDerivationMatchesIndependentVectors() {
        assertEquals(
            "4kWi7XenM4kgqjRfexzyKHJt2ABv4kzAHoEJBdNHCvP4",
            Base58.encode(SolanaAddresses.associatedTokenAddress(receivingWallet, skrMint))
        )
        assertEquals(
            "AcimD3aRgE5GfCV49B2Auy9z35Vb8qaT3ML5G8Lmyj7v",
            Base58.encode(SolanaAddresses.associatedTokenAddress(payerWallet, skrMint))
        )
    }

    @Test
    fun realPubkeysAreOnCurveDerivedAddressesAreNot() {
        assertTrue(com.raaru.orekingdom.util.Ed25519.isOnCurve(payerWallet))
        assertTrue(com.raaru.orekingdom.util.Ed25519.isOnCurve(receivingWallet))
        assertFalse(
            com.raaru.orekingdom.util.Ed25519.isOnCurve(
                SolanaAddresses.associatedTokenAddress(payerWallet, skrMint)
            )
        )
    }

    @Test
    fun transferCheckedDataLayout() {
        val ix = SolanaTx.transferChecked(
            source = payerWallet,
            mint = skrMint,
            destination = receivingWallet,
            owner = payerWallet,
            amountBase = 1_000_000_000L,
            decimals = 6,
            reference = receivingWallet
        )
        // discriminator 12, u64 LE amount (1e9 = 0x3B9ACA00), decimals 6
        assertArrayEquals(
            byteArrayOf(12, 0x00, 0xCA.toByte(), 0x9A.toByte(), 0x3B, 0, 0, 0, 0, 6),
            ix.data
        )
        assertEquals(5, ix.accounts.size)
        assertTrue(ix.accounts.last().pubkey.contentEquals(receivingWallet))
        assertFalse(ix.accounts.last().isSigner)
        assertFalse(ix.accounts.last().isWritable)
    }

    @Test
    fun paymentTransactionShape() {
        val reference = Base58.decode("GZzwVPjZ3AhnQznVpZoz14LM6YEBgtjZx7g9XYCXLBgQ")
        val blockhash = ByteArray(32) { it.toByte() }
        val tx = SolanaTx.buildPaymentTransaction(
            payer = payerWallet,
            mint = skrMint,
            destinationWallet = receivingWallet,
            reference = reference,
            amountBase = 1_000_000_000L,
            decimals = 6,
            recentBlockhash = blockhash
        )
        // One signature slot (the payer), zero-filled.
        assertEquals(1, tx[0].toInt())
        assertTrue(tx.slice(1..64).all { it == 0.toByte() })
        val msg = tx.copyOfRange(65, tx.size)
        // Header: 1 signer, 0 readonly signers, 6 readonly non-signers.
        assertEquals(1, msg[0].toInt())
        assertEquals(0, msg[1].toInt())
        assertEquals(6, msg[2].toInt())
        assertEquals(9, msg[3].toInt())
        fun keyAt(i: Int): ByteArray = msg.copyOfRange(4 + i * 32, 4 + (i + 1) * 32)
        val destAta = SolanaAddresses.associatedTokenAddress(receivingWallet, skrMint)
        val srcAta = SolanaAddresses.associatedTokenAddress(payerWallet, skrMint)
        // Key order: payer, dest ATA, source ATA, then readonly
        // non-signers in first-seen order, reference last.
        assertArrayEquals(payerWallet, keyAt(0))
        assertArrayEquals(destAta, keyAt(1))
        assertArrayEquals(srcAta, keyAt(2))
        assertArrayEquals(receivingWallet, keyAt(3))
        assertArrayEquals(skrMint, keyAt(4))
        assertArrayEquals(SolanaAddresses.SYSTEM_PROGRAM, keyAt(5))
        assertArrayEquals(SolanaAddresses.TOKEN_PROGRAM, keyAt(6))
        assertArrayEquals(SolanaAddresses.ATA_PROGRAM, keyAt(7))
        assertArrayEquals(reference, keyAt(8))
        // The reference is a readonly non-signer (index >= header cut).
        // Blockhash follows the keys.
        val afterKeys = 4 + 9 * 32
        assertArrayEquals(blockhash, msg.copyOfRange(afterKeys, afterKeys + 32))
        // Two instructions.
        assertEquals(2, msg[afterKeys + 32].toInt())
        // Instruction 2 is the transfer: program = Token (index 6), five
        // accounts [source ATA 2, mint 4, dest ATA 1, owner 0, reference 8],
        // and the reference index sits in the readonly non-signer range
        // (keys 3..8 per the header) — present, read-only, non-signer.
        val ix1Len = 1 + 1 + 6 + 1 + 1 // program, count, 6 accounts, data len, 1 data byte
        val ix2 = afterKeys + 32 + 1 + ix1Len
        assertEquals(6, msg[ix2].toInt())
        assertEquals(5, msg[ix2 + 1].toInt())
        val ixAccounts = (0 until 5).map { msg[ix2 + 2 + it].toInt() }
        assertEquals(listOf(2, 4, 1, 0, 8), ixAccounts)
        val referenceIndex = ixAccounts.last()
        assertTrue(referenceIndex >= 9 - 6) // readonly non-signer range start
        assertTrue(referenceIndex >= 1) // not a signer
        // Transfer data follows: 10 bytes, exact amount inside.
        val dataStart = ix2 + 2 + 5 + 1
        assertEquals(12, msg[dataStart].toInt())
        assertEquals(0x3B, msg[dataStart + 4].toInt() and 0xFF)
    }

    @Test
    fun formatSkrDisplay() {
        assertEquals("1,000 SKR", formatSkr(1_000_000_000L))
        assertEquals("1 SKR", formatSkr(1_000_000L))
        assertEquals("0.5 SKR", formatSkr(500_000L))
    }
}
