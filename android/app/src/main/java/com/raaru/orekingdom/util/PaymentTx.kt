package com.raaru.orekingdom.util

import java.math.BigInteger

/**
 * Transaction construction for the M3 payment flows (R1–R6), built
 * on the same SolanaTx primitives the Citadel invoice flow shipped
 * with. Pure functions of (payer, asset, destination, amount,
 * reference, blockhash) — unit-tested shape by shape; the wallet
 * signs whatever these produce and the host verifies it.
 *
 * The two shapes of record:
 *  - a SESSION payment (post / commercial / join / prestige): ONE
 *    transfer of the session's exact served amount to the session's
 *    served destination with the session's reference attached.
 *  - a TIP payment (package §2.2): TWO legs in ONE transaction — the
 *    tip to the author's wallet and the $1 platform fee to the Relay —
 *    each transfer carrying its OWN reference key.
 *
 * A token leg is [create the destination's ATA if it does not exist]
 * + [TransferChecked into that ATA] — the host verifier binds the
 * destination delta to the destination wallet's own ATA of the mint,
 * so the transfer must land in the ATA, never in a bare account.
 * A SOL leg is a bare system transfer to the wallet itself (the SOL
 * verifier binds the wallet's lamport gain).
 */
object PaymentTx {

    /** One payment leg as instructions. [amountBase] must be positive
     *  and is carried as a Long after an exact range check — every
     *  ruled price in the kingdom fits (the largest, a $500 tip in
     *  ORE, is 1.25e13 base units). */
    fun legInstructions(
        payer: ByteArray,
        asset: AssetSpec,
        destinationWallet: ByteArray,
        amountBase: BigInteger,
        reference: ByteArray?
    ): List<Instruction> {
        require(amountBase > BigInteger.ZERO) { "a payment leg moves a positive amount" }
        require(amountBase <= BigInteger.valueOf(Long.MAX_VALUE)) { "amount out of range" }
        val amount = amountBase.toLong()
        return if (asset.lamports) {
            listOf(SolanaTx.systemTransfer(payer, destinationWallet, amount, reference))
        } else {
            val mint = Base58.decode(asset.mint)
            val sourceAta = SolanaAddresses.associatedTokenAddress(payer, mint)
            val destinationAta = SolanaAddresses.associatedTokenAddress(destinationWallet, mint)
            listOf(
                SolanaTx.createAssociatedTokenAccountIdempotent(payer, destinationAta, destinationWallet, mint),
                SolanaTx.transferChecked(sourceAta, mint, destinationAta, payer, amount, asset.decimals, reference)
            )
        }
    }

    /** The single-transfer session payment. */
    fun buildSessionPayment(
        payer: ByteArray,
        asset: AssetSpec,
        destinationWallet: ByteArray,
        amountBase: BigInteger,
        reference: ByteArray,
        recentBlockhash: ByteArray
    ): ByteArray {
        val instructions = legInstructions(payer, asset, destinationWallet, amountBase, reference)
        return SolanaTx.serializeUnsigned(SolanaTx.compileMessage(payer, instructions, recentBlockhash))
    }

    /**
     * The two-leg tip transaction: tip leg first, fee leg second, one
     * signature authorizes both (A7 — the fee verification reads the
     * SAME signature's transaction). Each leg carries its own
     * reference key; destinations are the author's wallet (tip) and
     * the fee leg's destination (the Relay).
     */
    fun buildTipPayment(
        payer: ByteArray,
        asset: AssetSpec,
        tipDestinationWallet: ByteArray,
        tipAmountBase: BigInteger,
        tipReference: ByteArray,
        feeDestinationWallet: ByteArray,
        feeAmountBase: BigInteger,
        feeReference: ByteArray,
        recentBlockhash: ByteArray
    ): ByteArray {
        val instructions =
            legInstructions(payer, asset, tipDestinationWallet, tipAmountBase, tipReference) +
                legInstructions(payer, asset, feeDestinationWallet, feeAmountBase, feeReference)
        return SolanaTx.serializeUnsigned(SolanaTx.compileMessage(payer, instructions, recentBlockhash))
    }
}
