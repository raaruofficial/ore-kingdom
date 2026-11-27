package com.raaru.orekingdom.kingdom

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.raaru.orekingdom.moderation.AppealFormPhase
import com.raaru.orekingdom.moderation.ModLogScreen
import com.raaru.orekingdom.moderation.ModQueueScreen
import com.raaru.orekingdom.moderation.SanctionNoticeScreen
import com.raaru.orekingdom.passes.CrownDisplay
import com.raaru.orekingdom.passes.PassScreen
import kotlinx.coroutines.launch

/**
 * Secondary surfaces (INTEGRATION_MAP §5 step 6): passes + Crown, the
 * moderation queue/log, and the caller's own sanction notices. Every
 * action callback performs the host REST call and reloads; purchase /
 * gift callbacks stay inert — CONTRACT.md §8 carries no payment shape,
 * by design.
 */

@Composable
fun PassesRoute(session: KingdomSession, onBack: () -> Unit) {
    val entitlements by session.store.entitlements.collectAsState()
    var load by remember { mutableStateOf<KingdomSession.PassesLoad?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { load = session.loadPasses() }
    KingdomSecondaryScaffold(title = "Passes", onBack = onBack) {
        Column {
            CrownDisplay(
                state = load?.crown ?: com.raaru.orekingdom.passes.CrownDisplayUi(),
                onRetry = { reload++ },
            )
            PassScreen(
                state = load?.passes ?: com.raaru.orekingdom.passes.PassScreenUi(),
                entitlements = entitlements,
                onPurchase = { _, _ -> /* stub (TODO-CONTRACT): no purchase shape exists */ },
                onGift = { _, _, _ -> /* stub (TODO-CONTRACT): no gift shape exists */ },
                onRetry = { reload++ },
            )
        }
    }
}

@Composable
fun ModQueueRoute(session: KingdomSession, onBack: () -> Unit) {
    val entitlements by session.store.entitlements.collectAsState()
    val scope = rememberCoroutineScope()
    var state by remember {
        mutableStateOf(com.raaru.orekingdom.moderation.ModQueueUiState(viewerId = "", modLoft = false))
    }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload, entitlements) { state = session.loadModQueue(entitlements) }
    KingdomSecondaryScaffold(title = "Mod queue", onBack = onBack) {
        ModQueueScreen(
            state = state,
            onClaim = { reportId ->
                scope.launch {
                    runCatching { session.transport.modClaim(reportId) }
                    reload++
                }
            },
            onAction = { request ->
                scope.launch {
                    runCatching { session.transport.modAction(request) }
                    reload++
                }
            },
            onDecideAppeal = { decision ->
                scope.launch {
                    runCatching { session.transport.modDecideAppeal(decision) }
                    reload++
                }
            },
            onRetry = { reload++ },
        )
    }
}

@Composable
fun ModLogRoute(session: KingdomSession, onBack: () -> Unit) {
    val entitlements by session.store.entitlements.collectAsState()
    var state by remember {
        mutableStateOf(com.raaru.orekingdom.moderation.ModLogUiState(modLoft = false))
    }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload, entitlements) { state = session.loadModLog(entitlements) }
    KingdomSecondaryScaffold(title = "Mod log", onBack = onBack) {
        ModLogScreen(state = state, onRetry = { reload++ })
    }
}

@Composable
fun SanctionsRoute(session: KingdomSession, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember {
        mutableStateOf(com.raaru.orekingdom.moderation.SanctionNoticesUiState())
    }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { state = session.loadSanctions() }
    KingdomSecondaryScaffold(title = "Moderation notices", onBack = onBack) {
        SanctionNoticeScreen(
            state = state,
            onSubmitAppeal = { appeal ->
                // Mark the form as submitting, then reload from the read.
                state = state.copy(
                    notices = state.notices.map {
                        if (it.sanctionId == appeal.sanctionId) {
                            it.copy(formPhase = AppealFormPhase.Submitting)
                        } else it
                    },
                )
                scope.launch {
                    try {
                        session.transport.submitAppeal(appeal)
                    } catch (_: Exception) {
                        state = state.copy(
                            notices = state.notices.map {
                                if (it.sanctionId == appeal.sanctionId) {
                                    it.copy(formPhase = AppealFormPhase.Failed)
                                } else it
                            },
                        )
                        return@launch
                    }
                    reload++
                }
            },
            onRetry = { reload++ },
        )
    }
}
