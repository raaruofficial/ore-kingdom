package com.raaru.orekingdom

import com.raaru.orekingdom.util.SiwsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The SIWS text is a wire contract: the host re-derives the canonical
 * message from its parsed fields and verifies the signature against
 * those bytes. These tests pin the exact canonical shape (mirroring
 * @solana/wallet-standard-util's createSignInMessageText) so a casual
 * edit to the builder fails loudly here instead of as "sign-in broken"
 * against the production host.
 */
class SiwsMessageTest {

    @Test
    fun canonicalShape() {
        val text = SiwsMessage.buildText(
            address = "FRShnxX7exampleaddress1111111111111111111",
            issuedAt = "2026-11-02T00:00:00Z"
        )
        val expected = "orekingdom.137-184-143-128.sslip.io wants you to sign in with your Solana account:\n" +
            "FRShnxX7exampleaddress1111111111111111111\n\n" +
            "Your forum name is your wallet. Everything you do here is " +
            "permanently linkable to your on-chain finances\n\n" +
            "URI: https://orekingdom.137-184-143-128.sslip.io\n" +
            "Version: 1\n" +
            "Chain ID: mainnet\n" +
            "Issued At: 2026-11-02T00:00:00Z"
        assertEquals(expected, text)
    }

    @Test
    fun noTrailingNewline() {
        // The parse regex tolerates trailing newlines, but the derived
        // canonical text has none — signed bytes must match it exactly.
        val text = SiwsMessage.buildText("FRShnxX7exampleaddress1111111111111111111", "2026-11-02T00:00:00Z")
        assertFalse(text.endsWith("\n"))
    }

    @Test
    fun disclosureIsVerbatim() {
        assertEquals(
            "Your forum name is your wallet. Everything you do here is " +
                "permanently linkable to your on-chain finances",
            SiwsMessage.DISCLOSURE
        )
    }
}
