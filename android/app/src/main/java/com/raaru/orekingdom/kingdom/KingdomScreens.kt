package com.raaru.orekingdom.kingdom

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.channel.ChannelComposer
import com.raaru.orekingdom.channel.ChannelScreen
import com.raaru.orekingdom.channel.SearchStatus
import com.raaru.orekingdom.channel.SearchSurface
import com.raaru.orekingdom.channel.SearchUiState
import com.raaru.orekingdom.channel.ThreadList
import com.raaru.orekingdom.channel.ThreadListUiState
import com.raaru.orekingdom.channel.ThreadUiState
import com.raaru.orekingdom.channel.ThreadView
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreDarkColors
import com.raaru.orekingdom.designsystem.tokens.OreTheme
import com.raaru.orekingdom.modules.nightshift.IntelConsoleSection
import com.raaru.orekingdom.modules.nightshift.IntelFeedChannelSurface
import com.raaru.orekingdom.modules.nightshift.NightshiftAddOnSurface
import com.raaru.orekingdom.modules.nightshift.NightshiftConsoleScreen
import com.raaru.orekingdom.modules.pump.PumpAtmPanel
import com.raaru.orekingdom.modules.pump.PumpConsoleScreen
import com.raaru.orekingdom.modules.pump.PumpDayBankPanel
import com.raaru.orekingdom.moderation.ReportPhase
import com.raaru.orekingdom.moderation.ReportSheet
import com.raaru.orekingdom.moderation.ReportSheetUiState
import com.raaru.orekingdom.moderation.ReportSubmission
import com.raaru.orekingdom.moderation.ReportTargetKind
import com.raaru.orekingdom.shell.AppShell
import com.raaru.orekingdom.shell.ModuleEntry
import com.raaru.orekingdom.shell.RailChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Host routes outside the shell (INTEGRATION_MAP §5 step 5/6). */
object KingdomRoutes {
    const val SHELL = "shell"
    const val PROFILE = "profile"
    const val MEMBERS = "members"
    const val PASSES = "passes"
    const val MOD_QUEUE = "modqueue"
    const val MOD_LOG = "modlog"
    const val SANCTIONS = "sanctions"
    const val THREADS = "threads"
    const val THREAD = "thread"
    const val SEARCH = "search"
}

private data class ReportUi(
    val kind: ReportTargetKind,
    val targetId: String,
    val phase: ReportPhase = ReportPhase.Editing,
)

/**
 * Host root: [AppShell] plus the secondary surfaces the shell hands off
 * to (profile, members, passes, moderation), per INTEGRATION_MAP §2.2
 * and §5. The shell stays the navigation root; secondary routes are
 * host chrome around the client's own screens.
 */
