package com.raaru.orekingdom.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Display pins for formatCountdown (DeepSeek full-spend programme Q-20). */
class ComposerCountdownTest {

    @Test
    fun zeroSecondsFormatsAsZeroZero() {
        assertEquals("0:00", formatCountdown(0))
    }

    @Test
    fun singleDigitSecondsAreZeroPadded() {
        assertEquals("0:09", formatCountdown(9))
    }

    @Test
    fun fiftyNineSecondsStaysSubMinute() {
        assertEquals("0:59", formatCountdown(59))
    }

    @Test
    fun sixtySecondsRollsToOneMinute() {
        assertEquals("1:00", formatCountdown(60))
    }

    @Test
    fun fiveNinetyNineFormatsAsNineFiftyNine() {
        assertEquals("9:59", formatCountdown(599))
    }

    @Test
    fun oneHourStaysInMinutesNoHourRollover() {
        assertEquals("60:00", formatCountdown(3600))
    }

    @Test
    fun negativeSecondsKeepCurrentTruth() {
        assertEquals("0:-1", formatCountdown(-1))
    }
}
