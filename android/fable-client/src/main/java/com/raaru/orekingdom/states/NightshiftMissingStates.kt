package com.raaru.orekingdom.states

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal object NightshiftMissingStatesCopy {
    const val OFFLINE = "You're offline. Fleet status, assisted placement suggestions and intel may be out of date."
    const val INTEL_OFFLINE = "You're offline. Showing saved intel."
    const val RETRY = "Retry"
}

/**
 * NIGHTSHIFT audit: console skeleton / error / preview note, suggestion and fleet empty states and
 * intel loading / empty / error are already delivered (NightshiftStates + inline).
 * Missing: the stale-offline notices for the console and the #intel-feed surface.
 */
@Composable
fun NightshiftOfflineNotice(modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    StateBanner(
        text = NightshiftMissingStatesCopy.OFFLINE,
        modifier = modifier,
        stale = true,
        actionLabel = if (onRetry != null) NightshiftMissingStatesCopy.RETRY else null,
        onAction = onRetry,
    )
}

@Composable
fun IntelFeedOfflineNotice(modifier: Modifier = Modifier) {
    StateBanner(text = NightshiftMissingStatesCopy.INTEL_OFFLINE, modifier = modifier, stale = true)
}
