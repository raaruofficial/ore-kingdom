package com.raaru.orekingdom.moderation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Display pins for sanctionDurationText (DeepSeek full-spend programme Q-17). */
class SanctionDurationTest {

    @Test
    fun wholeUnitsFormat() {
        assertEquals("1m", sanctionDurationText(60L))
        assertEquals("1h", sanctionDurationText(3600L))
        assertEquals("1d", sanctionDurationText(86400L))
    }

    @Test
    fun mixedUnitsFormatSuppressesSecondsAboveHours() {
        assertEquals("1m 1s", sanctionDurationText(61L))
        assertEquals("1h 1m", sanctionDurationText(3661L))
        assertEquals("1d 1h 1m", sanctionDurationText(90061L))
    }

    @Test
    fun subMinuteAndFallbackFormat() {
        assertEquals("0s", sanctionDurationText(0L))
        assertEquals("45s", sanctionDurationText(45L))
        assertEquals("0s", sanctionDurationText(-5L))
    }

    @Test
    fun boundaryLeftoversFormat() {
        assertEquals("59s", sanctionDurationText(59L))
        assertEquals("59m 59s", sanctionDurationText(3599L))
        assertEquals("23h 59m", sanctionDurationText(86399L))
    }

    @Test
    fun sanctionRungWireLabelPairsAreExact() {
        assertEquals(
            listOf(
                "content_removed" to "Content removed",
                "slowmode" to "Slowmode",
                "mute" to "Mute",
                "suspension" to "Suspension",
                "ban" to "Ban",
            ),
            SanctionRung.values().map { it.wire to it.label },
        )
    }

    @Test
    fun sanctionRungOfReturnsNullForUnknownWire() {
        assertEquals(SanctionRung.Mute, SanctionRung.of("mute"))
        assertNull(SanctionRung.of("bogus"))
    }

    @Test
    fun appealStateOfReturnsNoneForUnknownWire() {
        assertEquals(AppealState.Reduced, AppealState.of("reduced"))
        assertEquals(AppealState.None, AppealState.of("bogus"))
    }
}
