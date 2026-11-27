package com.raaru.orekingdom.shell

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Clock-seam pins for remainingText (DeepSeek full-spend programme Q-21). */
class SpotlightRemainingTextTest {

    private val now: Instant = Instant.parse("2026-11-27T18:00:00Z")

    private fun endsAt(seconds: Long, nanos: Long = 0L): String =
        OffsetDateTime.ofInstant(now.plusSeconds(seconds).plusNanos(nanos), ZoneOffset.UTC).toString()

    @Test
    fun unparseableInputReturnsNull() {
        assertNull(remainingText("not-a-date", now))
    }

    @Test
    fun endInThePastReturnsNull() {
        assertNull(remainingText(endsAt(-60), now))
    }

    @Test
    fun endExactlyNowReturnsNull() {
        assertNull(remainingText(endsAt(0), now))
    }

    @Test
    fun thirtySecondsRoundsUpToOneMinute() {
        assertEquals("1m left", remainingText(endsAt(30), now))
    }

    @Test
    fun sixtySecondsIsOneMinute() {
        assertEquals("1m left", remainingText(endsAt(60), now))
    }

    @Test
    fun sixtyOneSecondsRoundsUpToTwoMinutes() {
        assertEquals("2m left", remainingText(endsAt(61), now))
    }

    @Test
    fun fiftyNinePointFiveSecondsIsOneMinute() {
        assertEquals("1m left", remainingText(endsAt(59, 500_000_000), now))
    }

    @Test
    fun sixtyPointFiveSecondsIsTwoMinutes() {
        assertEquals("2m left", remainingText(endsAt(60, 500_000_000), now))
    }

    @Test
    fun oneHourExactlyFormatsAsOneHourZeroMinutes() {
        assertEquals("1h 0m left", remainingText(endsAt(3600), now))
    }

    @Test
    fun oneHourSixtyOneSecondsCeilingsToOneHourTwoMinutes() {
        assertEquals("1h 2m left", remainingText(endsAt(3661), now))
    }

    @Test
    fun twoHoursExactlyFormatsAsTwoHoursZeroMinutes() {
        assertEquals("2h 0m left", remainingText(endsAt(7200), now))
    }
}
