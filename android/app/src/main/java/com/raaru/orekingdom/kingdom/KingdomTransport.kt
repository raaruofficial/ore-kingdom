package com.raaru.orekingdom.kingdom

import com.raaru.orekingdom.channel.HistoryPageRequest
import com.raaru.orekingdom.channel.PresenceUi
import com.raaru.orekingdom.moderation.AppealSubmission
import com.raaru.orekingdom.moderation.ModActionRequest
import com.raaru.orekingdom.moderation.ModAppealDecision
import com.raaru.orekingdom.moderation.ReportSubmission
import com.raaru.orekingdom.shell.ChannelDto
import com.raaru.orekingdom.shell.EntitlementPush
import com.raaru.orekingdom.shell.Entitlements
import com.raaru.orekingdom.shell.ShellTransport
import com.raaru.orekingdom.shell.SpotlightDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.random.Random

/** Inbound contract frames, decoded. Routed by [KingdomSession]. */
sealed interface KingdomInbound {
    object Connected : KingdomInbound
    object Disconnected : KingdomInbound
    data class Ack(val forId: String, val messageId: String, val seq: Long) : KingdomInbound
    data class Message(val message: ContractMessage) : KingdomInbound
    data class MessageUpdate(val message: ContractMessage) : KingdomInbound
    data class MessageDelete(val channelId: String, val messageId: String, val seq: Long) : KingdomInbound
    data class Reaction(val channelId: String?, val messageId: String, val emoji: String, val count: Int, val actorIds: List<String>) : KingdomInbound
    data class Presence(val presence: PresenceUi) : KingdomInbound
    data class Resync(val channelId: String, val reason: String) : KingdomInbound
    data class Error(val forId: String?, val code: String, val message: String, val channelId: String?) : KingdomInbound
}

/* ---- Module / moderation read models (host REST reads; INTEGRATION_MAP §3.5) ---- */

data class PumpWalletRead(val addressShort: String, val balanceText: String, val tankStateText: String)
data class PumpRead(
    val wallets: List<PumpWalletRead>,
    val bankedDays: Int,
    val activeRemainingSeconds: Long?,
    val firstDayFree: Boolean,
    val atmState: String,
)
data class NightshiftFleetRead(val headline: String, val lines: List<Pair<String, String>>)
data class NightshiftSuggestionRead(val id: String, val title: String, val body: String)
data class NightshiftRead(val fleet: NightshiftFleetRead?, val suggestions: List<NightshiftSuggestionRead>)
data class IntelItemRead(val title: String, val body: String, val severity: String, val at: String)
data class PassesRead(val lifetimeHolder: Boolean, val prestigeSeatsRemaining: Int?)
data class CrownRead(val holderName: String?, val month: String?)
data class ModSnapshotRead(
    val body: String,
    val authorId: String,
    val channelId: String,
    val createdAt: String,
    val context: List<ContractMessage>,
)
data class ModHistoryRead(val rung: String, val reasonCategory: String, val at: String)
data class ModAppealRead(val statement: String, val sanctionBy: String, val state: String)
data class ModQueueItemRead(
    val reportId: String,
    val state: String,
    val claimedBy: String?,
    val reason: String,
    val reporterCount: Int,
    val snapshot: ModSnapshotRead,
    val authorHistory: List<ModHistoryRead>,
    val appeal: ModAppealRead?,
)
data class ModLogEntryRead(val actorId: String, val action: String, val targetId: String, val reason: String, val at: String)
data class SanctionRead(
    val sanctionId: String?,
    val rung: String,
    val reasonCategory: String,
    val durationSec: Long?,
    val appealState: String,
)
data class HistoryRead(val messages: List<ContractMessage>, val hasMore: Boolean)

data class SearchRead(val messages: List<ContractMessage>, val threads: List<ContractThread>)

/**
 * The host transport behind every client seam (INTEGRATION_MAP §3):
 * REST reads/writes over HttpURLConnection (the :app convention, see
 * HostApi) plus the single WSS connection the contract requires.
 * Decoding to the pinned shapes happens here, via [KingdomJson].
 *
 * REST surface (paths relative to [baseUrl]): the contract reads
 * (/api/entitlements, /api/channels, /api/spotlight, /api/history,
 * /api/search, /api/members, /api/me), the module read models
 * (/api/modules/pump, /api/modules/nightshift, /api/intel), and the
 * moderation/passes reads and actions (/api/passes, /api/crown,
 * /api/sanctions, /api/reports, /api/appeals, /api/mod/queue,
 * /api/mod/log, /api/mod/claim, /api/mod/action, /api/mod/appeal).
 */
