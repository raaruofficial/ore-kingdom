package com.raaru.orekingdom.wallet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.suspendCoroutine

/**
 * JVM battery for the KFC-008 claim-settlement race ([raceWithClaim]).
 *
 * The load-bearing fake here is a NON-COOPERATIVE latch (per the estate
 * abandonment-recovery skill): the request deferred suspends in
 * [suspendCoroutine] and never resumes, and cancellation cannot reach it
 * — the exact shape of clientlib's parked transact suspension that made
 * an abandoned sign hang for the full 120 s backstop (CR-4, FAIL x4 of
 * record). A cooperative `delay` fake could NOT grade this fault class:
 * it resumes on cancellation and would mask the hang, so no test below
 * uses one as the request.
 */
class ClaimRaceTest {

    /** A request that never settles and ignores cancellation — the
     *  clientlib abandonment shape. Lives on its own scope so the test
     *  JVM is not held by it; its thread is a daemon. */
    private fun nonCooperativeRequest(): kotlinx.coroutines.Deferred<String> =
        CoroutineScope(Dispatchers.Default).async {
            suspendCoroutine { /* never resumed; cancellation cannot reach it */ }
        }

    @Test
    fun answeredRequestWinsWithItsValue() = runBlocking {
        val request = CompletableDeferred<String>().apply { complete("signature-bytes") }
        val signal = CompletableDeferred<Unit>()
        val outcome = raceWithClaim(request, signal, timeoutMs = 5_000)
        assertTrue(outcome is ClaimRace.Answered)
        assertEquals("signature-bytes", (outcome as ClaimRace.Answered).value)
    }

    @Test
    fun claimSettlesANonCooperativeRequestPromptly() = runBlocking {
        val request = nonCooperativeRequest()
        val signal = CompletableDeferred<Unit>()
        val signaler = launch(Dispatchers.Default) {
            delay(150)
            signal.complete(Unit)
        }
        val start = System.currentTimeMillis()
        val outcome = raceWithClaim(request, signal, timeoutMs = 30_000)
        val elapsed = System.currentTimeMillis() - start
        signaler.join()
        assertTrue("expected Claimed, got $outcome", outcome is ClaimRace.Claimed)
        assertTrue("claim settlement must land in-window, took ${elapsed}ms", elapsed < 10_000)
        // The fault shape itself: the request STILL never settled.
        assertTrue(request.isActive)
    }

    @Test
    fun preCompletedClaimWinsImmediately() = runBlocking {
        val request = nonCooperativeRequest()
        val signal = CompletableDeferred<Unit>().apply { complete(Unit) }
        val start = System.currentTimeMillis()
        val outcome = raceWithClaim(request, signal, timeoutMs = 30_000)
        val elapsed = System.currentTimeMillis() - start
        assertTrue(outcome is ClaimRace.Claimed)
        assertTrue("pre-completed claim must not wait, took ${elapsed}ms", elapsed < 5_000)
    }

    @Test
    fun silenceOnBothSidesTimesOutAtTheBound() = runBlocking {
        val request = nonCooperativeRequest()
        val signal = CompletableDeferred<Unit>()
        val start = System.currentTimeMillis()
        val outcome = raceWithClaim(request, signal, timeoutMs = 300)
        val elapsed = System.currentTimeMillis() - start
        assertTrue("expected TimedOut, got $outcome", outcome is ClaimRace.TimedOut)
        assertTrue("timeout fired early at ${elapsed}ms", elapsed >= 250)
    }

    @Test
    fun claimAfterAnswerDoesNotDisturbTheAnswer() = runBlocking {
        val request = async { delay(50); "answered-first" }
        val signal = CompletableDeferred<Unit>()
        val outcome = raceWithClaim(request, signal, timeoutMs = 5_000)
        signal.complete(Unit) // a late claim against a settled request is a no-op
        assertTrue(outcome is ClaimRace.Answered)
        assertEquals("answered-first", (outcome as ClaimRace.Answered).value)
    }
}
