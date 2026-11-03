package com.raaru.orekingdom.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raaru.orekingdom.OreKingdomApp
import com.raaru.orekingdom.data.ApiResult
import com.raaru.orekingdom.data.CatchUp
import com.raaru.orekingdom.data.ForumPayload
import com.raaru.orekingdom.data.ForumThread
import com.raaru.orekingdom.data.InboxPayload
import com.raaru.orekingdom.data.Member
import com.raaru.orekingdom.data.NotificationItem
import com.raaru.orekingdom.data.ParkedFlow
import com.raaru.orekingdom.data.PaySession
import com.raaru.orekingdom.data.PinCheckout
import com.raaru.orekingdom.data.PinSlots
import com.raaru.orekingdom.data.PostSessionReply
import com.raaru.orekingdom.data.ThreadDetail
import com.raaru.orekingdom.data.TipCheckout
import com.raaru.orekingdom.data.tipConfirmArmed
import com.raaru.orekingdom.util.AssetSpec
import com.raaru.orekingdom.util.Base58
import com.raaru.orekingdom.util.PaymentTx
import com.raaru.orekingdom.util.RELAY_WALLET
import com.raaru.orekingdom.util.SiwsMessage
import com.raaru.orekingdom.util.TIP_MAX_USD_CENTS
import com.raaru.orekingdom.util.TIP_MIN_USD_CENTS
import com.raaru.orekingdom.util.formatUsdCents
import com.raaru.orekingdom.wallet.WalletUiState
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * View model: identity (R0) + the read surfaces + the M2 nav core set.
 *
 * Read states are three-valued and never collapse into a blank screen:
 * Loading, Ready(payload, stale), Unreachable. On a failed read the
 * last good payload (CacheStore) renders marked stale; with no cache,
 * the screens render the host-unreachable state with Retry — the
 * sealed build's behaviour, carried natively.
 *
 * Sign-in is the host's single-shot SIWS: connect (MWA) -> build the
 * canonical SIWS message (util/SiwsMessage) -> signMessage -> one
 * authenticateSiws call. There is no challenge round-trip and no
 * session token; the member record the host returns (via listForum's
 * member field) is the signed-in state.
 *
 * M2 identity discipline (wrapper B2): gated actions (posting, the
 * Catch-Up) and VIP read release carry the SIWS proof itself —
 * {messageBase64, signatureBase64, publicKeyBase64} — and the host
 * derives the acting wallet from it. The proof made at sign-in is
 * held in memory only (never persisted: SessionStore deliberately
 * stores no signature material) and re-made on demand when a restored
 * session has none.
 */
class ForumViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as OreKingdomApp
    private val hostApi = app.hostApi
    private val sessionStore = app.sessionStore
    private val cacheStore = app.cacheStore

    val walletState: StateFlow<WalletUiState> = app.walletManager.uiState

    sealed interface LoadState<out T> {
        data object Loading : LoadState<Nothing>
        data class Ready<T>(val value: T, val stale: Boolean = false) : LoadState<T>
        data object Unreachable : LoadState<Nothing>
    }

    /** The River's views (R7): one feed, filters as views. Boards are
     *  category views over the same payload; the VIP board is the one
     *  gated surface among them. */
    sealed interface RiverView {
        data object All : RiverView
        data object Commercial : RiverView
        data class Board(val categoryId: Int) : RiverView
    }

    /** R9 presentation state. News is held until the Catch-Up screen
     *  has displayed it; only then does the watermark advance. */
    sealed interface CatchUpState {
        data object Idle : CatchUpState
        data object NoNews : CatchUpState
        data class News(val catchUp: CatchUp) : CatchUpState
        data class Refused(val verdict: String) : CatchUpState
    }

    /** A made SIWS proof, held in memory for this process only. */
    private data class SiwsProof(
        val wallet: String,
        val message: ByteArray,
        val signature: ByteArray,
        val publicKey: ByteArray
    )

    private val _siwsProof = MutableStateFlow<SiwsProof?>(null)

    private fun proofJson(proof: SiwsProof): JSONObject =
        hostApi.siwsIdentity(proof.message, proof.signature, proof.publicKey)

    private val _member = MutableStateFlow<Member?>(null)
    val member: StateFlow<Member?> = _member.asStateFlow()

    private val _forumState = MutableStateFlow<LoadState<ForumPayload>>(LoadState.Loading)
    val forumState: StateFlow<LoadState<ForumPayload>> = _forumState.asStateFlow()

    private val _riverView = MutableStateFlow<RiverView>(RiverView.All)
    val riverView: StateFlow<RiverView> = _riverView.asStateFlow()

    private val _railState = MutableStateFlow<LoadState<List<ForumThread>>>(LoadState.Loading)
    val railState: StateFlow<LoadState<List<ForumThread>>> = _railState.asStateFlow()

    private val _threadState = MutableStateFlow<LoadState<ThreadDetail>>(LoadState.Loading)
    val threadState: StateFlow<LoadState<ThreadDetail>> = _threadState.asStateFlow()

    /** The host's refusal string for the open thread, when it answered
     *  {ok:false} — rendered verbatim (error truthfulness), never
     *  paraphrased into a generic failure. */
    private val _threadRefusal = MutableStateFlow<String?>(null)
    val threadRefusal: StateFlow<String?> = _threadRefusal.asStateFlow()

    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

    private val _inboxState = MutableStateFlow<LoadState<InboxPayload>>(LoadState.Loading)
    val inboxState: StateFlow<LoadState<InboxPayload>> = _inboxState.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _catchUpState = MutableStateFlow<CatchUpState>(CatchUpState.Idle)
    val catchUpState: StateFlow<CatchUpState> = _catchUpState.asStateFlow()

    /** The `through` value already advanced for — the exactly-once
     *  guard for the Catch-Up watermark (R9 battery language). */
    private var catchUpAdvancedThrough: Long = 0L

    // ---------- M3: payment state (R1–R6) ----------

    private val paymentStore = app.paymentStore

    /** The member record the HOST served for the signed-in wallet on
     *  the last live listForum (null = the host has no member record
     *  for this wallet — the join gate's offer keys on exactly this,
     *  never on the locally restored display identity). */
    private val _hostMember = MutableStateFlow<Member?>(null)
    val hostMember: StateFlow<Member?> = _hostMember.asStateFlow()

    /** True once a live read has answered the membership question for
     *  the signed-in wallet (before that, the join offer stays hidden
     *  rather than flashing at a member). */
    private val _membershipChecked = MutableStateFlow(false)
    val membershipChecked: StateFlow<Boolean> = _membershipChecked.asStateFlow()

    /** The caller's prestige state, from the payload's served flag or
     *  the getPrestigeSession read; null = not yet answered. */
    private val _isPrestigeHolder = MutableStateFlow<Boolean?>(null)
    val isPrestigeHolder: StateFlow<Boolean?> = _isPrestigeHolder.asStateFlow()

    /** The spotlight's slot-state read (getPinSlots); null until the
     *  first live answer — the spotlight renders no strip until the
     *  read answers. */
    private val _pinSlots = MutableStateFlow<PinSlots?>(null)
    val pinSlots: StateFlow<PinSlots?> = _pinSlots.asStateFlow()

    /** Parked payment flows for the signed-in wallet (R4). */
    private val _parkedFlows = MutableStateFlow<List<ParkedFlow>>(emptyList())
    val parkedFlows: StateFlow<List<ParkedFlow>> = _parkedFlows.asStateFlow()

    /** One payment operation at a time, app-wide: a second Pay tap
     *  while a wallet sheet or a completion loop is live is ignored,
     *  never queued into a second transfer. */
    private val _paying = MutableStateFlow(false)
    val paying: StateFlow<Boolean> = _paying.asStateFlow()

    /** What the composer's Continue step resolved to: an exemption
     *  (post session-free, showing the host's note), a session-free
     *  VIP-holder post, or a priced quote to confirm. */
    sealed interface ThreadPrep {
        data class Exempt(val note: String?) : ThreadPrep
        data object SessionFree : ThreadPrep
        data class Quote(val session: PaySession, val commercial: Boolean) : ThreadPrep
    }

    /** One payment attempt's classification (R6): Done; Retry (the
     *  host's own "not finalized yet" answer — the pending loop's
     *  only retryable shape); StillPending (the loop's window ran
     *  out — the flow stays parked, never re-paid); Failed (terminal,
     *  shown verbatim — a refusal is a refusal, never "pending"). */
    private sealed interface PayOutcome {
        data class Done(val note: String? = null) : PayOutcome
        data class Retry(val message: String) : PayOutcome
        data class StillPending(val message: String) : PayOutcome
        data class Failed(val message: String) : PayOutcome
    }

    init {
        // Restore the displayed identity (wallet + .skr name) from the
        // last successful sign-in; reads below re-confirm it against
        // the host's own member record.
        val wallet = sessionStore.wallet
        val skr = sessionStore.skrName
        if (wallet != null && skr != null) {
            _member.value = Member(
                id = 0, wallet = wallet, displayName = skr, skrName = skr,
                reputation = 0, tier = "", isFoundingMember = false
            )
        }
        loadForum()
    }

    fun selectView(view: RiverView) {
        _riverView.value = view
    }

    /**
     * The River's base read: the full unpaginated payload, always
     * unfiltered — the views (All / Commercial / boards) filter it
     * client-side. The pin strip reads separately through
     * getPinSlots, so a board view never hides a live pin. A cached
     * SIWS proof rides along so a verified prestige holder's payload
     * includes the VIP board's threads (wrapper read release,
     * M2_HOST_REPORT §4b).
     */
    fun loadForum() {
        viewModelScope.launch {
            _forumState.value = LoadState.Loading
            val proof = _siwsProof.value
            val result = hostApi.listForum(
                wallet = sessionStore.wallet,
                siwsIdentity = proof?.let { proofJson(it) }
            )
            when (result) {
                is ApiResult.Ok -> {
                    cacheStore.put("forum_all", result.value)
                    val payload = ForumPayload.fromJson(result.value)
                    payload.member?.let { _member.value = it }
                    // M3: the membership question is answered by the
                    // host's own record on this live read — a signed-in
                    // wallet with no record is a non-member (the join
                    // gate's offer), whatever the restored display
                    // identity says.
                    if (sessionStore.wallet != null) {
                        _hostMember.value = payload.member
                        _membershipChecked.value = true
                    }
                    payload.callerPrestige?.let { _isPrestigeHolder.value = it }
                    _forumState.value = LoadState.Ready(payload)
                }
                is ApiResult.Refused -> {
                    // A refusal is an answer, not a connection failure:
                    // surface it through the same honest error path.
                    _forumState.value = LoadState.Unreachable
                }
                ApiResult.Unreachable -> {
                    val cached = cacheStore.get("forum_all")
                    _forumState.value = if (cached != null) {
                        LoadState.Ready(ForumPayload.fromJson(cached), stale = true)
                    } else {
                        LoadState.Unreachable
                    }
                }
            }
        }
        loadRail()
        refreshUnreadCount()
        loadPinSlots()
        refreshParked()
    }

    /** R8 — the Unanswered Rail: server-computed zero-reply threads,
     *  longest-waiting first. Refreshed alongside the river. */
    fun loadRail() {
        viewModelScope.launch {
            when (val result = hostApi.listUnanswered(sessionStore.wallet)) {
                is ApiResult.Ok -> {
                    cacheStore.put("rail", result.value)
                    _railState.value = LoadState.Ready(parseRail(result.value))
                }
                is ApiResult.Refused -> _railState.value = LoadState.Unreachable
                ApiResult.Unreachable -> {
                    val cached = cacheStore.get("rail")
                    _railState.value = if (cached != null) {
                        LoadState.Ready(parseRail(cached), stale = true)
                    } else {
                        LoadState.Unreachable
                    }
                }
            }
        }
    }

    private fun parseRail(o: JSONObject): List<ForumThread> {
        val threads = o.optJSONArray("threads")
        return (0 until (threads?.length() ?: 0)).mapNotNull {
            threads?.optJSONObject(it)?.let { t -> ForumThread.fromJson(t) }
        }
    }

    fun loadThread(threadId: Int) {
        val cacheKey = "thread_$threadId"
        viewModelScope.launch {
            _threadState.value = LoadState.Loading
            _threadRefusal.value = null
            val proof = _siwsProof.value
            val result = hostApi.getThread(
                wallet = sessionStore.wallet ?: "",
                threadId = threadId,
                siwsIdentity = proof?.let { proofJson(it) }
            )
            when (result) {
                is ApiResult.Ok -> {
                    val detail = ThreadDetail.fromJson(result.value)
                    if (detail != null) {
                        cacheStore.put(cacheKey, result.value)
                        _threadState.value = LoadState.Ready(detail)
                    } else {
                        _threadRefusal.value =
                            result.value.optString("error").ifBlank { "This thread is unavailable." }
                        _threadState.value = LoadState.Unreachable
                    }
                }
                is ApiResult.Refused -> {
                    _threadRefusal.value = result.verdict
                    _threadState.value = LoadState.Unreachable
                }
                ApiResult.Unreachable -> {
                    val cached = cacheStore.get(cacheKey)
                    val detail = cached?.let { ThreadDetail.fromJson(it) }
                    _threadState.value = if (detail != null) {
                        LoadState.Ready(detail, stale = true)
                    } else {
                        LoadState.Unreachable
                    }
                }
            }
        }
    }

    // ---------- identity: proof making ----------

    /** Connect if needed and have the wallet sign a fresh canonical
     *  SIWS message. The one proof-making path: sign-in and every
     *  gated action go through it. */
    private suspend fun makeProof(sender: ActivityResultSender): Result<SiwsProof> {
        val walletManager = app.walletManager
        val address: String = if (walletManager.isConnected) {
            walletManager.publicKeyBase58!!
        } else {
            val connectResult = walletManager.connect(sender)
            if (connectResult.isFailure) return Result.failure(
                connectResult.exceptionOrNull() ?: Exception("Wallet connection failed")
            )
            connectResult.getOrThrow()
        }
        // Persist the MWA auth token for quiet reauthorization.
        sessionStore.authToken = walletManager.persistedAuthToken

        val message = SiwsMessage.build(address)
        val signatureResult = walletManager.signMessageRaw(sender, message)
        if (signatureResult.isFailure) return Result.failure(
            signatureResult.exceptionOrNull() ?: Exception("Wallet signing failed")
        )
        val publicKey = try {
            Base58.decode(address)
        } catch (e: Exception) {
            return Result.failure(Exception("Wallet returned an unreadable address"))
        }
        return Result.success(
            SiwsProof(address, message, signatureResult.getOrThrow(), publicKey)
        )
    }

    /** The cached proof if it still matches the signed-in wallet, else
     *  a freshly made one. A proof for a different wallet than the
     *  signed-in member is refused here, before any host call. */
    private suspend fun ensureProof(sender: ActivityResultSender): Result<SiwsProof> {
        val sessionWallet = sessionStore.wallet
        val cached = _siwsProof.value
        if (cached != null && (sessionWallet == null || cached.wallet == sessionWallet)) {
            return Result.success(cached)
        }
        val proof = makeProof(sender).getOrElse { return Result.failure(it) }
        if (sessionWallet != null && proof.wallet != sessionWallet) {
            return Result.failure(
                Exception("The connected wallet is not your signed-in wallet.")
            )
        }
        _siwsProof.value = proof
        return Result.success(proof)
    }

    /**
     * The full sign-in: connect if needed, sign the SIWS message, one
     * authenticateSiws call. [onDone] receives null on success or the
     * truthful error (wallet message, or the host's own verdict string
     * carried verbatim — e.g. the .skr requirement).
     */
    fun signIn(sender: ActivityResultSender, onDone: (String?) -> Unit) {
        if (_signingIn.value) return
        _signingIn.value = true
        viewModelScope.launch {
            fun done(error: String?) {
                _signingIn.value = false
                onDone(error)
            }
            val proof = makeProof(sender).getOrElse {
                done(it.message ?: "Wallet signing failed")
                return@launch
            }
            _siwsProof.value = proof
            when (val result = hostApi.authenticateSiws(
                proof.wallet, proof.message, proof.signature, proof.publicKey
            )) {
                is ApiResult.Ok -> {
                    if (result.value.optBoolean("ok", false)) {
                        sessionStore.wallet = proof.wallet
                        sessionStore.skrName = result.value.optString("skrName")
                        loadForum()
                        done(null)
                    } else {
                        done(result.value.optString("error").ifBlank { "Sign-in was refused." })
                    }
                }
                is ApiResult.Refused -> done(result.verdict)
                ApiResult.Unreachable ->
                    done("Ore Kingdom could not be reached — check your connection and try again.")
            }
        }
    }

    fun signOut(sender: ActivityResultSender) {
        viewModelScope.launch {
            app.walletManager.disconnect(sender)
            sessionStore.clear()
            _siwsProof.value = null
            _member.value = null
            _hostMember.value = null
            _membershipChecked.value = false
            _isPrestigeHolder.value = null
            _unreadCount.value = 0
            _catchUpState.value = CatchUpState.Idle
            catchUpAdvancedThrough = 0L
            loadForum()
        }
    }

    /**
     * Make (or reuse) the SIWS proof and reload the river with it
     * attached — the VIP board's "verify it's you" action. A restored
     * session holds no proof in memory; a verified holder's next read
     * is what releases the board's threads. [onDone] receives null on
     * success or the truthful error.
     */
    fun refreshWithProof(sender: ActivityResultSender, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            ensureProof(sender).fold(
                onSuccess = {
                    loadForum()
                    onDone(null)
                },
                onFailure = { onDone(it.message ?: "Sign-in proof failed") }
            )
        }
    }

    // ---------- R9: the Catch-Up ----------

    /**
     * Ask the host what's new since the member's watermark. Runs when
     * the river opens with a signed-in member. Silent by design when
     * there is nothing to show or the member declines to sign: the
     * Catch-Up is an enhancement over the river, never a gate on it.
     * News is held in state; the screen presents it, and only the
     * display triggers the advance.
     */
    fun checkCatchUp(sender: ActivityResultSender) {
        if (sessionStore.wallet == null) return
        if (_catchUpState.value is CatchUpState.News) return
        viewModelScope.launch {
            val proof = ensureProof(sender).getOrElse { return@launch }
            when (val result = hostApi.getCatchUp(proof.wallet, proofJson(proof))) {
                is ApiResult.Ok -> {
                    val catchUp = CatchUp.fromJson(result.value)
                    _catchUpState.value =
                        if (catchUp.hasNews && catchUp.totalNews > 0) {
                            CatchUpState.News(catchUp)
                        } else {
                            CatchUpState.NoNews
                        }
                }
                is ApiResult.Refused -> _catchUpState.value = CatchUpState.Refused(result.verdict)
                ApiResult.Unreachable -> Unit // stay Idle; a later open retries
            }
        }
    }

    /**
     * Called by the Catch-Up screen once the news has been displayed:
     * advances the server watermark to the read's `through`, exactly
     * once per open-with-news. An item that landed during display is
     * after `through` and therefore still news next time — never
     * silently skipped (M2 host contract).
     */
    fun advanceCatchUpAfterDisplay() {
        val news = _catchUpState.value as? CatchUpState.News ?: return
        val through = news.catchUp.through
        if (through <= catchUpAdvancedThrough) {
            _catchUpState.value = CatchUpState.NoNews
            return
        }
        catchUpAdvancedThrough = through
        viewModelScope.launch {
            val proof = _siwsProof.value ?: return@launch
            hostApi.advanceCatchUp(proof.wallet, through, proofJson(proof))
            _catchUpState.value = CatchUpState.NoNews
        }
    }

    // ---------- R10: the Inbox ----------

    fun loadInbox() {
        val wallet = sessionStore.wallet
        if (wallet == null) {
            _inboxState.value = LoadState.Ready(InboxPayload(0, emptyList()))
            return
        }
        viewModelScope.launch {
            _inboxState.value = LoadState.Loading
            when (val result = hostApi.listNotifications(wallet)) {
                is ApiResult.Ok -> {
                    val payload = InboxPayload.fromJson(result.value)
                    _unreadCount.value = payload.unread
                    _inboxState.value = LoadState.Ready(payload)
                }
                is ApiResult.Refused -> _inboxState.value = LoadState.Unreachable
                ApiResult.Unreachable -> _inboxState.value = LoadState.Unreachable
            }
        }
    }

    /** The nav badge count, refreshed with the river. */
    fun refreshUnreadCount() {
        val wallet = sessionStore.wallet ?: run {
            _unreadCount.value = 0
            return
        }
        viewModelScope.launch {
            val result = hostApi.listNotifications(wallet)
            if (result is ApiResult.Ok) {
                _unreadCount.value = result.value.optInt("unread")
            }
        }
    }

    fun markAllNotificationsRead() {
        val wallet = sessionStore.wallet ?: return
        viewModelScope.launch {
            hostApi.markNotificationsRead(wallet)
            loadInbox()
        }
    }

    /** Opening an entry marks it read server-side (the read state is
     *  the host's copy — it survives reinstall, per R10). Local state
     *  updates optimistically to match. */
    fun markNotificationRead(item: NotificationItem) {
        if (item.read) return
        val wallet = sessionStore.wallet ?: return
        viewModelScope.launch {
            hostApi.markNotificationsRead(wallet, listOf(item.id))
            val current = _inboxState.value
            if (current is LoadState.Ready<*>) {
                @Suppress("UNCHECKED_CAST")
                val payload = (current as LoadState.Ready<InboxPayload>).value
                val items = payload.items.map {
                    if (it.id == item.id) it.copy(read = true) else it
                }
                val unread = items.count { !it.read }
                _unreadCount.value = unread
                _inboxState.value = LoadState.Ready(InboxPayload(unread, items))
            }
        }
    }

    // ---------- M3: the payment engine (R1–R6) ----------
    //
    // The discipline, once for every surface below:
    //  - The quote IS the host's session/checkout — amount, asset,
    //    destination and reference are read from it, never computed
    //    client-side from a price table.
    //  - A payment is built and sent exactly once; its signature is
    //    parked (SessionStore + PaymentStore) the moment it exists,
    //    BEFORE the first completion call.
    //  - Completion retries ride the pending loop on the host's own
    //    "not finalized yet" answer and NEVER re-pay (R3). Refusals
    //    are terminal and surface verbatim (R6); an expired session
    //    ends the flow — the screen re-quotes fresh.
    //  - Where the host does not serve a field a transfer needs (the
    //    tip fee leg's reference; the pin checkout's reference), the
    //    flow halts BEFORE signing with the gap named — a transfer
    //    the host cannot verify is never signed.

    /** The host's pending shape is the pending loop's only retryable
     *  answer: the wrapper's "No finalized payment found…" family and
     *  the bundle's "not found at finalized commitment". Everything
     *  else classifies Failed — refused shows refused, never pending. */
    private fun classifyError(message: String): PayOutcome =
        if (message.contains("No finalized payment found") ||
            message.contains("not found at finalized commitment")
        ) {
            PayOutcome.Retry(message)
        } else {
            PayOutcome.Failed(message)
        }

    /** The pending loop: re-dispatch the COMPLETION (never a second
     *  payment) until the host's answer is terminal or the finality
     *  window (~60 s) runs out with StillPending. */
    private suspend fun pendingLoop(
        onProgress: (String) -> Unit,
        attempt: suspend () -> PayOutcome
    ): PayOutcome {
        var lastRetry = "The payment is still confirming."
        repeat(15) { i ->
            when (val outcome = attempt()) {
                is PayOutcome.Retry -> {
                    lastRetry = outcome.message
                    if (i < 14) {
                        onProgress("Waiting for the payment to finalize on-chain — checking again…")
                        delay(4_000)
                    }
                }
                else -> return outcome
            }
        }
        return PayOutcome.StillPending(lastRetry)
    }

    /** Build, sign and send one payment transaction; park its
     *  signature against [parkId] the moment it exists. */
    private suspend fun signPayment(
        sender: ActivityResultSender,
        payerWallet: String,
        parkId: String,
        build: (payer: ByteArray, blockhash: ByteArray) -> ByteArray
    ): Result<String> {
        val blockhash = when (val r = app.solanaRpc.getLatestBlockhash()) {
            is ApiResult.Ok -> r.value
            else -> return Result.failure(
                Exception("A fresh blockhash could not be fetched — nothing was signed or sent.")
            )
        }
        val payer = try {
            Base58.decode(payerWallet)
        } catch (e: Exception) {
            return Result.failure(Exception("The wallet address could not be read — nothing was sent."))
        }
        val txBytes = try {
            build(payer, blockhash)
        } catch (e: Exception) {
            return Result.failure(
                Exception("The payment could not be built: ${e.message} — nothing was sent.")
            )
        }
        val signatureBytes = app.walletManager.signAndSendTransaction(sender, txBytes)
            .getOrElse { return Result.failure(it) }
        val signature = Base58.encode(signatureBytes)
        sessionStore.parkSignature(parkId, signature)
        return Result.success(signature)
    }

    /** Pay a wrapper-native session: exactly its served amount to its
     *  served destination with its reference attached — one transfer.
     *  Two guards live here, at the one choke point every session
     *  payment passes through: a session whose pay window has already
     *  closed is never paid (R6 — a late transfer can't revive it;
     *  the screen re-quotes fresh), and a session that already has a
     *  parked signature is never paid twice — its existing signature
     *  is returned and the completion loop takes over (R3/R4). */
    private suspend fun payPaySession(
        sender: ActivityResultSender,
        proof: SiwsProof,
        session: PaySession
    ): Result<String> {
        session.payExpiresAt?.let { expiry ->
            if (System.currentTimeMillis() > expiry) {
                return Result.failure(
                    Exception("That payment window has expired — go back and get a fresh quote. Nothing was sent.")
                )
            }
        }
        sessionStore.parkedSignature(session.id)?.let { return Result.success(it) }
        val asset = session.asset
            ?: return Result.failure(
                Exception("The host quoted an asset this client cannot pay — nothing was sent.")
            )
        val destination = try {
            Base58.decode(session.destination)
        } catch (e: Exception) {
            return Result.failure(Exception("The session's destination could not be read — nothing was sent."))
        }
        val reference = try {
            Base58.decode(session.reference)
        } catch (e: Exception) {
            return Result.failure(Exception("The session's reference could not be read — nothing was sent."))
        }
        return signPayment(sender, proof.wallet, session.id) { payer, blockhash ->
            PaymentTx.buildSessionPayment(payer, asset, destination, session.amountBase, reference, blockhash)
        }
    }

    private fun parkFlow(flow: ParkedFlow) {
        paymentStore.park(flow)
        refreshParked()
    }

    private fun unparkFlow(kind: String, id: String) {
        paymentStore.unpark(kind, id)
        sessionStore.clearParked(id)
        refreshParked()
    }

    /** The parked list for the signed-in wallet (R4's surface data). */
    fun refreshParked() {
        val wallet = sessionStore.wallet
        _parkedFlows.value = if (wallet == null) {
            emptyList()
        } else {
            paymentStore.parkedFlows().filter { it.wallet == wallet }
        }
    }

    /** The member's own escape hatch for a parked record that can no
     *  longer complete (e.g. a tip whose signature never landed): the
     *  record is theirs to drop; nothing about dropping it moves
     *  money or changes host state. */
    fun dismissParked(flow: ParkedFlow) = unparkFlow(flow.kind, flow.id)

    /** The spotlight's slot-state read (wrapper getPinSlots). A failed
     *  read keeps the last served state; with no served state yet the
     *  spotlight renders no strip. */
    fun loadPinSlots() {
        viewModelScope.launch {
            when (val result = hostApi.getPinSlots(sessionStore.wallet)) {
                is ApiResult.Ok -> PinSlots.fromJson(result.value)?.let { _pinSlots.value = it }
                else -> Unit
            }
        }
    }

    /** One createThread/createReply dispatch against a session,
     *  classified. Driven by the pending loop; never re-pays (R3). */
    private suspend fun postDispatchAttempt(
        proof: SiwsProof,
        sessionId: String,
        commercial: Boolean,
        categoryId: Int?,
        title: String?,
        body: String,
        threadId: Int?
    ): PayOutcome {
        val result = when {
            threadId != null -> hostApi.createReply(
                proof.wallet, threadId, body, proofJson(proof), postSessionId = sessionId
            )
            commercial -> hostApi.createThread(
                proof.wallet, categoryId!!, title!!, body, proofJson(proof),
                commercialSessionId = sessionId
            )
            else -> hostApi.createThread(
                proof.wallet, categoryId!!, title!!, body, proofJson(proof),
                postSessionId = sessionId
            )
        }
        return when (result) {
            is ApiResult.Ok -> if (result.value.optBoolean("ok", false)) {
                PayOutcome.Done()
            } else {
                classifyError(result.value.optString("error").ifBlank { "The post was refused." })
            }
            is ApiResult.Refused -> classifyError(result.verdict)
            ApiResult.Unreachable -> PayOutcome.Retry("Ore Kingdom could not be reached.")
        }
    }

    // ---------- composer: the thread fee + commercial (R2) ----------

    /**
     * Composer step 1 — resolve what this thread costs before anything
     * is signed. A commercial thread takes a commercial session
     * ($50 flat). A VIP-board thread by a holder goes session-free —
     * the gate passes holders before any session logic, so paying
     * first would strand a session — and a non-holder is refused here,
     * before any session exists. Every other thread asks
     * createPostSession, whose answer is a priced quote or an
     * exemption carrying the host's own note (a prestige holder's
     * thread: exempt, A12).
     */
    fun prepareThreadPayment(
        sender: ActivityResultSender,
        commercial: Boolean,
        isVipBoard: Boolean,
        callerPrestige: Boolean?,
        asset: AssetSpec,
        onReady: (ThreadPrep) -> Unit,
        onError: (String?) -> Unit
    ) {
        if (_paying.value) return
        _paying.value = true
        viewModelScope.launch {
            fun fail(error: String?) {
                _paying.value = false
                onError(error)
            }
            val proof = ensureProof(sender).getOrElse {
                fail(it.message ?: "Sign-in proof failed")
                return@launch
            }
            if (commercial) {
                if (isVipBoard) {
                    fail("Commercial threads can't run on the VIP board — the host refuses them there, so no session was started.")
                    return@launch
                }
                when (val result = hostApi.createCommercialSession(proof.wallet, asset.code, proofJson(proof))) {
                    is ApiResult.Ok -> {
                        _paying.value = false
                        val session = PaySession.fromJson(
                            result.value.optJSONObject("session") ?: result.value
                        )
                        if (session != null) {
                            onReady(ThreadPrep.Quote(session, commercial = true))
                        } else {
                            onError("The host's commercial quote could not be read — nothing was charged.")
                        }
                    }
                    is ApiResult.Refused -> fail(result.verdict)
                    ApiResult.Unreachable ->
                        fail("Ore Kingdom could not be reached — check your connection and try again.")
                }
                return@launch
            }
            if (isVipBoard) {
                _paying.value = false
                when (callerPrestige) {
                    false -> onError("The VIP board is prestige-only — this wallet does not hold prestige, so nothing was charged.")
                    else -> onReady(ThreadPrep.SessionFree)
                }
                return@launch
            }
            when (val result = hostApi.createPostSession(
                proof.wallet, kind = "thread", audience = "public",
                asset = asset.code, siwsIdentity = proofJson(proof)
            )) {
                is ApiResult.Ok -> {
                    _paying.value = false
                    when (val reply = PostSessionReply.fromJson(result.value)) {
                        is PostSessionReply.Exempt -> onReady(ThreadPrep.Exempt(reply.note))
                        is PostSessionReply.Session -> onReady(ThreadPrep.Quote(reply.session, commercial = false))
                        null -> onError("The host's answer could not be read — nothing was charged.")
                    }
                }
                is ApiResult.Refused -> fail(result.verdict)
                ApiResult.Unreachable ->
                    fail("Ore Kingdom could not be reached — check your connection and try again.")
            }
        }
    }

    /**
     * Composer step 2 — carry out the prepared path. Exempt and
     * session-free posts dispatch directly. A quote is paid exactly
     * once (the flow parks with its signature before the first
     * dispatch) and dispatched through the pending loop.
     */
    fun executeThreadPayment(
        sender: ActivityResultSender,
        categoryId: Int,
        title: String,
        body: String,
        prep: ThreadPrep,
        onProgress: (String) -> Unit,
        onDone: (String?) -> Unit
    ) {
        if (_paying.value) return
        _paying.value = true
        viewModelScope.launch {
            fun done(error: String?) {
                _paying.value = false
                onDone(error)
            }
            val proof = ensureProof(sender).getOrElse {
                done(it.message ?: "Sign-in proof failed")
                return@launch
            }
            when (prep) {
                is ThreadPrep.Exempt, is ThreadPrep.SessionFree -> {
                    when (val result = hostApi.createThread(
                        proof.wallet, categoryId, title, body, proofJson(proof)
                    )) {
                        is ApiResult.Ok -> if (result.value.optBoolean("ok", false)) {
                            loadForum()
                            done(null)
                        } else {
                            done(result.value.optString("error").ifBlank { "The post was refused." })
                        }
                        is ApiResult.Refused -> done(result.verdict)
                        ApiResult.Unreachable ->
                            done("Ore Kingdom could not be reached — check your connection and try again.")
                    }
                }
                is ThreadPrep.Quote -> {
                    val session = prep.session
                    val kind = if (prep.commercial) "commercial" else "post"
                    onProgress("Opening your wallet to pay…")
                    payPaySession(sender, proof, session).getOrElse {
                        done(it.message ?: "The payment was not sent.")
                        return@launch
                    }
                    parkFlow(
                        ParkedFlow(
                            kind = kind,
                            id = session.id,
                            wallet = proof.wallet,
                            signature = sessionStore.parkedSignature(session.id),
                            label = (if (prep.commercial) "Commercial thread — " else "Thread — ") + title,
                            createdAt = System.currentTimeMillis(),
                            title = title, body = body, categoryId = categoryId
                        )
                    )
                    onProgress("Payment sent — posting…")
                    when (val outcome = pendingLoop(onProgress) {
                        postDispatchAttempt(proof, session.id, prep.commercial, categoryId, title, body, null)
                    }) {
                        is PayOutcome.Done -> {
                            unparkFlow(kind, session.id)
                            loadForum()
                            done(null)
                        }
                        is PayOutcome.Failed -> {
                            unparkFlow(kind, session.id)
                            done(outcome.message)
                        }
                        is PayOutcome.StillPending -> done(
                            "Your payment is still confirming on-chain. It is saved under “Payments to finish” on the River — the post completes from there without paying again."
                        )
                        is PayOutcome.Retry -> done("The payment is still confirming.")
                    }
                }
            }
        }
    }

    /**
     * Reply to a thread. The first attempt is session-free — the gate
     * passes VIP-holder posts, prestige-based session-free posts and every
     * other session-free case by its own reads, and paying first would
     * strand a session against them. Only the gate's own paid answer
     * ("start a payment session first") opens the paid path: a priced
     * reply session, paid once, dispatched through the pending loop.
     * Any other refusal surfaces verbatim. (The staged host charges
     * reply sessions today; the final ruling is free member replies —
     * this flow follows the host's answers either way, and a host
     * that stops charging needs no client change.)
     */
    fun postReply(
        sender: ActivityResultSender,
        threadId: Int,
        body: String,
        asset: AssetSpec,
        onProgress: (String) -> Unit,
        onDone: (String?) -> Unit
    ) {
        if (_paying.value) return
        _paying.value = true
        viewModelScope.launch {
            fun done(error: String?) {
                _paying.value = false
                onDone(error)
            }
            val proof = ensureProof(sender).getOrElse {
                done(it.message ?: "Sign-in proof failed")
                return@launch
            }
            when (val first = hostApi.createReply(proof.wallet, threadId, body, proofJson(proof))) {
                is ApiResult.Ok -> {
                    if (first.value.optBoolean("ok", false)) {
                        loadThread(threadId)
                        loadForum()
                        done(null)
                        return@launch
                    }
                    val error = first.value.optString("error").ifBlank { "The reply was refused." }
                    if (!error.contains("start a payment session first")) {
                        done(error)
                        return@launch
                    }
                }
                is ApiResult.Refused -> {
                    if (!first.verdict.contains("start a payment session first")) {
                        done(first.verdict)
                        return@launch
                    }
                }
                ApiResult.Unreachable -> {
                    done("Ore Kingdom could not be reached — check your connection and try again.")
                    return@launch
                }
            }
            @Suppress("UNCHECKED_CAST")
            val detail = (_threadState.value as? LoadState.Ready<ThreadDetail>)?.value
            val audience = detail?.thread?.audience ?: "public"
            val session = when (val priced = hostApi.createPostSession(
                proof.wallet, kind = "reply", audience = audience,
                asset = asset.code, siwsIdentity = proofJson(proof)
            )) {
                is ApiResult.Ok -> when (val reply = PostSessionReply.fromJson(priced.value)) {
                    is PostSessionReply.Exempt -> {
                        when (val retry = hostApi.createReply(proof.wallet, threadId, body, proofJson(proof))) {
                            is ApiResult.Ok -> if (retry.value.optBoolean("ok", false)) {
                                loadThread(threadId)
                                loadForum()
                                done(null)
                            } else {
                                done(retry.value.optString("error").ifBlank { "The reply was refused." })
                            }
                            is ApiResult.Refused -> done(retry.verdict)
                            ApiResult.Unreachable ->
                                done("Ore Kingdom could not be reached — check your connection and try again.")
                        }
                        return@launch
                    }
                    is PostSessionReply.Session -> reply.session
                    null -> {
                        done("The host's answer could not be read — nothing was charged.")
                        return@launch
                    }
                }
                is ApiResult.Refused -> {
                    done(priced.verdict)
                    return@launch
                }
                ApiResult.Unreachable -> {
                    done("Ore Kingdom could not be reached — check your connection and try again.")
                    return@launch
                }
            }
            onProgress("Opening your wallet to pay…")
            payPaySession(sender, proof, session).getOrElse {
                done(it.message ?: "The payment was not sent.")
                return@launch
            }
            parkFlow(
                ParkedFlow(
                    kind = "reply",
                    id = session.id,
                    wallet = proof.wallet,
                    signature = sessionStore.parkedSignature(session.id),
                    label = "Reply — thread #$threadId",
                    createdAt = System.currentTimeMillis(),
                    body = body, threadId = threadId
                )
            )
            onProgress("Payment sent — posting your reply…")
            when (val outcome = pendingLoop(onProgress) {
                postDispatchAttempt(proof, session.id, false, null, null, body, threadId)
            }) {
                is PayOutcome.Done -> {
                    unparkFlow("reply", session.id)
                    loadThread(threadId)
                    loadForum()
                    done(null)
                }
                is PayOutcome.Failed -> {
                    unparkFlow("reply", session.id)
                    done(outcome.message)
                }
                is PayOutcome.StillPending -> done(
                    "Your payment is still confirming on-chain. It is saved under “Payments to finish” on the River — the reply completes from there without paying again."
                )
                is PayOutcome.Retry -> done("The payment is still confirming.")
            }
        }
    }

    // ---------- R1: the join gate ----------

    /** Join step 1: open the join session — the quote of record
     *  (5,000 SKR, served by the session itself). A wallet that
     *  already holds a membership is refused by the host here and
     *  never sees a charge. */
    fun prepareJoin(
        sender: ActivityResultSender,
        displayName: String?,
        skrName: String?,
        onReady: (PaySession) -> Unit,
        onError: (String?) -> Unit
    ) {
        if (_paying.value) return
        _paying.value = true
        viewModelScope.launch {
            fun fail(error: String?) {
                _paying.value = false
                onError(error)
            }
            val proof = ensureProof(sender).getOrElse {
                fail(it.message ?: "Sign-in proof failed")
                return@launch
            }
            when (val result = hostApi.createJoinSession(
                proof.wallet, displayName, skrName, proofJson(proof)
            )) {
                is ApiResult.Ok -> {
                    _paying.value = false
                    val session = PaySession.fromJson(
                        result.value.optJSONObject("session") ?: result.value
                    )
                    if (session != null) {
                        onReady(session)
                    } else {
                        onError("The host's join quote could not be read — nothing was charged.")
                    }
                }
                is ApiResult.Refused -> fail(result.verdict)
                ApiResult.Unreachable ->
                    fail("Ore Kingdom could not be reached — check your connection and try again.")
            }
        }
    }

    /** One by-reference completion attempt (join and prestige share
     *  the shape): the host looks the payment up by the session's
     *  reference — no signature is presented. A forward-apply answer
     *  (the payment landed but does not cover the price, A10) is
     *  terminal and surfaces in the host's own words. */
    private suspend fun byReferenceAttempt(
        proof: SiwsProof,
        sessionId: String,
        prestige: Boolean
    ): PayOutcome {
        val result = if (prestige) {
            hostApi.completePrestigeSession(proof.wallet, sessionId, proofJson(proof))
        } else {
            hostApi.completeJoinSession(proof.wallet, sessionId, proofJson(proof))
        }
        return when (result) {
            is ApiResult.Ok -> {
                val data = result.value
                when {
                    data.optBoolean("ok", false) -> PayOutcome.Done()
                    data.optBoolean("forwardApplied", false) -> PayOutcome.Failed(
                        data.optString("error").ifBlank { "The payment was credited forward to your next paid action." }
                    )
                    else -> classifyError(data.optString("error").ifBlank { "Completion was refused." })
                }
            }
            is ApiResult.Refused -> classifyError(result.verdict)
            ApiResult.Unreachable -> PayOutcome.Retry("Ore Kingdom could not be reached.")
        }
    }

    /** Join step 2: pay the session exactly once, then complete by
     *  reference through the pending loop. */
    fun executeJoin(
        sender: ActivityResultSender,
        session: PaySession,
        onProgress: (String) -> Unit,
        onDone: (String?) -> Unit
    ) {
        if (_paying.value) return
        _paying.value = true
        viewModelScope.launch {
            fun done(error: String?) {
                _paying.value = false
                onDone(error)
            }
            val proof = ensureProof(sender).getOrElse {
                done(it.message ?: "Sign-in proof failed")
                return@launch
            }
            onProgress("Opening your wallet to pay…")
            payPaySession(sender, proof, session).getOrElse {
                done(it.message ?: "The payment was not sent.")
                return@launch
            }
            parkFlow(
                ParkedFlow(
                    kind = "join",
                    id = session.id,
                    wallet = proof.wallet,
                    signature = sessionStore.parkedSignature(session.id),
                    label = "Kingdom membership — the join",
                    createdAt = System.currentTimeMillis()
                )
            )
            onProgress("Payment sent — activating your membership…")
            when (val outcome = pendingLoop(onProgress) {
                byReferenceAttempt(proof, session.id, prestige = false)
            }) {
                is PayOutcome.Done -> {
                    unparkFlow("join", session.id)
                    loadForum()
                    done(null)
                }
                is PayOutcome.Failed -> {
                    unparkFlow("join", session.id)
                    done(outcome.message)
                }
                is PayOutcome.StillPending -> done(
                    "Your join payment is still confirming on-chain. It is saved under “Payments to finish” on the River — your membership activates from there without paying again."
                )
                is PayOutcome.Retry -> done("The payment is still confirming.")
            }
        }
    }

    // ---------- prestige (10,000 SKR, 100 places) ----------

    /** The prestige surface's status read: the caller's holder flag
     *  from the host (getPrestigeSession). */
    fun loadPrestigeStatus(sender: ActivityResultSender, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val proof = ensureProof(sender).getOrElse {
                onDone(it.message ?: "Sign-in proof failed")
                return@launch
            }
            when (val result = hostApi.getPrestigeSession(proof.wallet, null, proofJson(proof))) {
                is ApiResult.Ok -> {
                    _isPrestigeHolder.value = result.value.optBoolean("prestige", false)
                    onDone(null)
                }
                is ApiResult.Refused -> onDone(result.verdict)
                ApiResult.Unreachable ->
                    onDone("Ore Kingdom could not be reached — check your connection and try again.")
            }
        }
    }

    /** Prestige step 1: open the session (the host re-answers an
     *  already-open pending session rather than duplicating it, and
     *  refuses holders and the sold-out state by name). The served
     *  sessions carry no claimed-count, so the surface shows the cap
     *  without a count — never an invented one. */
    fun preparePrestige(
        sender: ActivityResultSender,
        onReady: (PaySession) -> Unit,
        onError: (String?) -> Unit
    ) {
        if (_paying.value) return
        _paying.value = true
        viewModelScope.launch {
            fun fail(error: String?) {
                _paying.value = false
                onError(error)
            }
            val proof = ensureProof(sender).getOrElse {
                fail(it.message ?: "Sign-in proof failed")
                return@launch
            }
            when (val result = hostApi.createPrestigeSession(proof.wallet, proofJson(proof))) {
                is ApiResult.Ok -> {
                    _paying.value = false
                    val session = PaySession.fromJson(
                        result.value.optJSONObject("session") ?: result.value
                    )
                    if (session != null) {
                        onReady(session)
                    } else {
                        onError("The host's prestige quote could not be read — nothing was charged.")
                    }
                }
                is ApiResult.Refused -> fail(result.verdict)
                ApiResult.Unreachable ->
                    fail("Ore Kingdom could not be reached — check your connection and try again.")
            }
        }
    }

    /** Prestige step 2: pay once, complete by reference. */
    fun executePrestige(
        sender: ActivityResultSender,
        session: PaySession,
        onProgress: (String) -> Unit,
        onDone: (String?) -> Unit
    ) {
        if (_paying.value) return
        _paying.value = true
        viewModelScope.launch {
            fun done(error: String?) {
                _paying.value = false
                onDone(error)
            }
            val proof = ensureProof(sender).getOrElse {
                done(it.message ?: "Sign-in proof failed")
                return@launch
            }
            onProgress("Opening your wallet to pay…")
            payPaySession(sender, proof, session).getOrElse {
                done(it.message ?: "The payment was not sent.")
                return@launch
            }
            parkFlow(
                ParkedFlow(
                    kind = "prestige",
                    id = session.id,
                    wallet = proof.wallet,
                    signature = sessionStore.parkedSignature(session.id),
                    label = "Prestige — 10,000 SKR",
                    createdAt = System.currentTimeMillis()
                )
            )
            onProgress("Payment sent — claiming your prestige…")
            when (val outcome = pendingLoop(onProgress) {
                byReferenceAttempt(proof, session.id, prestige = true)
            }) {
                is PayOutcome.Done -> {
                    unparkFlow("prestige", session.id)
                    _isPrestigeHolder.value = true
                    loadForum()
                    done(null)
                }
                is PayOutcome.Failed -> {
                    unparkFlow("prestige", session.id)
                    done(outcome.message)
                }
                is PayOutcome.StillPending -> done(
                    "Your prestige payment is still confirming on-chain. It is saved under “Payments to finish” on the River — your prestige activates from there without paying again."
                )
                is PayOutcome.Retry -> done("The payment is still confirming.")
            }
        }
    }

    // ---------- tips: the two-leg transaction (double opt-in) ----------

    /**
     * Tip step 1 — compose resolves to a checkout. The client-side
     * ceiling and floor (tip safety ruling) refuse before any host
     * round-trip: above [TIP_MAX_USD_CENTS] or below
     * [TIP_MIN_USD_CENTS] nothing is created. An outstanding-fee
     * refusal from the host surfaces verbatim — it names the settle
     * path itself.
     */
    fun prepareTip(
        sender: ActivityResultSender,
        threadId: Int?,
        replyId: Int?,
        asset: AssetSpec,
        amountUsdCents: Int,
        onReady: (TipCheckout) -> Unit,
        onError: (String?) -> Unit
    ) {
        if (amountUsdCents > TIP_MAX_USD_CENTS) {
            onError(
                "Tips are capped at ${formatUsdCents(TIP_MAX_USD_CENTS)} by the tip safety rule — lower the amount and try again. Nothing was sent."
            )
            return
        }
        if (amountUsdCents < TIP_MIN_USD_CENTS) {
            onError("The minimum tip is ${formatUsdCents(TIP_MIN_USD_CENTS)}. Nothing was sent.")
            return
        }
        if (_paying.value) return
        _paying.value = true
        viewModelScope.launch {
            fun fail(error: String?) {
                _paying.value = false
                onError(error)
            }
            val proof = ensureProof(sender).getOrElse {
                fail(it.message ?: "Sign-in proof failed")
                return@launch
            }
            when (val result = hostApi.createTipCheckout(
                proof.wallet, asset.code, threadId, replyId, amountUsdCents, proofJson(proof)
            )) {
                is ApiResult.Ok -> {
                    _paying.value = false
                    val checkout = TipCheckout.fromJson(result.value)
                    if (checkout != null) {
                        onReady(checkout)
                    } else {
                        onError("The host's tip quote could not be read — nothing was sent.")
                    }
                }
                is ApiResult.Refused -> fail(result.verdict)
                ApiResult.Unreachable ->
                    fail("Ore Kingdom could not be reached — check your connection and try again.")
            }
        }
    }

    private suspend fun tipAttempt(
        proof: SiwsProof,
        checkoutId: String,
        signature: String
    ): PayOutcome {
        return when (val result = hostApi.completeTipCheckout(
            proof.wallet, checkoutId, signature, proofJson(proof)
        )) {
            is ApiResult.Ok -> if (result.value.optBoolean("ok", false)) {
                PayOutcome.Done()
            } else {
                classifyError(result.value.optString("error").ifBlank { "The tip was refused." })
            }
            is ApiResult.Refused -> classifyError(result.verdict)
            ApiResult.Unreachable -> PayOutcome.Retry("Ore Kingdom could not be reached.")
        }
    }

    /**
     * Tip step 2 — the explicit confirm behind the review sheet. The
     * confirm re-checks the arming condition here (defense in depth
     * behind the sheet's own disabled state): unless the fee leg is
     * fully served — its amount AND its own reference key — and the
     * sheet total equals the checkout's stored total, nothing is
     * signed. When it arms, ONE transaction carries both legs (tip →
     * author, fee → Relay), each with its own reference (A7).
     */
    fun executeTip(
        sender: ActivityResultSender,
        checkout: TipCheckout,
        threadId: Int?,
        onProgress: (String) -> Unit,
        onDone: (String?) -> Unit
    ) {
        val sheetTotal = checkout.reviewTotalBase()
        if (sheetTotal == null || !tipConfirmArmed(checkout, sheetTotal)) {
            onDone(
                "The host did not serve the platform fee's payment reference for this checkout, so the $1 fee leg cannot be built in a form the host can verify. Nothing was sent and nothing was charged."
            )
            return
        }
        if (_paying.value) return
        _paying.value = true
        viewModelScope.launch {
            fun done(error: String?) {
                _paying.value = false
                onDone(error)
            }
            val proof = ensureProof(sender).getOrElse {
                done(it.message ?: "Sign-in proof failed")
                return@launch
            }
            val fee = checkout.fee!!
            // A parked signature means this checkout was already paid
            // once: never build a second payment for it — the
            // completion loop below is the only way forward (R3/R4).
            val parked = sessionStore.parkedSignature(checkout.checkoutId)
            val signature = if (parked != null) {
                parked
            } else {
                onProgress("Opening your wallet to sign the tip…")
                signPayment(sender, proof.wallet, checkout.checkoutId) { payer, blockhash ->
                    PaymentTx.buildTipPayment(
                        payer = payer,
                        asset = checkout.asset,
                        tipDestinationWallet = Base58.decode(checkout.recipientWallet),
                        tipAmountBase = checkout.tipAmountBase,
                        tipReference = Base58.decode(checkout.tipReference),
                        feeDestinationWallet = Base58.decode(fee.destination ?: RELAY_WALLET),
                        feeAmountBase = fee.amountBase,
                        feeReference = Base58.decode(fee.reference!!),
                        recentBlockhash = blockhash
                    )
                }.getOrElse {
                    done(it.message ?: "The tip was not sent.")
                    return@launch
                }
            }
            parkFlow(
                ParkedFlow(
                    kind = "tip",
                    id = checkout.checkoutId,
                    wallet = proof.wallet,
                    signature = signature,
                    label = "Tip — ${formatUsdCents(checkout.amountUsdCents)} to " +
                        (checkout.recipientSkrName ?: "the author"),
                    createdAt = System.currentTimeMillis(),
                    threadId = threadId
                )
            )
            onProgress("Tip sent — confirming…")
            when (val outcome = pendingLoop(onProgress) {
                tipAttempt(proof, checkout.checkoutId, signature)
            }) {
                is PayOutcome.Done -> {
                    unparkFlow("tip", checkout.checkoutId)
                    loadForum()
                    threadId?.let { loadThread(it) }
                    done(null)
                }
                // A refused completion stays parked: the payment may
                // already have landed, and the record is the member's
                // path to finishing (or settling the fee) — never to
                // paying twice.
                is PayOutcome.Failed -> done(outcome.message)
                is PayOutcome.StillPending -> done(
                    "Your tip is still confirming on-chain. It is saved under “Payments to finish” on the River — do not tip again for this post until it settles."
                )
                is PayOutcome.Retry -> done("The tip is still confirming.")
            }
        }
    }

    /**
     * The A7 fallback settle: pay an OUTSTANDING fee leg on its own
     * (a tip whose tip leg landed but whose fee leg never verified),
     * so the member can tip again. The fee leg pays alone, from the
     * served checkout's own fields; until the host serves the leg's
     * reference this halts with the gap named, before signing.
     */
    fun settleOutstandingTipFee(
        sender: ActivityResultSender,
        checkout: TipCheckout,
        onProgress: (String) -> Unit,
        onDone: (String?) -> Unit
    ) {
        val fee = checkout.fee
        if (fee == null || fee.reference.isNullOrEmpty()) {
            onDone(
                "The host did not serve the outstanding fee leg's payment reference, so it cannot be settled from here yet. Nothing was sent."
            )
            return
        }
        if (_paying.value) return
        _paying.value = true
        viewModelScope.launch {
            fun done(error: String?) {
                _paying.value = false
                onDone(error)
            }
            val proof = ensureProof(sender).getOrElse {
                done(it.message ?: "Sign-in proof failed")
                return@launch
            }
            onProgress("Opening your wallet to settle the fee…")
            val signature = signPayment(sender, proof.wallet, checkout.checkoutId + ":fee") { payer, blockhash ->
                PaymentTx.buildSessionPayment(
                    payer = payer,
                    asset = fee.asset ?: checkout.asset,
                    destinationWallet = Base58.decode(fee.destination ?: RELAY_WALLET),
                    amountBase = fee.amountBase,
                    reference = Base58.decode(fee.reference!!),
                    recentBlockhash = blockhash
                )
            }.getOrElse {
                done(it.message ?: "The fee payment was not sent.")
                return@launch
            }
            when (val result = hostApi.payOutstandingTipFee(
                proof.wallet, checkout.checkoutId, signature, proofJson(proof)
            )) {
                is ApiResult.Ok -> if (result.value.optBoolean("ok", false)) {
                    done(null)
                } else {
                    done(result.value.optString("error").ifBlank { "The fee settle was refused." })
                }
                is ApiResult.Refused -> done(result.verdict)
                ApiResult.Unreachable ->
                    done("Ore Kingdom could not be reached — check your connection and try again.")
            }
        }
    }

    // ---------- paid pins ($250 / 12h, 2 slots) ----------

    /** Pin step 1: open the checkout (own threads only; refused at
     *  create when both slots are spoken for — nothing charged). */
    fun preparePin(
        sender: ActivityResultSender,
        threadId: Int,
        asset: AssetSpec,
        onReady: (PinCheckout) -> Unit,
        onError: (String?) -> Unit
    ) {
        if (_paying.value) return
        _paying.value = true
        viewModelScope.launch {
            fun fail(error: String?) {
                _paying.value = false
                onError(error)
            }
            val proof = ensureProof(sender).getOrElse {
                fail(it.message ?: "Sign-in proof failed")
                return@launch
            }
            when (val result = hostApi.createPaidPinCheckout(
                proof.wallet, threadId, asset.code, proofJson(proof)
            )) {
                is ApiResult.Ok -> {
                    _paying.value = false
                    val checkout = PinCheckout.fromJson(result.value)
                    if (checkout != null) {
                        onReady(checkout)
                    } else {
                        onError("The host's pin quote could not be read — nothing was charged.")
                    }
                }
                is ApiResult.Refused -> fail(result.verdict)
                ApiResult.Unreachable ->
                    fail("Ore Kingdom could not be reached — check your connection and try again.")
            }
        }
    }

    private data class PinAttempt(val outcome: PayOutcome, val deferred: Boolean = false)

    private suspend fun pinAttempt(
        proof: SiwsProof,
        checkoutId: String,
        signature: String
    ): PinAttempt {
        return when (val result = hostApi.completePaidPinCheckout(
            proof.wallet, checkoutId, signature, proofJson(proof)
        )) {
            is ApiResult.Ok -> if (result.value.optBoolean("ok", false)) {
                PinAttempt(PayOutcome.Done(), deferred = result.value.optBoolean("pinDeferred", false))
            } else {
                PinAttempt(classifyError(result.value.optString("error").ifBlank { "The pin was refused." }))
            }
            is ApiResult.Refused -> PinAttempt(classifyError(result.verdict))
            ApiResult.Unreachable -> PinAttempt(PayOutcome.Retry("Ore Kingdom could not be reached."))
        }
    }

    private suspend fun pinPendingLoop(
        proof: SiwsProof,
        checkoutId: String,
        signature: String,
        onProgress: (String) -> Unit
    ): PinAttempt {
        var last = PinAttempt(PayOutcome.Failed("No attempt was made."))
        repeat(15) { i ->
            val attempt = pinAttempt(proof, checkoutId, signature)
            last = attempt
            if (attempt.outcome !is PayOutcome.Retry) return attempt
            if (i < 14) {
                onProgress("Waiting for the payment to finalize on-chain — checking again…")
                delay(4_000)
            }
        }
        val retry = last.outcome as PayOutcome.Retry
        return PinAttempt(PayOutcome.StillPending(retry.message))
    }

    /**
     * Pin step 2: pay once, complete with the signature. A completion
     * the host defers (both slots filled between create and
     * completion, A9) is a success state, shown truthfully — the pin
     * activates when a slot frees; it is never re-charged.
     */
    fun executePin(
        sender: ActivityResultSender,
        checkout: PinCheckout,
        threadId: Int,
        threadTitle: String,
        onProgress: (String) -> Unit,
        onDone: (String?, Boolean) -> Unit
    ) {
        if (!checkout.confirmable) {
            onDone(
                "The host did not serve this pin checkout's payment reference, so the payment cannot be built in a form the host can verify. Nothing was sent and nothing was charged.",
                false
            )
            return
        }
        if (_paying.value) return
        _paying.value = true
        viewModelScope.launch {
            fun done(error: String?, deferred: Boolean = false) {
                _paying.value = false
                onDone(error, deferred)
            }
            val proof = ensureProof(sender).getOrElse {
                done(it.message ?: "Sign-in proof failed")
                return@launch
            }
            // Same never-pay-twice guard as the tip: a parked
            // signature means this checkout was already paid.
            val parked = sessionStore.parkedSignature(checkout.checkoutId)
            val signature = if (parked != null) {
                parked
            } else {
                onProgress("Opening your wallet to pay…")
                signPayment(sender, proof.wallet, checkout.checkoutId) { payer, blockhash ->
                    PaymentTx.buildSessionPayment(
                        payer = payer,
                        asset = checkout.asset,
                        destinationWallet = Base58.decode(checkout.destinationWallet),
                        amountBase = checkout.amountBase,
                        reference = Base58.decode(checkout.reference!!),
                        recentBlockhash = blockhash
                    )
                }.getOrElse {
                    done(it.message ?: "The payment was not sent.")
                    return@launch
                }
            }
            parkFlow(
                ParkedFlow(
                    kind = "pin",
                    id = checkout.checkoutId,
                    wallet = proof.wallet,
                    signature = signature,
                    label = "Pin — $threadTitle",
                    createdAt = System.currentTimeMillis(),
                    threadId = threadId
                )
            )
            onProgress("Payment sent — starting your pin…")
            val attempt = pinPendingLoop(proof, checkout.checkoutId, signature, onProgress)
            when (val outcome = attempt.outcome) {
                is PayOutcome.Done -> {
                    unparkFlow("pin", checkout.checkoutId)
                    loadPinSlots()
                    loadForum()
                    done(null, attempt.deferred)
                }
                is PayOutcome.Failed -> done(outcome.message)
                is PayOutcome.StillPending -> done(
                    "Your pin payment is still confirming on-chain. It is saved under “Payments to finish” on the River — the pin starts from there without paying again."
                )
                is PayOutcome.Retry -> done("The payment is still confirming.")
            }
        }
    }

    // ---------- R4: resume-after-abandon ----------

    /**
     * Finish a parked flow against the host's CURRENT read of it.
     * Wrapper sessions are re-read first — their status decides
     * (dispatch, complete, or drop with the truth told); the bundle
     * checkouts have no read, so their parked signature re-runs the
     * idempotent completion. Nothing here ever builds a second
     * payment.
     */
    fun resumeParked(
        sender: ActivityResultSender,
        flow: ParkedFlow,
        onProgress: (String) -> Unit,
        onDone: (String?) -> Unit
    ) {
        if (_paying.value) return
        _paying.value = true
        viewModelScope.launch {
            fun done(error: String?) {
                _paying.value = false
                onDone(error)
            }
            val proof = ensureProof(sender).getOrElse {
                done(it.message ?: "Sign-in proof failed")
                return@launch
            }
            if (proof.wallet != flow.wallet) {
                done("That payment belongs to a different wallet than the one connected — connect the paying wallet to finish it.")
                return@launch
            }
            val message = when (flow.kind) {
                "post", "commercial", "reply" -> resumePostFlow(proof, flow, onProgress)
                "join" -> resumeByReference(proof, flow, prestige = false, onProgress)
                "prestige" -> resumeByReference(proof, flow, prestige = true, onProgress)
                "tip" -> resumeTip(proof, flow, onProgress)
                "pin" -> resumePin(proof, flow, onProgress)
                else -> "That parked payment can't be read by this build."
            }
            done(message)
        }
    }

    private suspend fun resumePostFlow(
        proof: SiwsProof,
        flow: ParkedFlow,
        onProgress: (String) -> Unit
    ): String? {
        val commercial = flow.kind == "commercial"
        val read = if (commercial) {
            hostApi.getCommercialSession(proof.wallet, flow.id, proofJson(proof))
        } else {
            hostApi.getPostSession(proof.wallet, flow.id, proofJson(proof))
        }
        val sessionJson = when (read) {
            is ApiResult.Ok -> read.value.optJSONObject("session")
                ?: return "The host no longer serves that session. Any amount that did land is credited forward to your next paid action."
            is ApiResult.Refused -> return read.verdict
            ApiResult.Unreachable -> return "Ore Kingdom could not be reached — check your connection and try again."
        }
        val session = PaySession.fromJson(sessionJson)
            ?: return "That session could not be read — nothing was charged from here."
        return when (session.status) {
            "consumed" -> {
                unparkFlow(flow.kind, flow.id)
                "That payment already did its work — the post is live."
            }
            "expired" -> {
                unparkFlow(flow.kind, flow.id)
                "That payment session expired before it was used, so it can't be finished. Any amount that did land is credited forward to your next paid action — start a fresh post when you're ready."
            }
            "forward_applied" -> {
                unparkFlow(flow.kind, flow.id)
                "That payment was credited forward to your next paid action — there is nothing more to finish here."
            }
            else -> when (val outcome = pendingLoop(onProgress) {
                postDispatchAttempt(proof, flow.id, commercial, flow.categoryId, flow.title, flow.body ?: "", flow.threadId)
            }) {
                is PayOutcome.Done -> {
                    unparkFlow(flow.kind, flow.id)
                    loadForum()
                    flow.threadId?.let { loadThread(it) }
                    null
                }
                is PayOutcome.Failed -> {
                    unparkFlow(flow.kind, flow.id)
                    outcome.message
                }
                is PayOutcome.StillPending ->
                    "Still confirming on-chain — it stays saved here; try finishing it again in a little while. Nothing more to pay."
                is PayOutcome.Retry -> "Still confirming on-chain."
            }
        }
    }

    private suspend fun resumeByReference(
        proof: SiwsProof,
        flow: ParkedFlow,
        prestige: Boolean,
        onProgress: (String) -> Unit
    ): String? {
        val read = if (prestige) {
            hostApi.getPrestigeSession(proof.wallet, flow.id, proofJson(proof))
        } else {
            hostApi.getJoinSession(proof.wallet, flow.id, proofJson(proof))
        }
        val data = when (read) {
            is ApiResult.Ok -> read.value
            is ApiResult.Refused -> return read.verdict
            ApiResult.Unreachable -> return "Ore Kingdom could not be reached — check your connection and try again."
        }
        val status = data.optJSONObject("session")?.optString("status") ?: ""
        return when (status) {
            "completed" -> {
                unparkFlow(flow.kind, flow.id)
                if (prestige) _isPrestigeHolder.value = true
                loadForum()
                null
            }
            "forward_applied" -> {
                unparkFlow(flow.kind, flow.id)
                "That payment was credited forward to your next paid action — there is nothing more to finish here."
            }
            "expired" -> {
                unparkFlow(flow.kind, flow.id)
                "That payment session expired before it completed, so it can't be finished. Any amount that did land is credited forward to your next paid action."
            }
            else -> when (val outcome = pendingLoop(onProgress) {
                byReferenceAttempt(proof, flow.id, prestige)
            }) {
                is PayOutcome.Done -> {
                    unparkFlow(flow.kind, flow.id)
                    if (prestige) _isPrestigeHolder.value = true
                    loadForum()
                    null
                }
                is PayOutcome.Failed -> {
                    unparkFlow(flow.kind, flow.id)
                    outcome.message
                }
                is PayOutcome.StillPending ->
                    "Still confirming on-chain — it stays saved here; try finishing it again in a little while. Nothing more to pay."
                is PayOutcome.Retry -> "Still confirming on-chain."
            }
        }
    }

    private suspend fun resumeTip(
        proof: SiwsProof,
        flow: ParkedFlow,
        onProgress: (String) -> Unit
    ): String? {
        val signature = flow.signature ?: sessionStore.parkedSignature(flow.id)
            ?: return "No signature was parked for that tip — it was never sent, so there is nothing to complete. You can dismiss it from the list."
        return when (val outcome = pendingLoop(onProgress) {
            tipAttempt(proof, flow.id, signature)
        }) {
            is PayOutcome.Done -> {
                unparkFlow("tip", flow.id)
                loadForum()
                flow.threadId?.let { loadThread(it) }
                null
            }
            // Stays parked on refusal: the payment may have landed,
            // and the record is the member's path to finishing —
            // never to paying twice.
            is PayOutcome.Failed -> outcome.message
            is PayOutcome.StillPending ->
                "Still confirming on-chain — it stays saved here; try finishing it again in a little while. Nothing more to pay."
            is PayOutcome.Retry -> "Still confirming on-chain."
        }
    }

    private suspend fun resumePin(
        proof: SiwsProof,
        flow: ParkedFlow,
        onProgress: (String) -> Unit
    ): String? {
        val signature = flow.signature ?: sessionStore.parkedSignature(flow.id)
            ?: return "No signature was parked for that pin — it was never sent, so there is nothing to complete. You can dismiss it from the list."
        val attempt = pinPendingLoop(proof, flow.id, signature, onProgress)
        return when (val outcome = attempt.outcome) {
            is PayOutcome.Done -> {
                unparkFlow("pin", flow.id)
                loadPinSlots()
                loadForum()
                if (attempt.deferred) {
                    "Paid and queued — your pin activates when a slot frees."
                } else {
                    null
                }
            }
            is PayOutcome.Failed -> outcome.message
            is PayOutcome.StillPending ->
                "Still confirming on-chain — it stays saved here; try finishing it again in a little while. Nothing more to pay."
            is PayOutcome.Retry -> "Still confirming on-chain."
        }
    }
}
