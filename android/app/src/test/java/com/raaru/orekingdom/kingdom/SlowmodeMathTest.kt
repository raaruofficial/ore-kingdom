package com.raaru.orekingdom.kingdom

import org.junit.Assert.assertEquals
import org.junit.Test

/** SW2-01 host overlay maths (integration stage 1). */
class SlowmodeMathTest {

    @Test
    fun fullWindowImmediatelyAfterAck() {
        assertEquals(30, SlowmodeMath.remainingSeconds(30, lastAckAtMs = 1_000_000L, nowMs = 1_000_000L))
    }

    @Test
    fun countsDownByWholeSeconds() {
        assertEquals(19, SlowmodeMath.remainingSeconds(30, lastAckAtMs = 0L, nowMs = 11_400L))
    }

    @Test
    fun expiresAtTheBoundary() {
        assertEquals(0, SlowmodeMath.remainingSeconds(30, lastAckAtMs = 0L, nowMs = 30_000L))
        assertEquals(0, SlowmodeMath.remainingSeconds(30, lastAckAtMs = 0L, nowMs = 45_000L))
    }

    @Test
    fun zeroSlowmodeNeverBlocks() {
        assertEquals(0, SlowmodeMath.remainingSeconds(0, lastAckAtMs = 0L, nowMs = 0L))
    }

    @Test
    fun clockSkewFutureAckShowsMoreThanFullWindow() {
        assertEquals(35, SlowmodeMath.remainingSeconds(30, lastAckAtMs = 1_005_000L, nowMs = 1_000_000L))
    }
}
