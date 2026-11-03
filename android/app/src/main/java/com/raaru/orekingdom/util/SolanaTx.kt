package com.raaru.orekingdom.util

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Minimal Solana transaction construction, in-tree on purpose (the same
 * discipline as [Base58]): the payment build needs exactly three things —
 * program-derived addresses (for associated token accounts), the SPL
 * Token instructions for one transfer, and legacy message compilation.
 * No third-party Solana library is pulled in for this.
 *
 * Everything here is pure JVM (SHA-256 + BigInteger only) and unit-tested
 * against independently computed vectors.
 */

/** Ed25519 curve membership — the PDA derivation predicate. A candidate
 *  program address must NOT be a valid curve point. */
object Ed25519 {
    // NB: valueOf(2), not BigInteger.TWO — the field is not present on
    // this app's minSdk (28) Android runtime.
    private val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    private val D: BigInteger =
        BigInteger.valueOf(-121665).mod(P).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)

    fun isOnCurve(bytes: ByteArray): Boolean {
        require(bytes.size == 32) { "ed25519 point encoding must be 32 bytes" }
        // y is the little-endian low 255 bits; the top bit is the x sign.
        val yBytes = bytes.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        val y = BigInteger(1, yBytes.reversedArray())
        if (y >= P) return false
        val y2 = y.multiply(y).mod(P)
        // x² = (y² − 1) / (d·y² + 1)
        val numerator = y2.subtract(BigInteger.ONE).mod(P)
        val denominator = D.multiply(y2).add(BigInteger.ONE).mod(P)
        val x2 = numerator.multiply(denominator.modInverse(P)).mod(P)
        if (x2 == BigInteger.ZERO) return true
        // Legendre symbol: x² is a quadratic residue iff x²^((p−1)/2) == 1.
        return x2.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.TWO), P) == BigInteger.ONE
    }
}

object SolanaAddresses {
    val SYSTEM_PROGRAM: ByteArray = Base58.decode("11111111111111111111111111111111")
    val TOKEN_PROGRAM: ByteArray = Base58.decode("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")
    val ATA_PROGRAM: ByteArray = Base58.decode("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")

    private const val PDA_MARKER = "ProgramDerivedAddress"

    fun createProgramAddress(seeds: List<ByteArray>, programId: ByteArray): ByteArray? {
        val md = MessageDigest.getInstance("SHA-256")
        seeds.forEach { md.update(it) }
        md.update(programId)
        md.update(PDA_MARKER.toByteArray(Charsets.UTF_8))
        val hash = md.digest()
        return if (Ed25519.isOnCurve(hash)) null else hash
    }

    fun findProgramAddress(seeds: List<ByteArray>, programId: ByteArray): ByteArray {
        for (bump in 255 downTo 0) {
            val withBump = seeds + listOf(byteArrayOf(bump.toByte()))
            createProgramAddress(withBump, programId)?.let { return it }
        }
        throw IllegalStateException("no viable program address bump")
    }

    /** The associated token account for (owner, mint) under the classic
     *  SPL Token program. */
    fun associatedTokenAddress(owner: ByteArray, mint: ByteArray): ByteArray =
        findProgramAddress(listOf(owner, TOKEN_PROGRAM, mint), ATA_PROGRAM)
}

data class AccountMeta(val pubkey: ByteArray, val isSigner: Boolean, val isWritable: Boolean) {
    override fun equals(other: Any?): Boolean =
        other is AccountMeta && pubkey.contentEquals(other.pubkey) &&
            isSigner == other.isSigner && isWritable == other.isWritable

    override fun hashCode(): Int = pubkey.contentHashCode()
}

data class Instruction(
    val programId: ByteArray,
    val accounts: List<AccountMeta>,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is Instruction && programId.contentEquals(other.programId) &&
            accounts == other.accounts && data.contentEquals(other.data)

    override fun hashCode(): Int = programId.contentHashCode()
}

object SolanaTx {

    fun shortvec(value: Int): ByteArray {
        require(value >= 0)
        val out = mutableListOf<Byte>()
        var v = value
        while (true) {
            var b = v and 0x7F
            v = v ushr 7
            if (v != 0) b = b or 0x80
            out.add(b.toByte())
            if (v == 0) break
        }
        return out.toByteArray()
    }