@Composable
fun KingdomRoot(session: KingdomSession) {
    val rail by session.store.rail.collectAsState()
    val spotlight by session.store.spotlight.collectAsState()
    val entitlements by session.store.entitlements.collectAsState()
    val scope = rememberCoroutineScope()

    var route by rememberSaveable { mutableStateOf(KingdomRoutes.SHELL) }
    var profileUserId by rememberSaveable { mutableStateOf<String?>(null) }
    var threadsChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var openThreadId by rememberSaveable { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<ReportUi?>(null) }

    fun openReport(kind: ReportTargetKind, targetId: String) {
        report = ReportUi(kind, targetId)
    }

    fun submitReport(submission: ReportSubmission) {
        val current = report ?: return
        report = current.copy(phase = ReportPhase.Submitting)
        scope.launch {
            try {
                session.transport.submitReport(submission)
                report = report?.copy(phase = ReportPhase.Acknowledged)
            } catch (_: Exception) {
                report = report?.copy(phase = ReportPhase.Failed)
            }
        }
    }

    BackHandler(enabled = route != KingdomRoutes.SHELL) {
        route = when (route) {
            KingdomRoutes.THREAD -> KingdomRoutes.THREADS
            KingdomRoutes.THREADS, KingdomRoutes.SEARCH, KingdomRoutes.PROFILE -> KingdomRoutes.SHELL
            else -> KingdomRoutes.PROFILE
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (route) {
            KingdomRoutes.SHELL -> AppShell(
                rail = rail,
                spotlight = spotlight,
                modules = com.raaru.orekingdom.shell.ModuleRegistry.entries(entitlements),
                onChannelSelected = { session.openChannel(it) },
                onModuleSelected = { },
                onProfileSelected = {
                    profileUserId = session.selfId
                    session.refreshDirectory()
                    route = KingdomRoutes.PROFILE
                },
                onRetry = { session.store.refresh() },
                channelContent = { channel ->
                    ChannelHost(
                        session = session,
                        channel = channel,
                        onReportMessage = { messageId -> openReport(ReportTargetKind.Message, messageId) },
                        onOpenThreads = { channelId ->
                            threadsChannelId = channelId
                            route = KingdomRoutes.THREADS
                        },
                        onOpenSearch = { route = KingdomRoutes.SEARCH },
                    )
                },
                moduleContent = { entry ->
                    ModuleHost(session = session, entry = entry)
                },
            )
            KingdomRoutes.PROFILE -> KingdomSecondaryScaffold(
                title = "Profile",
                onBack = { route = KingdomRoutes.SHELL },
                nav = secondaryNav(entitlements?.capabilities?.modLoft == true) { route = it },
            ) {
                val uid = profileUserId
                if (uid == null) {
                    ProfileLoadingNote(onRetry = { session.refreshDirectory() })
                } else {
                    com.raaru.orekingdom.identity.ProfileScreen(
                        state = session.profileState(uid),
                        entitlements = entitlements,
                        onReport = { openReport(ReportTargetKind.Profile, it) },
                        onPassSelected = { route = KingdomRoutes.PASSES },
                        onRetry = { session.refreshDirectory() },
                    )
                }
            }
            KingdomRoutes.MEMBERS -> KingdomSecondaryScaffold(
                title = "Members",
                onBack = { route = KingdomRoutes.PROFILE },
            ) {
                LaunchedEffect(Unit) { session.refreshDirectory() }
                val version by session.version.collectAsState()
                val realtimeState by session.realtime.state.collectAsState()
                // Snapshots below read live session state; the collected
                // flows exist to recompose this route when either moves.
                @Suppress("UNUSED_EXPRESSION")
                (version to realtimeState)
                com.raaru.orekingdom.identity.MemberListScreen(
                    state = session.membersState(),
                    onMemberSelected = { userId ->
                        profileUserId = userId
                        route = KingdomRoutes.PROFILE
                    },
                    onRetry = { session.refreshDirectory() },
                )
            }
            KingdomRoutes.PASSES -> PassesRoute(session = session, onBack = { route = KingdomRoutes.PROFILE })
            KingdomRoutes.MOD_QUEUE -> ModQueueRoute(session = session, onBack = { route = KingdomRoutes.PROFILE })
            KingdomRoutes.MOD_LOG -> ModLogRoute(session = session, onBack = { route = KingdomRoutes.PROFILE })
            KingdomRoutes.SANCTIONS -> SanctionsRoute(session = session, onBack = { route = KingdomRoutes.PROFILE })
            KingdomRoutes.THREADS -> {
                val channelId = threadsChannelId
                if (channelId == null) {
                    KingdomSecondaryScaffold(title = "Threads", onBack = { route = KingdomRoutes.SHELL }) {
                        ProfileLoadingNote(onRetry = { route = KingdomRoutes.SHELL })
                    }
                } else {
                    ThreadsRoute(
                        session = session,
                        channelId = channelId,
                        onBack = { route = KingdomRoutes.SHELL },
                        onThreadSelected = { threadId ->
                            openThreadId = threadId
                            route = KingdomRoutes.THREAD
                        },
                    )
                }
            }
            KingdomRoutes.THREAD -> {
                val channelId = threadsChannelId
                val threadId = openThreadId
                if (channelId == null || threadId == null) {
                    KingdomSecondaryScaffold(title = "Thread", onBack = { route = KingdomRoutes.SHELL }) {
                        ProfileLoadingNote(onRetry = { route = KingdomRoutes.SHELL })
                    }
                } else {
                    ThreadRoute(
                        session = session,
                        channelId = channelId,
                        threadId = threadId,
                        onBack = { route = KingdomRoutes.THREADS },
                    )
                }
            }
            KingdomRoutes.SEARCH -> SearchRoute(
                session = session,
                onBack = { route = KingdomRoutes.SHELL },
                onMessageOpen = { channelId, _ ->
                    session.openChannel(channelId)
                    route = KingdomRoutes.SHELL
                },
                onThreadOpen = { channelId, threadId ->
                    threadsChannelId = channelId
                    openThreadId = threadId
                    route = KingdomRoutes.THREAD
                },
            )
        }

        // Report sheet overlay (host container for the client's sheet content).
        report?.let { current ->
            OreTheme(colors = OreDarkColors) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(OreTheme.color.surface.scrim),
                    contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
                ) {
                    ReportSheet(
                        state = ReportSheetUiState(current.kind, current.targetId, current.phase),
                        onSubmit = { submitReport(it) },
                        onDismiss = { report = null },
                        modifier = Modifier.padding(OreTheme.space.md),
                    )
                }
            }
        }
    }
}

