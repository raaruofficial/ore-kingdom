package com.raaru.orekingdom.wallet

import android.net.Uri
import android.util.Log
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.DefaultTransactionParams
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.successPayload
import com.raaru.orekingdom.util.Base58
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * UI-facing wallet state. Observed by Compose screens via [uiState].
 */
sealed interface WalletUiState {
    data object Disconnected : WalletUiState
    data object Working : WalletUiState
    data class Connected(val publicKeyBase58: String) : WalletUiState
    data class Error(val message: String) : WalletUiState
}

/**
 * Owns the Solana Mobile Wallet Adapter session for Ore Kingdom.
 * Ported from the Seeker Citadel client (the estate hardened shell of
 * record) with identity re-pointed; the fault-cure shapes below are
 * Citadel's inheritance notes and stand unchanged.
 *
 * INHERITANCE (Brief §3): this is the estate hardened shell of record's
 * wallet layer — HAUNT v0.1.19 (haunt-lane/repo, commit 2ff0500) — ported
 * shape-for-shape, because its fault cures are runtime-proven on that
 * binary and must be RE-PROVEN on this one (Phase 6):
 * - KFC-001: the single [ActivityResultSender] is constructed in
 *   MainActivity.onCreate and injected; this class never constructs one.
 * - KFC-005: [ConnectionIdentity] iconUri is RELATIVE ("favicon.ico"),
 *   resolved against identityUri; an absolute iconUri throws on every
 *   transact with a wallet present and connect can never complete.
 * - KFC-006: address/signature strings come from the cured in-tree
 *   [Base58] codec (known-vector unit tests in the instruments lane).
 * - KFC-007: the connected pubkey is captured into
 *   [sessionPublicKeyBase58] at connect time; state restore reads that
 *   field, never a getter derived from live UI state.
 * - KFC-008 (combined v0.1.19 shape): generation-scoped foreground
 *   reconciliation (the reconciler may resolve ONLY the generation
 *   pending at backgrounding, snapshotted by [onAppBackgrounded] from
 *   MainActivity.onStop; the post-interruption refire loop's generation
 *   is exempt outright) + the hardened refire loop (3 attempts x 35 s
 *   slots; a SLOW outcome >25 s is the swallowed-intent signature and
 *   refires; fast definitive answers end the loop) + adapter reset on
 *   interruption + the 120 s request backstop.
 * - KFC-008 claim settlement (added after the CR-4 diagnostic, FAIL x4
 *   of record — PHASE6 §6): the v0.1.19 reconciler writes STATE at
 *   claim time, but the sign path's awaiting caller sits behind
 *   `deferred.await()` over clientlib's non-cooperative suspension, so
 *   a state write never settles it and an abandoned sign parked the
 *   caller for the full backstop. The reconciler now also completes
 *   the claimed request's claim signal at claim time, and
 *   [runWalletRequest] races its deferred against that signal
 *   ([raceWithClaim]) so the caller receives exactly one failure
 *   result at claim time, on BOTH paths (connect carries the same
 *   latent shape and is settled symmetrically). The sign-side follow
 *   began minimal (claim-time adapter reset only); the re-proof
 *   battery then SHOWED the retry swallow (follow-up transact fired,
 *   zero establishments, zombie sheet consuming the approval — x3
 *   wire-identical), triggering the pre-authorized Part 2 FULL: a
 *   bounded exactly-one re-issue gate on the follow-up sign, keyed
 *   on attempt 1 returning with zero session establishments (see
 *   [performSignMessage]).
 *
 * The forum's wallet operations are connect and sign-message (the SIWS
 * sign-in signature) — plus, since v0.2.0, exactly ONE transaction path:
 * sign-and-send of a client-built payment transaction for a wrapper
 * session (the v0.1.4 flip's R2 transfer construction). The
 * send path runs under this same rig and under the KFC-008 bounded gate
 * in its FULL form: a follow-up send whose first attempt returned in
 * failure with zero session establishments is re-issued exactly once;
 * any other outcome is final. The gate was specified for sign-message;
 * it is applied to the send unchanged in shape, because the zombie
 * swallow it cures is a property of the wallet session, not of the
 * payload — and a payment is the payload that can least afford a
 * duplicate. No other transaction shape exists in this app.
 */