class KingdomTransport(
    private val baseUrl: String,
    private val sessionToken: () -> String? = { null },
    private val walletAddress: () -> String? = { null },
) : ShellTransport {

    private val _entitlementPushes = MutableSharedFlow<EntitlementPush>(extraBufferCapacity = 64)
    override val entitlementPushes: Flow<EntitlementPush> = _entitlementPushes.asSharedFlow()

    private val _frames = MutableSharedFlow<KingdomInbound>(extraBufferCapacity = 512)
    val frames: Flow<KingdomInbound> = _frames.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** The most recent channel list this transport fetched (DTOs, incl. slowmode + kinds). */
    private val _channelsSnapshot = MutableStateFlow<List<ChannelDto>>(emptyList())
    val channelsSnapshot: StateFlow<List<ChannelDto>> = _channelsSnapshot.asStateFlow()

    @Volatile private var wsClient: WsClient? = null

    /* ---- ShellTransport (REST + push) ---- */

    override suspend fun getEntitlements(): Entitlements =
        KingdomJson.parseEntitlements(getJson("/api/entitlements"))

    override suspend fun getChannels(): List<ChannelDto> {
        val channels = KingdomJson.parseChannels(getJson("/api/channels").getJSONArray("channels"))
        _channelsSnapshot.value = channels
        return channels
    }

    override suspend fun getSpotlight(): SpotlightDto =
        KingdomJson.parseSpotlight(getJson("/api/spotlight"))

    override suspend fun unsubscribe(channelIds: List<String>) {
        sendRaw(KingdomJson.unsubscribeFrame(channelIds))
    }

    /* ---- Connection management ---- */

    /** Starts the reconnect loop (jittered backoff, 1s → 30s cap, per INTEGRATION_MAP §3.3). */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            var backoffMs = 1_000L
            while (isActive) {
                val client = WsClient(wsUrl(), authHeaders(), object : WsClient.Listener {
                    override fun onOpen() {
                        _connected.value = true
                        backoffMs = 1_000L
                        _frames.tryEmit(KingdomInbound.Connected)
                    }

                    override fun onMessage(text: String) = dispatch(text)

                    override fun onClosed(reason: String) = Unit
                    override fun onFailure(error: Exception) = Unit
                })
                wsClient = client
                try {
                    client.run()
                } catch (_: Exception) {
                    // run() reports through the listener contract; a throw here is a dead connection.
                }
                wsClient = null
                val wasConnected = _connected.value
                _connected.value = false
                if (wasConnected) _frames.tryEmit(KingdomInbound.Disconnected)
                delay(backoffMs + Random.nextLong(0, 500))
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    /** Writes one raw frame; false when the socket is down (callers treat sends as retryable). */
    fun sendRaw(frame: String): Boolean = wsClient?.send(frame) ?: false

    private fun dispatch(text: String) {
        val envelope = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        val payload = envelope.optJSONObject("payload") ?: JSONObject()
        when (envelope.optString("type")) {
            "role.update", "entitlement.update" -> {
                val ents = payload.optJSONObject("entitlements") ?: return
                runCatching { KingdomJson.parseEntitlements(ents) }
                    .onSuccess { parsed ->
                        // SW2-03: the push may carry the server-filtered channel
                        // list. Absent or malformed → null, and the store
                        // refetches exactly as it did before this key existed.
                        val channels = payload.optJSONArray("channels")?.let { array ->
                            runCatching { KingdomJson.parseChannels(array) }.getOrNull()
                        }
                        _entitlementPushes.tryEmit(EntitlementPush(parsed, channels))
                    }
            }
            "ack" -> _frames.tryEmit(
                KingdomInbound.Ack(
                    forId = payload.getString("for_id"),
                    messageId = payload.getString("message_id"),
                    seq = payload.getLong("seq"),
                ),
            )
            "message" -> payload.optJSONObject("message")?.let {
                runCatching { KingdomJson.parseMessage(it) }
                    .onSuccess { m -> _frames.tryEmit(KingdomInbound.Message(m)) }
            }
            "message.update" -> payload.optJSONObject("message")?.let {
                runCatching { KingdomJson.parseMessage(it) }
                    .onSuccess { m -> _frames.tryEmit(KingdomInbound.MessageUpdate(m)) }
            }
            "message.delete" -> _frames.tryEmit(
                KingdomInbound.MessageDelete(
                    channelId = payload.getString("channel_id"),
                    messageId = payload.getString("message_id"),
                    seq = payload.getLong("seq"),
                ),
            )
            "reaction" -> _frames.tryEmit(
                KingdomInbound.Reaction(
                    channelId = envelope.optStringOrNull("channel_id"),
                    messageId = payload.getString("message_id"),
                    emoji = payload.getString("emoji"),
                    count = payload.getInt("count"),
                    actorIds = payload.optJSONArray("actor_ids")?.toStringList() ?: emptyList(),
                ),
            )
            "presence" -> payload.optJSONObject("presence")?.let {
                runCatching { KingdomJson.parsePresence(it) }
                    .onSuccess { p -> _frames.tryEmit(KingdomInbound.Presence(p)) }
            }
            "resync" -> _frames.tryEmit(
                KingdomInbound.Resync(
                    channelId = payload.getString("channel_id"),
                    reason = payload.optString("reason", "gap"),
                ),
            )
            "error" -> _frames.tryEmit(
                KingdomInbound.Error(
                    forId = payload.optStringOrNull("for_id"),
                    code = payload.optString("code", "error"),
                    message = payload.optString("message", ""),
                    channelId = payload.optStringOrNull("channel_id") ?: envelope.optStringOrNull("channel_id"),
                ),
            )
        }
    }

    /* ---- REST reads beyond the shell ---- */

    suspend fun getMe(): Pair<String, ContractMember> {
        val json = getJson("/api/me")
        return json.getString("user_id") to KingdomJson.parseMember(json.getJSONObject("member"))
    }

    suspend fun getMembers(): List<ContractMember> =
        getJson("/api/members").getJSONArray("members").let { array ->
            (0 until array.length()).map { KingdomJson.parseMember(array.getJSONObject(it)) }
        }

    suspend fun getHistory(request: HistoryPageRequest): HistoryRead {
        val query = buildString {
            append("channel_id=").append(URLEncoder.encode(request.channelId, "UTF-8"))
            if (request.beforeSeq != null) append("&before_seq=").append(request.beforeSeq)
            append("&limit=").append(request.limit)
        }
        val json = getJson("/api/history?$query")
        return HistoryRead(
            messages = json.getJSONArray("messages").let { array ->
                (0 until array.length()).map { KingdomJson.parseMessage(array.getJSONObject(it)) }
            },
            hasMore = json.getBoolean("has_more"),
        )
    }

    /** Thread list for one channel (server thread reads; 404 surfaces as a failed read). */
    suspend fun getThreads(channelId: String): List<ContractThread> =
        getJson("/api/threads?channel_id=" + URLEncoder.encode(channelId, "UTF-8"))
            .getJSONArray("threads").let { array ->
                (0 until array.length()).map { KingdomJson.parseThread(array.getJSONObject(it)) }
            }

    /** One thread's messages via the history read's thread_id filter. */
    suspend fun getThreadHistory(channelId: String, threadId: String): List<ContractMessage> {
        val query = "channel_id=" + URLEncoder.encode(channelId, "UTF-8") +
            "&thread_id=" + URLEncoder.encode(threadId, "UTF-8") + "&limit=200"
        return getJson("/api/history?$query").getJSONArray("messages").let { array ->
            (0 until array.length()).map { KingdomJson.parseMessage(array.getJSONObject(it)) }
        }
    }

    /** CONTRACT.md §7 search read: entitlement-filtered messages + threads. */
    suspend fun getSearch(query: String): SearchRead {
        val json = getJson("/api/search?q=" + URLEncoder.encode(query, "UTF-8"))
        return SearchRead(
            messages = json.getJSONArray("messages").let { array ->
                (0 until array.length()).map { KingdomJson.parseMessage(array.getJSONObject(it)) }
            },
            threads = json.getJSONArray("threads").let { array ->
                (0 until array.length()).map { KingdomJson.parseThread(array.getJSONObject(it)) }
            },
        )
    }

    suspend fun getPump(): PumpRead {
        val json = getJson("/api/modules/pump")
        val bank = json.getJSONObject("day_bank")
        return PumpRead(
            wallets = json.getJSONArray("wallets").let { array ->
                (0 until array.length()).map {
                    val w = array.getJSONObject(it)
                    PumpWalletRead(w.getString("address_short"), w.getString("balance_text"), w.getString("tank_state_text"))
                }
            },
            bankedDays = bank.getInt("banked_days"),
            activeRemainingSeconds = if (bank.isNull("active_remaining_seconds")) null else bank.getLong("active_remaining_seconds"),
            firstDayFree = bank.getBoolean("first_day_free"),
            atmState = json.getJSONObject("atm").getString("state"),
        )
    }

    suspend fun getNightshift(): NightshiftRead {
        val json = getJson("/api/modules/nightshift")
        val fleet = if (json.isNull("fleet")) null else json.getJSONObject("fleet").let { f ->
            NightshiftFleetRead(
                headline = f.getString("headline"),
                lines = f.getJSONArray("lines").let { array ->
                    (0 until array.length()).map {
                        val line = array.getJSONObject(it)
                        line.getString("label") to line.getString("value")
                    }
                },
            )
        }
        return NightshiftRead(
            fleet = fleet,
            suggestions = json.getJSONArray("suggestions").let { array ->
                (0 until array.length()).map {
                    val s = array.getJSONObject(it)
                    NightshiftSuggestionRead(s.getString("id"), s.getString("title"), s.getString("body"))
                }
            },
        )
    }

    suspend fun getIntel(): List<IntelItemRead> =
        getJson("/api/intel").getJSONArray("items").let { array ->
            (0 until array.length()).map {
                val item = array.getJSONObject(it)
                IntelItemRead(item.getString("title"), item.getString("body"), item.getString("severity"), item.getString("at"))
            }
        }

    suspend fun getPasses(): PassesRead {
        val json = getJson("/api/passes")
        return PassesRead(
            lifetimeHolder = json.getBoolean("lifetime_holder"),
            prestigeSeatsRemaining = if (json.isNull("prestige_seats_remaining")) null else json.getInt("prestige_seats_remaining"),
        )
    }

    suspend fun getCrown(): CrownRead {
        val json = getJson("/api/crown")
        return CrownRead(json.optStringOrNull("holder_name"), json.optStringOrNull("month"))
    }

    suspend fun getModQueue(): List<ModQueueItemRead> =
        getJson("/api/mod/queue").getJSONArray("items").let { array ->
            (0 until array.length()).map { parseModQueueItem(array.getJSONObject(it)) }
        }

    suspend fun getModLog(): List<ModLogEntryRead> =
        getJson("/api/mod/log").getJSONArray("entries").let { array ->
            (0 until array.length()).map {
                val e = array.getJSONObject(it)
                ModLogEntryRead(e.getString("actor_id"), e.getString("action"), e.getString("target_id"), e.getString("reason"), e.getString("at"))
            }
        }

    suspend fun getSanctions(): List<SanctionRead> =
        getJson("/api/sanctions").getJSONArray("sanctions").let { array ->
            (0 until array.length()).map {
                val s = array.getJSONObject(it)
                SanctionRead(
                    sanctionId = s.optStringOrNull("sanction_id"),
                    rung = s.getString("rung"),
                    reasonCategory = s.getString("reason_category"),
                    durationSec = if (s.isNull("duration_sec")) null else s.getLong("duration_sec"),
                    appealState = s.optJSONObject("appeal")?.optString("state", "none") ?: "none",
                )
            }
        }

    private fun parseModQueueItem(json: JSONObject): ModQueueItemRead {
        val snapshot = json.getJSONObject("snapshot")
        val appeal = if (json.isNull("appeal")) null else json.getJSONObject("appeal")
        return ModQueueItemRead(
            reportId = json.getString("report_id"),
            state = json.getString("state"),
            claimedBy = json.optStringOrNull("claimed_by"),
            reason = json.getString("reason"),
            reporterCount = json.getInt("reporter_count"),
            snapshot = ModSnapshotRead(
                body = snapshot.getString("body"),
                authorId = snapshot.getString("author_id"),
                channelId = snapshot.getString("channel_id"),
                createdAt = snapshot.getString("created_at"),
                context = snapshot.optJSONArray("context")?.let { array ->
                    (0 until array.length()).map { KingdomJson.parseMessage(array.getJSONObject(it)) }
                } ?: emptyList(),
            ),
            authorHistory = json.optJSONArray("author_history")?.let { array ->
                (0 until array.length()).map {
                    val h = array.getJSONObject(it)
                    ModHistoryRead(h.getString("rung"), h.getString("reason_category"), h.getString("at"))
                }
            } ?: emptyList(),
            appeal = appeal?.let {
                ModAppealRead(it.getString("statement"), it.getString("sanction_by"), it.getString("state"))
            },
        )
    }

    /* ---- REST actions (no payment/signing shapes exist, per CONTRACT.md §8) ---- */

    suspend fun submitReport(submission: ReportSubmission) {
        postJson(
            "/api/reports",
            JSONObject()
                .put("target_kind", submission.targetKind)
                .put("target_id", submission.targetId)
                .put("reason", submission.reason)
                .put("note", submission.note),
        )
    }

    suspend fun submitAppeal(appeal: AppealSubmission) {
        postJson(
            "/api/appeals",
            JSONObject().put("sanction_id", appeal.sanctionId).put("statement", appeal.statement),
        )
    }

    suspend fun modClaim(reportId: String) {
        postJson("/api/mod/claim", JSONObject().put("report_id", reportId))
    }

    suspend fun modAction(request: ModActionRequest) {
        val body = JSONObject()
            .put("report_id", request.reportId)
            .put("action", request.action)
            .put("reason_code", request.reasonCode)
            .put("note", request.note)
            .put("override_claim", request.overrideClaim)
        if (request.durationSec != null) body.put("duration_sec", request.durationSec)
        postJson("/api/mod/action", body)
    }

    suspend fun modDecideAppeal(decision: ModAppealDecision) {
        postJson(
            "/api/mod/appeal",
            JSONObject()
                .put("report_id", decision.reportId)
                .put("outcome", decision.outcome)
                .put("reason_code", decision.reasonCode)
                .put("note", decision.note)
                .put("override_claim", decision.overrideClaim),
        )
    }

    suspend fun nightshiftSuggestion(suggestionId: String, accept: Boolean) {
        postJson(
            "/api/modules/nightshift/suggestions",
            JSONObject().put("suggestion_id", suggestionId).put("accept", accept),
        )
    }

    /* ---- HTTP plumbing (estate pattern: HttpURLConnection + org.json) ---- */

    private fun authHeaders(): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        sessionToken()?.let { headers["Authorization"] = "Bearer $it" }
        walletAddress()?.let { headers["X-Kingdom-Wallet"] = it }
        return headers
    }

    private fun wsUrl(): String {
        val trimmed = baseUrl.trimEnd('/')
        val wsBase = when {
            trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
            trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
            else -> trimmed
        }
        return "$wsBase/ws"
    }

    suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = openConnection(path, "GET")
        try {
            readJson(conn)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun postJson(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val conn = openConnection(path, "POST")
        try {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            readJson(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(path: String, method: String): HttpURLConnection =
        (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            authHeaders().forEach { (name, value) -> setRequestProperty(name, value) }
        }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText() } ?: ""
        if (code !in 200..299) throw KingdomHttpException(code, text)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}

class KingdomHttpException(val code: Int, val body: String) : Exception("kingdom http $code: $body")

/** SharedPreferences-backed [com.raaru.orekingdom.shell.LastVisitedStore] (INTEGRATION_MAP §3.2). */
class PrefsLastVisitedStore(context: android.content.Context) : com.raaru.orekingdom.shell.LastVisitedStore {
    private val prefs = context.getSharedPreferences("kingdom_last_visited", android.content.Context.MODE_PRIVATE)
    override fun get(): String? = prefs.getString("channel_id", null)
    override fun set(channelId: String) {
        prefs.edit().putString("channel_id", channelId).apply()
    }
}