private fun secondaryNav(modLoft: Boolean, navigate: (String) -> Unit): List<Pair<String, () -> Unit>> {
    val entries = mutableListOf(
        "Members" to { navigate(KingdomRoutes.MEMBERS) },
        "Passes" to { navigate(KingdomRoutes.PASSES) },
        "Notices" to { navigate(KingdomRoutes.SANCTIONS) },
    )
    if (modLoft) {
        entries += "Mod queue" to { navigate(KingdomRoutes.MOD_QUEUE) }
        entries += "Mod log" to { navigate(KingdomRoutes.MOD_LOG) }
    }
    return entries
}

@Composable
private fun ProfileLoadingNote(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(OreTheme.space.lg),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
    ) {
        OreText(
            text = "Your profile is still loading.",
            style = OreTheme.type.body,
            color = OreTheme.color.text.secondary,
        )
        OreGhostButton(text = "Retry", onClick = onRetry)
    }
}

/** Secondary-surface chrome: dark theme wrap (INTEGRATION_MAP §5 step 2), back row, optional nav row. */
@Composable
fun KingdomSecondaryScaffold(
    title: String,
    onBack: () -> Unit,
    nav: List<Pair<String, () -> Unit>> = emptyList(),
    content: @Composable () -> Unit,
) {
    OreTheme(colors = OreDarkColors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(OreTheme.color.surface.base),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OreTheme.color.surface.raised)
                    .padding(horizontal = OreTheme.space.md, vertical = OreTheme.space.xs),
                horizontalArrangement = Arrangement.spacedBy(OreTheme.space.md),
            ) {
                OreGhostButton(text = "Back", onClick = onBack)
                OreText(
                    text = title,
                    style = OreTheme.type.titleSmall,
                    color = OreTheme.color.text.primary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
            if (nav.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = OreTheme.space.md, vertical = OreTheme.space.xs),
                    horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
                ) {
                    nav.forEach { (label, action) -> OreGhostButton(text = label, onClick = action) }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
        }
    }
}

/**
 * One channel's host: realtime state machine + outbox + composer for a
 * single rail channel. The `#intel-feed` channel gets its special
 * surface (INTEGRATION_MAP §5 step 5); every other channel gets the
 * standard channel screen.
 */
