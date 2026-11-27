package com.raaru.orekingdom.states

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal object PumpMissingStatesCopy {
    const val OFFLINE = "You're offline. Fleet, day bank and ATM status may be out of date."
    const val RETRY = "Retry"
}

/**
 * PUMP audit: console skeleton / error / preview note, the no-wallets empty state and per-panel
 * loading / error are already delivered (PumpStates + inline). Missing: the stale-offline notice.
 */
@Composable
fun PumpOfflineNotice(modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    StateBanner(
        text = PumpMissingStatesCopy.OFFLINE,
        modifier = modifier,
        stale = true,
        actionLabel = if (onRetry != null) PumpMissingStatesCopy.RETRY else null,
        onAction = onRetry,
    )
}