class WalletManager {

    @Volatile
    private var walletAdapter: MobileWalletAdapter = newWalletAdapter()

    private fun newWalletAdapter(): MobileWalletAdapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = Uri.parse("https://github.com/raaruofficial/ore-kingdom"),
            iconUri = Uri.parse("favicon.ico"), // relative — resolves against identityUri (KFC-005)
            identityName = "Ore Kingdom"
        )
    )

    /** Set when a wallet request has been resolved as interrupted. */
    @Volatile
    private var lastRequestInterrupted: Boolean = false

    /** Discard the current adapter (and its zombie association) for a fresh
     *  instance; the MWA auth token is carried across the swap. */
    @Synchronized
    private fun resetWalletAdapter() {
        val old = walletAdapter
        walletAdapter = newWalletAdapter().also { it.authToken = old.authToken }
    }

    private val _uiState = MutableStateFlow<WalletUiState>(WalletUiState.Disconnected)
    val uiState: StateFlow<WalletUiState> = _uiState.asStateFlow()

    val publicKeyBase58: String?
        get() = (_uiState.value as? WalletUiState.Connected)?.publicKeyBase58

    val isConnected: Boolean
        get() = _uiState.value is WalletUiState.Connected

    /** KFC-007: the session pubkey, captured at connect, cleared only by
     *  [disconnect]. The ONLY restore source for [restoreConnectedState]. */
    @Volatile
    private var sessionPublicKeyBase58: String? = null

    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var pendingRequest: Job? = null

    private enum class RequestKind { CONNECT, SIGN, TRANSACT }

    @Volatile
    private var pendingKind: RequestKind? = null

    /** Claim-settlement signal for the pending request: created with it
     *  in [runWalletRequest], completed by the foreground reconciler at
     *  claim time, cleared with the pending slot. The awaiting caller
     *  races its request deferred against this signal ([raceWithClaim]). */
    @Volatile
    private var pendingClaimSignal: CompletableDeferred<Unit>? = null

    private val generationCounter = AtomicInteger(0)

    @Volatile
    private var pendingGeneration: Int = 0

    @Volatile
    private var resolvedGeneration: Int = 0

    private val resolutionLock = Any()

    /** Claim generation [gen]'s state resolution. True exactly once per gen. */
    private fun claimResolution(gen: Int): Boolean = synchronized(resolutionLock) {
        if (resolvedGeneration == gen) false else {
            resolvedGeneration = gen
            true
        }
    }

    /** The MWA auth token, for persistence across restarts by the caller. */
    var persistedAuthToken: String?
        get() = walletAdapter.authToken
        set(value) {
            walletAdapter.authToken = value
        }

    /**
     * Connect to a wallet via [sender] (always MainActivity.walletSender —
     * the one sender constructed in onCreate). Returns the wallet's Base58
     * public key on success.
     */
    suspend fun connect(sender: ActivityResultSender): Result<String> =
        runWalletRequest(RequestKind.CONNECT, onInterrupted = { fail(INTERRUPTED_MESSAGE) }) { gen ->
            performConnect(sender, gen)
        }

    private sealed interface ConnectOutcome {
        data class Answered(
            val result: TransactionResult<Unit>
        ) : ConnectOutcome
        data class Threw(val error: Throwable) : ConnectOutcome
    }

    private suspend fun performConnect(sender: ActivityResultSender, gen: Int): Result<String> =
        withContext(Dispatchers.IO) {
            _uiState.value = WalletUiState.Working
            val followInterruption = lastRequestInterrupted
            if (followInterruption) {
                resetWalletAdapter()
                lastRequestInterrupted = false
                loopGeneration = gen
            }
            val maxAttempts = if (followInterruption) INTERRUPTED_CONNECT_ATTEMPTS else 1
            var attempt = 0
            var answered: ConnectOutcome? = null
            while (answered == null) {
                attempt++
                if (attempt > 1) {
                    resetWalletAdapter()
                    delay(INTERRUPTED_REFIRE_PAUSE_MS)
                }
                val adapter = walletAdapter
                val attemptStartMs = System.currentTimeMillis()
                val deferred = async {
                    try {
                        ConnectOutcome.Answered(adapter.connect(sender))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        ConnectOutcome.Threw(e)
                    }
                }
                if (!followInterruption) {
                    answered = deferred.await()
                    break
                }
                val outcome = withTimeoutOrNull(INTERRUPTED_ATTEMPT_TIMEOUT_MS) { deferred.await() }
                val elapsedMs = System.currentTimeMillis() - attemptStartMs
                val slow = outcome == null || elapsedMs > INTERRUPTED_SLOW_OUTCOME_MS
                val definitive = when (outcome) {
                    null -> false
                    is ConnectOutcome.Answered -> when (outcome.result) {
                        is TransactionResult.Success,
                        is TransactionResult.NoWalletFound -> true
                        is TransactionResult.Failure -> !slow
                    }
                    is ConnectOutcome.Threw -> !slow
                }
                Log.d(
                    LOG_TAG,
                    "connect attempt $attempt/$maxAttempts outcome=" +
                        (outcome?.javaClass?.simpleName ?: "timeout") +
                        " elapsedMs=$elapsedMs slow=$slow definitive=$definitive"
                )
                when {
                    definitive -> answered = outcome
                    attempt >= maxAttempts -> {
                        deferred.cancel()
                        return@withContext failIfCurrent(gen, INTERRUPTED_MESSAGE)
                    }
                    else -> {
                        deferred.cancel()
                        answered = null
                    }
                }
            }
            val finalOutcome = answered
                ?: return@withContext failIfCurrent(gen, INTERRUPTED_MESSAGE)
            when (val outcome = finalOutcome) {
                is ConnectOutcome.Threw ->
                    return@withContext failIfCurrent(gen, outcome.error.message ?: "Connection failed", outcome.error)
                is ConnectOutcome.Answered -> when (val result = outcome.result) {
                    is TransactionResult.Success -> {
                        val pubkeyBytes = result.authResult.accounts.firstOrNull()?.publicKey
                        if (pubkeyBytes == null) {
                            failIfCurrent(gen, "Wallet returned no accounts")
                        } else {
                            val base58 = Base58.encode(pubkeyBytes)
                            sessionPublicKeyBase58 = base58
                            _uiState.value = WalletUiState.Connected(base58)
                            Result.success(base58)
                        }
                    }
                    is TransactionResult.NoWalletFound ->
                        failIfCurrent(gen, "No MWA-compatible wallet found on this device")
                    is TransactionResult.Failure ->
                        failIfCurrent(gen, result.e.message ?: "Wallet connection failed", result.e)
                }
            }
        }

    /**
     * Ask the wallet to sign [message] (the server-issued SIWS text, byte
     * exact). Returns the RAW signature bytes — the host contract takes the
     * signature base64-encoded, so no string codec sits on this path.
     */
    suspend fun signMessageRaw(sender: ActivityResultSender, message: ByteArray): Result<ByteArray> =
        runWalletRequest(
            RequestKind.SIGN,
            onInterrupted = {
                restoreConnectedState()
                Result.failure(IllegalStateException(INTERRUPTED_MESSAGE))
            }
        ) { performSignMessage(sender, message) }

    /**
     * One sign attempt's outcome: the caller-visible [result] plus how many
     * times the transact block was entered — the block runs only after the
     * session is ESTABLISHED, so the count is the attempt's establishment
     * count (the re-issue gate's safety key).
     */
    private class SignAttempt(val result: Result<ByteArray>, val establishments: Int)

    /**
     * The sign path (KFC-008): a single bare transact per attempt — no
     * refire loop (an established sign that fails or is declined is a
     * definitive answer, not an interruption signature).
     *
     * Part 2 FULL — the bounded re-issue gate, landed via the
     * pre-authorized escalation after the claim-settlement battery
     * (runs 41240590255 / 41240596926 / 41240604757) proved the retry-leg
     * swallow wire-identical x3: with claim settlement alone, the
     * follow-up sign's transact FIRED but its association intent was
     * swallowed by the wallet-side zombie scenario of the interrupted
     * attempt (single-task handler; no websocket server ever started
     * for the new association — 102x ECONNREFUSED across the transact's
     * ~30 s establishment budget, ZERO establishments), while the
     * abandoned sheet sat in the wallet and consumed the approval,
     * producing a signature on the dead association. The gate, in the
     * VR v1.0.12 shape keyed to THIS wire: when this sign follows an
     * interruption, attempt 1 may be re-issued EXACTLY ONCE, and only
     * when attempt 1 RETURNED in failure with ZERO session
     * establishments — provably no sheet was ever presented for it and
     * no signature can exist, so the re-issue cannot double-submit.
     * Attempt 2 is final. A non-follow-up sign never re-issues.
     */
    private suspend fun performSignMessage(
        sender: ActivityResultSender,
        message: ByteArray
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        _uiState.value = WalletUiState.Working
        val followInterruption = lastRequestInterrupted
        if (followInterruption) {
            // Consumed here, mirroring performConnect: the first wallet
            // operation after an interruption is the follow-up; if THIS
            // generation is interrupted too, the claim/backstop tail
            // re-arms the flag for its own follow-up.
            lastRequestInterrupted = false
        }
        val first = runSignAttempt(sender, message)
        if (!followInterruption) return@withContext first.result
        if (first.result.isSuccess || first.establishments > 0) {
            // Definitive: either it succeeded, or a session WAS
            // established (a sheet may have been presented; a signature
            // may exist) — never re-issue.
            Log.d(
                LOG_TAG,
                "sign follow-up: attempt 1 definitive (success=${first.result.isSuccess}, " +
                    "establishments=${first.establishments}) — no re-issue"
            )
            return@withContext first.result
        }
        Log.d(
            LOG_TAG,
            "sign follow-up: attempt 1 returned with ZERO establishments (zombie-swallow " +
                "signature) — re-issuing exactly once"
        )
        resetWalletAdapter()
        delay(SIGN_REISSUE_PAUSE_MS)
        _uiState.value = WalletUiState.Working
        val second = runSignAttempt(sender, message)
        Log.d(
            LOG_TAG,
            "sign follow-up: re-issue outcome success=${second.result.isSuccess}, " +
                "establishments=${second.establishments}"
        )
        second.result
    }

    /** One bare sign transact, with its session-establishment count. */
    private suspend fun runSignAttempt(
        sender: ActivityResultSender,
        message: ByteArray
    ): SignAttempt {
        val establishments = AtomicInteger(0)
        val adapter = walletAdapter
        val result = runCatching {
            adapter.transact(sender) { authResult ->
                // First act inside the block: reaching here at all means
                // the session was established (VR H2 counting discipline).
                establishments.incrementAndGet()
                val account = authResult.accounts.first().publicKey
                signMessagesDetached(
                    arrayOf(message),
                    arrayOf(account)
                )
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            restoreConnectedState()
            return SignAttempt(Result.failure(e), establishments.get())
        }
        val mapped = when (result) {
            is TransactionResult.Success -> {
                val sig = result.successPayload?.messages
                    ?.firstOrNull()?.signatures?.firstOrNull()
                restoreConnectedState()
                if (sig != null) Result.success(sig)
                else Result.failure(IllegalStateException("Wallet returned an empty signature"))
            }
            is TransactionResult.NoWalletFound -> {
                restoreConnectedState()
                Result.failure(IllegalStateException("No MWA-compatible wallet found on this device"))
            }
            is TransactionResult.Failure -> {
                restoreConnectedState()
                Result.failure(result.e)
            }
        }
        return SignAttempt(mapped, establishments.get())
    }

    /**
     * Ask the wallet to sign and SEND one serialized transaction (a §17
     * payment built by the caller). Returns the transaction signature
     * bytes on success — the receipt the host's confirmPayment hint and
     * the invoice verifier key on. Runs under the same claim-settlement
     * rig as sign-message, with the bounded re-issue gate applied in
     * full (see [performSignAndSend]).
     */
    suspend fun signAndSendTransaction(
        sender: ActivityResultSender,
        transaction: ByteArray
    ): Result<ByteArray> =
        runWalletRequest(
            RequestKind.TRANSACT,
            onInterrupted = {
                restoreConnectedState()
                Result.failure(IllegalStateException(INTERRUPTED_MESSAGE))
            }
        ) { performSignAndSend(sender, transaction) }

    /** One send attempt's outcome, with its establishment count — the
     *  re-issue gate's safety key, same discipline as [SignAttempt]. */
    private class SendAttempt(val result: Result<ByteArray>, val establishments: Int)

    /**
     * The send path's bounded gate — the [performSignMessage] gate,
     * shape-for-shape: when this send follows an interruption, attempt 1
     * may be re-issued EXACTLY ONCE, and only when attempt 1 returned in
     * failure with ZERO session establishments (no sheet was ever
     * presented, so no signature and no submission can exist — the
     * re-issue cannot double-pay). Attempt 2 is final. A send that does
     * not follow an interruption never re-issues: an established send
     * that fails or is declined is a definitive answer.
     */
    private suspend fun performSignAndSend(
        sender: ActivityResultSender,
        transaction: ByteArray
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        _uiState.value = WalletUiState.Working
        val followInterruption = lastRequestInterrupted
        if (followInterruption) {
            lastRequestInterrupted = false
        }
        val first = runSendAttempt(sender, transaction)
        if (!followInterruption) return@withContext first.result
        if (first.result.isSuccess || first.establishments > 0) {
            Log.d(
                LOG_TAG,
                "send follow-up: attempt 1 definitive (success=${first.result.isSuccess}, " +
                    "establishments=${first.establishments}) — no re-issue"
            )
            return@withContext first.result
        }
        Log.d(
            LOG_TAG,
            "send follow-up: attempt 1 returned with ZERO establishments (zombie-swallow " +
                "signature) — re-issuing exactly once"
        )
        resetWalletAdapter()
        delay(SIGN_REISSUE_PAUSE_MS)
        _uiState.value = WalletUiState.Working
        val second = runSendAttempt(sender, transaction)
        Log.d(
            LOG_TAG,
            "send follow-up: re-issue outcome success=${second.result.isSuccess}, " +
                "establishments=${second.establishments}"
        )
        second.result
    }

    /** One bare sign-and-send transact, with its establishment count. */
    private suspend fun runSendAttempt(
        sender: ActivityResultSender,
        transaction: ByteArray
    ): SendAttempt {
        val establishments = AtomicInteger(0)
        val adapter = walletAdapter
        val result = runCatching {
            adapter.transact(sender) {
                establishments.incrementAndGet()
                signAndSendTransactions(arrayOf(transaction), DefaultTransactionParams)
            }
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            restoreConnectedState()
            return SendAttempt(Result.failure(e), establishments.get())
        }
        val mapped = when (result) {
            is TransactionResult.Success -> {
                val sig = result.successPayload?.signatures?.firstOrNull()
                restoreConnectedState()
                if (sig != null) Result.success(sig)
                else Result.failure(IllegalStateException("Wallet returned no transaction signature"))
            }
            is TransactionResult.NoWalletFound -> {
                restoreConnectedState()
                Result.failure(IllegalStateException("No MWA-compatible wallet found on this device"))
            }
            is TransactionResult.Failure -> {
                restoreConnectedState()
                Result.failure(result.e)
            }
        }
        return SendAttempt(mapped, establishments.get())
    }

    /** Revoke the MWA session; the next connect shows the approval dialog. */
    suspend fun disconnect(sender: ActivityResultSender) {
        val adapter = walletAdapter
        runCatching { adapter.disconnect(sender) }
        sessionPublicKeyBase58 = null
        _uiState.value = WalletUiState.Disconnected
    }

    /** Cancel the in-flight wallet request, if any (KFC-008). */
    fun cancelPendingRequest() {
        pendingRequest?.cancel()
    }

    /** The generation pending when the app backgrounded (onStop snapshot).
     *  The foreground reconciler may resolve ONLY this generation. */
    @Volatile
    private var backgroundedGeneration: Int = 0

    /** Snapshot the pending generation at backgrounding time. */
    fun onAppBackgrounded() {
        backgroundedGeneration = if (pendingRequest != null) pendingGeneration else 0
    }

    /** The generation running the post-interruption refire loop; the
     *  reconciler NEVER resolves it — the loop's own bounds resolve it. */
    @Volatile
    private var loopGeneration: Int = 0

    /**
     * KFC-008 foreground reconciliation — called from MainActivity's
     * onResume. A request still pending after the grace window, which was
     * already pending when the app backgrounded, was abandoned by the
     * wallet: resolve the UI state DIRECTLY from session truth (connect →
     * interrupted Error with retry; sign → restore Connected), claim the
     * generation so late writes are suppressed, discard the adapter so
     * the zombie association dies, SETTLE THE AWAITING CALLER at claim
     * time via its claim signal (the state write alone cannot resume a
     * caller parked on clientlib's non-cooperative suspension — the
     * CR-4 escape), and cancel the request as best effort.
     */
    fun onReturnedToForeground() {
        val job = pendingRequest ?: return
        val gen = pendingGeneration
        val kind = pendingKind ?: return
        // Captured with the pending slot; the identity re-check after the
        // grace delay (pendingRequest/pendingGeneration unchanged) is what
        // guarantees this signal still belongs to generation [gen].
        val claimSignal = pendingClaimSignal
        if (!job.isActive) return
        if (gen == loopGeneration) return
        if (gen != backgroundedGeneration) return
        backgroundedGeneration = 0
        requestScope.launch {
            delay(FOREGROUND_RECONCILE_GRACE_MS)
            if (pendingRequest !== job || pendingGeneration != gen || !job.isActive) return@launch
            if (claimResolution(gen)) {
                when (kind) {
                    RequestKind.CONNECT ->
                        _uiState.value = WalletUiState.Error(INTERRUPTED_MESSAGE)
                    RequestKind.SIGN, RequestKind.TRANSACT ->
                        restoreConnectedState()
                }
                lastRequestInterrupted = true
                resetWalletAdapter()
                // Claim settlement: deliver the caller's one failure
                // result NOW, at claim time — not at the 120 s backstop.
                claimSignal?.complete(Unit)
                Log.d(LOG_TAG, "reconciler claim gen=$gen kind=$kind — state resolved, caller settled at claim time")
            }
            job.cancel()
            if (pendingGeneration == gen) {
                pendingRequest = null
                pendingKind = null
            }
        }
    }

    /**
     * Runs one wallet request under the KFC-008 protections: tracked job on
     * [requestScope], awaited under the 120 s backstop. The wait RACES the
     * request against the generation's claim signal ([raceWithClaim]): if
     * the foreground reconciler claims this generation (the wallet was
     * abandoned), the caller settles at claim time via [onInterrupted] —
     * exactly one failure result — instead of parking on the request's
     * non-cooperative clientlib suspension until the backstop (the CR-4
     * escape: a state write alone cannot settle this caller). The backstop
     * and cancellation paths are unchanged: timeout or a cancelled request
     * still resolve via [onInterrupted] unless the reconciler already
     * claimed the generation's state resolution.
     */
    private suspend fun <T> runWalletRequest(
        kind: RequestKind,
        onInterrupted: () -> Result<T>,
        block: suspend (gen: Int) -> Result<T>
    ): Result<T> {
        val gen = generationCounter.incrementAndGet()
        val deferred: Deferred<Result<T>> = requestScope.async { block(gen) }
        val claimSignal = CompletableDeferred<Unit>()
        pendingRequest = deferred
        pendingKind = kind
        pendingGeneration = gen
        pendingClaimSignal = claimSignal
        var settledByClaim = false
        val result: Result<T>? = try {
            when (val race = raceWithClaim(deferred, claimSignal, WALLET_REQUEST_TIMEOUT_MS)) {
                is ClaimRace.Answered -> race.value
                ClaimRace.Claimed -> {
                    settledByClaim = true
                    Log.d(LOG_TAG, "caller settled by reconciler claim gen=$gen kind=$kind")
                    null
                }
                ClaimRace.TimedOut -> null
            }
        } catch (e: CancellationException) {
            if (deferred.isCancelled) {
                null
            } else {
                deferred.cancel()
                if (claimResolution(gen)) onInterrupted()
                throw e
            }
        } finally {
            if (pendingRequest === deferred) {
                pendingRequest = null
                pendingKind = null
                pendingClaimSignal = null
            }
            if (loopGeneration == gen) loopGeneration = 0
        }
        if (result != null) return result
        deferred.cancel()
        lastRequestInterrupted = true
        resetWalletAdapter()
        // Settled-by-claim reaches here with the generation already claimed
        // by the reconciler (claimResolution returns false) — the flag is
        // what routes it to the same single interruption result. In the
        // exotic interleave where the reconciler's job.cancel() lands
        // before the signal resumes this caller, the caller still receives
        // exactly one failure carrying INTERRUPTED_MESSAGE and the state
        // was already resolved by the reconciler at claim time.
        return if (claimResolution(gen) || settledByClaim) onInterrupted()
        else Result.failure(IllegalStateException(INTERRUPTED_MESSAGE))
    }

    private fun fail(message: String, cause: Throwable? = null): Result<String> {
        _uiState.value = WalletUiState.Error(message)
        return Result.failure(cause ?: IllegalStateException(message))
    }

    private fun failIfCurrent(gen: Int, message: String, cause: Throwable? = null): Result<String> {
        return if (resolvedGeneration == gen || pendingGeneration != gen) {
            Result.failure(cause ?: IllegalStateException(message))
        } else {
            fail(message, cause)
        }
    }

    private fun restoreConnectedState() {
        // KFC-007: restore from the stored session field — never from the
        // live UI state, which is Working for the whole transact.
        val pk = sessionPublicKeyBase58
        _uiState.value = if (pk != null) WalletUiState.Connected(pk) else WalletUiState.Disconnected
    }

    companion object {
        private const val WALLET_REQUEST_TIMEOUT_MS = 120_000L
        private const val FOREGROUND_RECONCILE_GRACE_MS = 5_000L
        private const val INTERRUPTED_MESSAGE =
            "Wallet request was interrupted - the wallet did not respond. Please try again."
        private const val INTERRUPTED_CONNECT_ATTEMPTS = 3
        private const val INTERRUPTED_ATTEMPT_TIMEOUT_MS = 35_000L
        private const val INTERRUPTED_SLOW_OUTCOME_MS = 25_000L
        private const val INTERRUPTED_REFIRE_PAUSE_MS = 500L
        private const val SIGN_REISSUE_PAUSE_MS = 1_000L
        private const val LOG_TAG = "OreKingdomWallet"
    }
}