@Composable
private fun ChannelHost(
    session: KingdomSession,
    channel: RailChannel,
    onReportMessage: (messageId: String) -> Unit,
    onOpenThreads: (channelId: String) -> Unit,
    onOpenSearch: () -> Unit,
) {
    val entitlements by session.store.entitlements.collectAsState()
    // Collected so the snapshots below recompose whenever the session,
    // the realtime machine, the outbox, or the send pipeline moves.
    val version by session.version.collectAsState()
    val realtimeState by session.realtime.state.collectAsState()
    val outboxEntries by session.outbox.entries.collectAsState()
    val inFlight by session.sendController.inFlight.collectAsState()
    @Suppress("UNUSED_EXPRESSION")
    (version to realtimeState to outboxEntries to inFlight)
    val connected by session.transport.connected.collectAsState()

    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(channel.id) {
        session.openChannel(channel.id)
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val nowMs = remember(tick) { System.currentTimeMillis() }
    val composerState = session.effectiveComposer(channel, nowMs)

    if (channel.name == IntelFeedTitle) {
        var intel by remember(channel.id) {
            mutableStateOf(com.raaru.orekingdom.modules.nightshift.IntelFeedUi())
        }
        LaunchedEffect(channel.id) {
            intel = session.loadNightshift().intel
        }
        IntelFeedChannelSurface(
            state = intel,
            onRetry = { },
            composer = { ChannelComposerBlock(session, channel, composerState, connected) },
        )
        return
    }

    var selectedMessageId by remember(channel.id) { mutableStateOf<String?>(null) }
    val state = session.channelState(channel, entitlements)
    ChannelScreen(
        state = state,
        onReactionToggle = { messageId, emoji -> session.toggleReaction(channel.id, messageId, emoji) },
        onMediaOpen = { _, _ -> /* no media viewer shape in the contract; thumbnails only (INTEGRATION_MAP §3.5) */ },
        onMessageSelected = { selectedMessageId = it },
        onLoadOlder = { session.loadOlder(channel.id) },
        onRetry = { session.retryChannel(channel.id) },
        composer = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OreTheme.color.surface.raised)
                        .padding(horizontal = OreTheme.space.md, vertical = OreTheme.space.xs),
                    horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
                ) {
                    OreGhostButton(text = "Threads", onClick = { onOpenThreads(channel.id) })
                    OreGhostButton(text = "Search", onClick = onOpenSearch)
                }
                selectedMessageId?.let { messageId ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(OreTheme.color.surface.raised)
                            .padding(horizontal = OreTheme.space.md, vertical = OreTheme.space.xs),
                        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
                    ) {
                        OreGhostButton(
                            text = "Report message",
                            onClick = {
                                onReportMessage(messageId)
                                selectedMessageId = null
                            },
                        )
                        OreGhostButton(text = "Done", onClick = { selectedMessageId = null })
                    }
                }
                ChannelComposerBlock(session, channel, composerState, connected)
            }
        },
    )
}

private const val IntelFeedTitle = "intel-feed"

@Composable
private fun ChannelComposerBlock(
    session: KingdomSession,
    channel: RailChannel,
    composerState: com.raaru.orekingdom.designsystem.components.ComposerState,
    connected: Boolean,
    threadId: String? = null,
) {
    var slowmodeRefresh by remember(channel.id) { mutableIntStateOf(0) }
    // Reading slowmodeRefresh keeps the block recomposing when a countdown elapses.
    @Suppress("UNUSED_EXPRESSION")
    slowmodeRefresh
    ChannelComposer(
        channelId = channel.id,
        composerState = composerState,
        outbox = session.outbox,
        onSendAck = { },
        online = connected,
        threadId = threadId,
        onTyping = { typing -> session.setTyping(channel.id, typing) },
        onSlowmodeElapsed = { slowmodeRefresh++ },
        controller = session.sendController,
    )
}

/** Module consoles by route (INTEGRATION_MAP §5 step 5), fed by the host read models. */
@Composable
private fun ModuleHost(session: KingdomSession, entry: ModuleEntry) {
    val entitlements by session.store.entitlements.collectAsState()
    val scope = rememberCoroutineScope()
    when (entry.route) {
        "module/pump" -> {
            var load by remember(entry.route) { mutableStateOf<KingdomSession.PumpLoad?>(null) }
            var reload by remember(entry.route) { mutableIntStateOf(0) }
            LaunchedEffect(entry.route, reload, entitlements) {
                load = session.loadPump(entitlements)
            }
            PumpConsoleScreen(
                state = load?.console ?: com.raaru.orekingdom.modules.pump.PumpConsoleUi(),
                flag = entry.state,
                onRetry = { reload++ },
                panels = { actionsEnabled ->
                    Column(verticalArrangement = Arrangement.spacedBy(OreTheme.space.md)) {
                        PumpDayBankPanel(
                            state = load?.dayBank ?: com.raaru.orekingdom.modules.pump.PumpDayBankUi(),
                            actionsEnabled = actionsEnabled,
                            onRetry = { reload++ },
                        )
                        PumpAtmPanel(
                            state = load?.atm ?: com.raaru.orekingdom.modules.pump.PumpAtmUi(),
                            actionsEnabled = actionsEnabled,
                            onRetry = { reload++ },
                        )
                    }
                },
            )
        }
        "module/nightshift" -> {
            var load by remember(entry.route) { mutableStateOf<KingdomSession.NightshiftLoad?>(null) }
            var reload by remember(entry.route) { mutableIntStateOf(0) }
            LaunchedEffect(entry.route, reload) {
                load = session.loadNightshift()
            }
            NightshiftConsoleScreen(
                state = load?.console ?: com.raaru.orekingdom.modules.nightshift.NightshiftConsoleUi(),
                flag = entry.state,
                consoleEntitled = entitlements?.capabilities?.nightshiftConsole == true,
                onAccept = { id ->
                    scope.launch {
                        runCatching { session.transport.nightshiftSuggestion(id, accept = true) }
                        reload++
                    }
                },
                onDismiss = { id ->
                    scope.launch {
                        runCatching { session.transport.nightshiftSuggestion(id, accept = false) }
                        reload++
                    }
                },
                onRetry = { reload++ },
                intel = {
                    IntelConsoleSection(
                        state = load?.intel ?: com.raaru.orekingdom.modules.nightshift.IntelFeedUi(),
                        onRetry = { reload++ },
                    )
                },
                addOn = {
                    NightshiftAddOnSurface(autopilotSurface = entitlements?.capabilities?.autopilotSurface == true)
                },
            )
        }
        else -> com.raaru.orekingdom.shell.ModulePlaceholder(entry = entry)
    }
}

