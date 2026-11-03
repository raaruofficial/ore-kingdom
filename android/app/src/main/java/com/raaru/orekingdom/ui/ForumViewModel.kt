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
import com.raaru.orekingdom.data.ThreadDetail
import com.raaru.orekingdom.util.Base58
import com.raaru.orekingdom.util.SiwsMessage
import com.raaru.orekingdom.wallet.WalletUiState
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
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

    private val _posting = MutableStateFlow(false)
    val posting: StateFlow<Boolean> = _posting.asStateFlow()

    init {
        // Restore the displayed identity (wallet + .skr name) from the
        // last successful sign-in; reads below re-confirm it against
        // the host's own member record.
        val wallet = sessionStore.wallet
        val skr = sessionStore.skrName
        if (wallet != null && skr != null) {
            _member.value = Member(
                id = 0, wallet = wallet, displayName = skr, skrName = skr,
                reputation = 0, tier = "", isFounder = false,
                isFoundingMember = false, foundingNumber = null, foundingTranche = null
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
     * client-side, and the pin strip derives from the full set, so a
     * board view never hides a live pin. A cached SIWS proof rides
     * along so a verified prestige holder's payload includes the VIP
     * board's threads (wrapper read release, M2_HOST_REPORT §4b).
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

    // ---------- composer (payment-session wiring is M3) ----------

    /**
     * Start a thread. Carries the SIWS proof through the wrapper's
     * post gate: a prestige holder's thread passes session-free by the
     * wrapper's own status read; a VIP-board thread is a createThread
     * into the VIP category and passes only for a holder. Any other
     * refusal (including the standing thread fee, whose session flow
     * is M3) surfaces verbatim — error truthfulness, never a fake
     * success. [onDone] receives null on success or the error.
     */
    fun postThread(
        sender: ActivityResultSender,
        categoryId: Int,
        title: String,
        body: String,
        onDone: (String?) -> Unit
    ) {
        if (_posting.value) return
        _posting.value = true
        viewModelScope.launch {
            fun done(error: String?) {
                _posting.value = false
                onDone(error)
            }
            val proof = ensureProof(sender).getOrElse {
                done(it.message ?: "Sign-in proof failed")
                return@launch
            }
            when (val result = hostApi.createThread(
                proof.wallet, categoryId, title, body, proofJson(proof)
            )) {
                is ApiResult.Ok -> {
                    if (result.value.optBoolean("ok", false)) {
                        loadForum()
                        done(null)
                    } else {
                        done(result.value.optString("error").ifBlank { "The post was refused." })
                    }
                }
                is ApiResult.Refused -> done(result.verdict)
                ApiResult.Unreachable ->
                    done("Ore Kingdom could not be reached — check your connection and try again.")
            }
        }
    }

    /** Reply to a thread. Same gate discipline as postThread; the
     *  wrapper's reply bump (R7) re-orders the river on success. */
    fun postReply(
        sender: ActivityResultSender,
        threadId: Int,
        body: String,
        onDone: (String?) -> Unit
    ) {
        if (_posting.value) return
        _posting.value = true
        viewModelScope.launch {
            fun done(error: String?) {
                _posting.value = false
                onDone(error)
            }
            val proof = ensureProof(sender).getOrElse {
                done(it.message ?: "Sign-in proof failed")
                return@launch
            }
            when (val result = hostApi.createReply(
                proof.wallet, threadId, body, proofJson(proof)
            )) {
                is ApiResult.Ok -> {
                    if (result.value.optBoolean("ok", false)) {
                        loadThread(threadId)
                        loadForum()
                        done(null)
                    } else {
                        done(result.value.optString("error").ifBlank { "The reply was refused." })
                    }
                }
                is ApiResult.Refused -> done(result.verdict)
                ApiResult.Unreachable ->
                    done("Ore Kingdom could not be reached — check your connection and try again.")
            }
        }
    }
}
