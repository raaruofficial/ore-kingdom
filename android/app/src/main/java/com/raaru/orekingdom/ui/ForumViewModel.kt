package com.raaru.orekingdom.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.raaru.orekingdom.OreKingdomApp
import com.raaru.orekingdom.data.ApiResult
import com.raaru.orekingdom.data.ForumPayload
import com.raaru.orekingdom.data.Member
import com.raaru.orekingdom.data.ThreadDetail
import com.raaru.orekingdom.util.Base58
import com.raaru.orekingdom.util.SiwsMessage
import com.raaru.orekingdom.wallet.WalletUiState
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * M1 view model: identity (R0) + the read surfaces.
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

    private val _member = MutableStateFlow<Member?>(null)
    val member: StateFlow<Member?> = _member.asStateFlow()

    private val _forumState = MutableStateFlow<LoadState<ForumPayload>>(LoadState.Loading)
    val forumState: StateFlow<LoadState<ForumPayload>> = _forumState.asStateFlow()

    private val _selectedCategory = MutableStateFlow<Int?>(null)
    val selectedCategory: StateFlow<Int?> = _selectedCategory.asStateFlow()

    private val _threadState = MutableStateFlow<LoadState<ThreadDetail>>(LoadState.Loading)
    val threadState: StateFlow<LoadState<ThreadDetail>> = _threadState.asStateFlow()

    /** The host's refusal string for the open thread, when it answered
     *  {ok:false} — rendered verbatim (error truthfulness), never
     *  paraphrased into a generic failure. */
    private val _threadRefusal = MutableStateFlow<String?>(null)
    val threadRefusal: StateFlow<String?> = _threadRefusal.asStateFlow()

    private val _signingIn = MutableStateFlow(false)
    val signingIn: StateFlow<Boolean> = _signingIn.asStateFlow()

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

    fun selectCategory(categoryId: Int?) {
        _selectedCategory.value = categoryId
        loadForum()
    }

    fun loadForum() {
        val categoryId = _selectedCategory.value
        val cacheKey = if (categoryId == null) "forum_all" else "forum_cat_$categoryId"
        viewModelScope.launch {
            _forumState.value = LoadState.Loading
            when (val result = hostApi.listForum(wallet = sessionStore.wallet, categoryId = categoryId)) {
                is ApiResult.Ok -> {
                    cacheStore.put(cacheKey, result.value)
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
                    val cached = cacheStore.get(cacheKey)
                    _forumState.value = if (cached != null) {
                        LoadState.Ready(ForumPayload.fromJson(cached), stale = true)
                    } else {
                        LoadState.Unreachable
                    }
                }
            }
        }
    }

    fun loadThread(threadId: Int) {
        val cacheKey = "thread_$threadId"
        viewModelScope.launch {
            _threadState.value = LoadState.Loading
            _threadRefusal.value = null
            when (val result = hostApi.getThread(wallet = sessionStore.wallet ?: "", threadId = threadId)) {
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
            val walletManager = app.walletManager
            val address: String = if (walletManager.isConnected) {
                walletManager.publicKeyBase58!!
            } else {
                val connectResult = walletManager.connect(sender)
                if (connectResult.isFailure) {
                    done(connectResult.exceptionOrNull()?.message ?: "Wallet connection failed")
                    return@launch
                }
                connectResult.getOrThrow()
            }
            // Persist the MWA auth token for quiet reauthorization.
            sessionStore.authToken = walletManager.persistedAuthToken

            val message = SiwsMessage.build(address)
            val signatureResult = walletManager.signMessageRaw(sender, message)
            if (signatureResult.isFailure) {
                done(signatureResult.exceptionOrNull()?.message ?: "Wallet signing failed")
                return@launch
            }
            val signature = signatureResult.getOrThrow()
            val publicKey = try {
                Base58.decode(address)
            } catch (e: Exception) {
                done("Wallet returned an unreadable address")
                return@launch
            }
            when (val result = hostApi.authenticateSiws(address, message, signature, publicKey)) {
                is ApiResult.Ok -> {
                    if (result.value.optBoolean("ok", false)) {
                        sessionStore.wallet = address
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
            _member.value = null
            loadForum()
        }
    }
}
