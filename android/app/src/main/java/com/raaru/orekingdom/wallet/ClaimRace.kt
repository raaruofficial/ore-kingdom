package com.raaru.orekingdom.wallet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How a wallet request's wait ended (KFC-008 claim settlement).
 *
 * The MWA clientlib suspension under a wallet request is NON-COOPERATIVE:
 * when the user abandons the wallet sheet, cancelling the request does not
 * resume it — it can sit unanswered past every UI window (the CR-4 hang,
 * FAIL x4 of record, PHASE6 §6). The foreground reconciler's state write
 * alone cannot settle the awaiting caller either, for the same reason.
 * So the caller races its request against a CLAIM SIGNAL: when the
 * reconciler claims the abandoned generation, it completes the signal and
 * the caller settles at claim time with exactly one interruption result,
 * instead of parking until the request backstop.
 */
sealed interface ClaimRace<out T> {
    /** The request itself answered inside the window. */
    data class Answered<T>(val value: T) : ClaimRace<T>

    /** The reconciler claimed the generation and signalled first. */
    data object Claimed : ClaimRace<Nothing>

    /** Neither the request nor a claim arrived inside the window. */
    data object TimedOut : ClaimRace<Nothing>
}

/**
 * Race [deferred] against [claimSignal] under a [timeoutMs] bound.
 *
 * Cancellation semantics are deliberately transparent: if the awaiting
 * coroutine is cancelled, or [deferred] itself is cancelled, the
 * CancellationException propagates to the caller exactly as a bare
 * `deferred.await()` would deliver it — the owner of the request decides
 * what cancellation means. A signal that completed before the race starts
 * wins immediately.
 */
suspend fun <T> raceWithClaim(
    deferred: Deferred<T>,
    claimSignal: CompletableDeferred<Unit>,
    timeoutMs: Long
): ClaimRace<T> =
    withTimeoutOrNull(timeoutMs) {
        select<ClaimRace<T>> {
            deferred.onAwait { ClaimRace.Answered(it) }
            claimSignal.onAwait { ClaimRace.Claimed }
        }
    } ?: ClaimRace.TimedOut
