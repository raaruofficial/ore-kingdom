package com.raaru.orekingdom

import com.raaru.orekingdom.util.Base58
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import kotlin.random.Random

/**
 * KFC-006 seal-time instrument: known-vector codec tests. The estate's
 * hand-rolled Base58 once collapsed every multi-byte value to a single
 * character (inverted digit-contribution condition) — invisible until a
 * wallet-present runtime leg displayed the connected address as "E".
 * These vectors fail on that fault shape by construction, and the
 * BigInteger reference cross-check leaves no shared-implementation hole.
 */
class Base58Test {

    private fun referenceEncode(input: ByteArray): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var zeros = 0
        while (zeros < input.size && input[zeros] == 0.toByte()) zeros++
        if (zeros == input.size) return "1".repeat(zeros)
        var n = BigInteger(1, input.copyOfRange(zeros, input.size))
        val sb = StringBuilder()
        val fiftyEight = BigInteger.valueOf(58)
        while (n > BigInteger.ZERO) {
            val (q, r) = n.divideAndRemainder(fiftyEight)
            sb.append(alphabet[r.toInt()])
            n = q
        }
        return "1".repeat(zeros) + sb.reversed().toString()
    }

    @Test
    fun knownAddressesRoundTripExactly() {
        // Real addresses/mints from the estate record.
        val vectors = listOf(
            "FRShnxX7vi64gtBoA6JPmH9MBuVqtheEQaxA1D17wKdP", // raaru.skr (Phase 0 leg 1)
            "4nd3bLiGTDx69v9nHZJDa9JmQwXviU6j2vX6qKTFcxk8", // a real SGT mint
            "GT22s89nU4iWFkNXj1Bw6uYhJJWDRPpShHt4Bk8f99Te", // SGT group address
            "WGSEgS1wRKJspT4hN8Rw947nX936YdhHRbuqftue9mu", // mock-wallet key, KFC-006 cure vector
            "9ooWrK7RfQLc61f3LxFkKLqN278CkZXWARiMSB4QJNPJ"  // TITHE rig key
        )
        for (v in vectors) {
            val bytes = Base58.decode(v)
            assertEquals("decode length for $v", 32, bytes.size)
            assertEquals("round-trip for $v", v, Base58.encode(bytes))
        }
    }

    @Test
    fun edgeCases() {
        assertEquals("", Base58.encode(ByteArray(0)))
        assertEquals("1", Base58.encode(byteArrayOf(0)))
        assertEquals("11111111111111111111111111111111", Base58.encode(ByteArray(32)))
        assertArrayEquals(ByteArray(32), Base58.decode("11111111111111111111111111111111"))
        // A single non-zero byte must never collapse to one wrong char.
        assertEquals("2", Base58.encode(byteArrayOf(1)))
        assertEquals("z", Base58.encode(byteArrayOf(57)))
    }

    @Test
    fun crossCheckAgainstBigIntegerReference() {
        val rnd = Random(20261030)
        repeat(300) { i ->
            val len = when (i % 4) {
                0 -> 32 // pubkey size
                1 -> 64 // signature size
                2 -> rnd.nextInt(1, 20)
                else -> rnd.nextInt(1, 80)
            }
            val bytes = rnd.nextBytes(len)
            assertEquals(
                "encode mismatch at iter $i len $len",
                referenceEncode(bytes),
                Base58.encode(bytes)
            )
            assertArrayEquals(
                "decode round-trip mismatch at iter $i len $len",
                bytes,
                Base58.decode(Base58.encode(bytes))
            )
        }
    }
}