/* ---- thread + search routes (stage-1 remainder, mounted 2026-11-27) ---- */

@Composable
private fun ThreadsRoute(
    session: KingdomSession,
    channelId: String,
    onBack: () -> Unit,
    onThreadSelected: (String) -> Unit,
) {
    var state by remember(channelId) { mutableStateOf(ThreadListUiState()) }
    var reload by remember(channelId) { mutableIntStateOf(0) }
    LaunchedEffect(channelId, reload) { state = session.loadThreadList(channelId) }
    KingdomSecondaryScaffold(title = "Threads", onBack = onBack) {
        ThreadList(state = state, onThreadSelected = onThreadSelected, onRetry = { reload++ })
    }
}

@Composable
private fun ThreadRoute(
    session: KingdomSession,
    channelId: String,
    threadId: String,
    onBack: () -> Unit,
) {
    val rail by session.store.rail.collectAsState()
    val connected by session.transport.connected.collectAsState()
    val version by session.version.collectAsState()
    var loaded by remember(channelId, threadId) { mutableStateOf<ThreadUiState?>(null) }
    var reload by remember(channelId, threadId) { mutableIntStateOf(0) }
    LaunchedEffect(channelId, threadId, reload) { loaded = session.loadThread(channelId, threadId) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(channelId) {
        while (true) {
            delay(1_000)
            tick++
        }
    }
    val nowMs = remember(tick) { System.currentTimeMillis() }
    // Collected so the snapshot below recomputes whenever the session cache moves.
    @Suppress("UNUSED_EXPRESSION")
    version
    val base = loaded ?: ThreadUiState(threadId = threadId, title = "")
    val state = session.threadSnapshot(channelId, base)
    val channel = rail.categories.flatMap { it.channels }.firstOrNull { it.id == channelId }
    KingdomSecondaryScaffold(title = state.title.ifEmpty { "Thread" }, onBack = onBack) {
        ThreadView(
            state = state,
            composerState = channel?.let { session.effectiveComposer(it, nowMs) }
                ?: com.raaru.orekingdom.designsystem.components.ComposerState.Disabled(),
            onReactionToggle = { messageId, emoji -> session.toggleReaction(channelId, messageId, emoji) },
            onMediaOpen = { _, _ -> /* no media viewer shape in the contract; thumbnails only */ },
            activeComposer = { cState ->
                if (channel != null) {
                    ChannelComposerBlock(session, channel, cState, connected, threadId)
                }
            },
            onRetry = { reload++ },
        )
    }
}

@Composable
private fun SearchRoute(
    session: KingdomSession,
    onBack: () -> Unit,
    onMessageOpen: (channelId: String, messageId: String) -> Unit,
    onThreadOpen: (channelId: String, threadId: String) -> Unit,
) {
    var state by remember { mutableStateOf(SearchUiState()) }
    var submitted by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(submitted) {
        val q = submitted ?: return@LaunchedEffect
        state = session.loadSearch(q)
    }
    KingdomSecondaryScaffold(title = "Search", onBack = onBack) {
        SearchSurface(
            state = state,
            onQueryChange = { state = state.copy(query = it) },
            onSubmit = {
                state = state.copy(status = SearchStatus.Loading)
                submitted = state.query
            },
            onMessageOpen = onMessageOpen,
            onThreadOpen = onThreadOpen,
            onRetry = { submitted = state.query },
        )
    }
}