    /**
     * Compile a legacy message. Account ordering follows the web3.js
     * convention: fee payer first, then writable signers, readonly
     * signers, writable non-signers, readonly non-signers — insertion
     * order preserved within each class, privileges merged on duplicates.
     */
    fun compileMessage(
        feePayer: ByteArray,
        instructions: List<Instruction>,
        recentBlockhash: ByteArray
    ): ByteArray {
        require(recentBlockhash.size == 32) { "blockhash must be 32 bytes" }
        val merged = LinkedHashMap<String, AccountMeta>()
        fun add(meta: AccountMeta) {
            val key = Base58.encode(meta.pubkey)
            val prev = merged[key]
            merged[key] = if (prev == null) meta else AccountMeta(
                meta.pubkey,
                prev.isSigner || meta.isSigner,
                prev.isWritable || meta.isWritable
            )
        }
        add(AccountMeta(feePayer, isSigner = true, isWritable = true))
        for (ix in instructions) {
            ix.accounts.forEach { add(it) }
            add(AccountMeta(ix.programId, isSigner = false, isWritable = false))
        }
        val all = merged.values.toList()
        val ordered = all.filter { it.isSigner && it.isWritable } +
            all.filter { it.isSigner && !it.isWritable } +
            all.filter { !it.isSigner && it.isWritable } +
            all.filter { !it.isSigner && !it.isWritable }
        val numSigners = ordered.count { it.isSigner }
        val numReadonlySigners = ordered.count { it.isSigner && !it.isWritable }
        val numReadonlyNonSigners = ordered.count { !it.isSigner && !it.isWritable }
        val indexOf = HashMap<String, Int>()
        ordered.forEachIndexed { i, meta -> indexOf[Base58.encode(meta.pubkey)] = i }

        val out = mutableListOf<Byte>()
        fun push(bytes: ByteArray) = bytes.forEach { out.add(it) }
        push(byteArrayOf(numSigners.toByte(), numReadonlySigners.toByte(), numReadonlyNonSigners.toByte()))
        push(shortvec(ordered.size))
        ordered.forEach { push(it.pubkey) }
        push(recentBlockhash)
        push(shortvec(instructions.size))
        for (ix in instructions) {
            out.add(indexOf.getValue(Base58.encode(ix.programId)).toByte())
            push(shortvec(ix.accounts.size))
            ix.accounts.forEach { out.add(indexOf.getValue(Base58.encode(it.pubkey)).toByte()) }
            push(shortvec(ix.data.size))
            push(ix.data)
        }
        return out.toByteArray()
    }

    /** Serialize an unsigned transaction (zero-filled signature slots for
     *  the wallet to fill via signAndSend). */
    fun serializeUnsigned(message: ByteArray): ByteArray {
        val numSigners = message[0].toInt() and 0xFF
        val sigBlock = ByteArray(numSigners * 64)
        return shortvec(numSigners) + sigBlock + message
    }

    // ---------- SPL Token / ATA instructions ----------

    /** createAssociatedTokenAccountIdempotent (discriminator 1): creates
     *  the destination ATA if it does not exist yet, no-ops if it does. */
    fun createAssociatedTokenAccountIdempotent(
        payer: ByteArray,
        ata: ByteArray,
        owner: ByteArray,
        mint: ByteArray
    ): Instruction = Instruction(
        programId = SolanaAddresses.ATA_PROGRAM,
        accounts = listOf(
            AccountMeta(payer, isSigner = true, isWritable = true),
            AccountMeta(ata, isSigner = false, isWritable = true),
            AccountMeta(owner, isSigner = false, isWritable = false),
            AccountMeta(mint, isSigner = false, isWritable = false),
            AccountMeta(SolanaAddresses.SYSTEM_PROGRAM, isSigner = false, isWritable = false),
            AccountMeta(SolanaAddresses.TOKEN_PROGRAM, isSigner = false, isWritable = false)
        ),
        data = byteArrayOf(1)
    )

    /**
     * SPL Token TransferChecked (discriminator 12), with the invoice
     * reference attached as an extra read-only, non-signer account — the
     * Solana Pay convention the host verifier keys on (§17.3 predicate 1).
     */
    fun transferChecked(
        source: ByteArray,
        mint: ByteArray,
        destination: ByteArray,
        owner: ByteArray,
        amountBase: Long,
        decimals: Int,
        reference: ByteArray?
    ): Instruction {
        val accounts = mutableListOf(
            AccountMeta(source, isSigner = false, isWritable = true),
            AccountMeta(mint, isSigner = false, isWritable = false),
            AccountMeta(destination, isSigner = false, isWritable = true),
            AccountMeta(owner, isSigner = true, isWritable = false)
        )
        reference?.let { accounts += AccountMeta(it, isSigner = false, isWritable = false) }
        val data = ByteArray(1 + 8 + 1)
        data[0] = 12
        var v = amountBase
        for (i in 0 until 8) {
            data[1 + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        data[9] = decimals.toByte()
        return Instruction(SolanaAddresses.TOKEN_PROGRAM, accounts, data)
    }

    /**
     * The payment transaction of record for an invoice (packet §7): create
     * the destination's SKR ATA if needed, then transfer exactly
     * [amountBase] of [mint] from the payer's ATA to the invoice
     * destination's ATA with the invoice [reference] attached. Amount,
     * destination, mint and reference all come from the invoice response —
     * nothing money-shaped is hardcoded here except the SKR mint's
     * decimals (packet §1: classic SPL, 6dp, PROVEN).
     */
    fun buildPaymentTransaction(
        payer: ByteArray,
        mint: ByteArray,
        destinationWallet: ByteArray,
        reference: ByteArray,
        amountBase: Long,
        decimals: Int,
        recentBlockhash: ByteArray
    ): ByteArray {
        val sourceAta = SolanaAddresses.associatedTokenAddress(payer, mint)
        val destinationAta = SolanaAddresses.associatedTokenAddress(destinationWallet, mint)
        val instructions = listOf(
            createAssociatedTokenAccountIdempotent(payer, destinationAta, destinationWallet, mint),
            transferChecked(sourceAta, mint, destinationAta, payer, amountBase, decimals, reference)
        )
        return serializeUnsigned(compileMessage(payer, instructions, recentBlockhash))
    }
}
