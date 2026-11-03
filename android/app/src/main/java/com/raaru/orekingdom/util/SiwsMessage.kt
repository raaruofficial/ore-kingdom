package com.raaru.orekingdom.util

import java.time.Instant

/**
 * Builds the Sign-In With Solana message the Ore Kingdom host verifies.
 *
 * The host's `authenticateSiws` (bundle actions.ts) runs the
 * wallet-standard `verifySignIn` over the parsed message: it re-derives
 * the canonical message text from the parsed fields
 * (`createSignInMessageText`) and checks the signature against THOSE
 * bytes. So the text built here must be byte-identical to the canonical
 * form — same field order, same separators — or verification fails with
 * no server-side detail. This builder mirrors `createSignInMessageText`
 * from @solana/wallet-standard-util exactly:
 *
 *   ${domain} wants you to sign in with your Solana account:\n
 *   ${address}[\n\n${statement}][\n\nURI: ...\nVersion: ...\n...]
 *
 * There is no server challenge/nonce in this host's contract — sign-in
 * is single-shot (census §2): the client constructs the message, the
 * wallet signs it via signMessage (WalletManager.signMessageRaw), and
 * the host verifies signature + binds the wallet to its .skr name.
 *
 * The statement is the estate's verbatim linkability disclosure — the
 * same words the sign-in screen shows before any wallet action exists,
 * so what the member reads is what the wallet signs.
 */
object SiwsMessage {

    const val DOMAIN = "orekingdom.137-184-143-128.sslip.io"
    const val URI = "https://orekingdom.137-184-143-128.sslip.io"
    const val VERSION = "1"
    const val CHAIN_ID = "mainnet"

    /** Verbatim (listing draft + census item 13). Do not paraphrase. */
    const val DISCLOSURE =
        "Your forum name is your wallet. Everything you do here is " +
            "permanently linkable to your on-chain finances"

    fun buildText(address: String, issuedAt: String = Instant.now().toString()): String {
        val message = StringBuilder()
        message.append("$DOMAIN wants you to sign in with your Solana account:\n")
        message.append(address)
        message.append("\n\n").append(DISCLOSURE)
        val fields = listOf(
            "URI: $URI",
            "Version: $VERSION",
            "Chain ID: $CHAIN_ID",
            "Issued At: $issuedAt"
        )
        message.append("\n\n").append(fields.joinToString("\n"))
        return message.toString()
    }

    fun build(address: String, issuedAt: String = Instant.now().toString()): ByteArray =
        buildText(address, issuedAt).toByteArray(Charsets.UTF_8)
}
