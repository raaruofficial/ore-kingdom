package com.raaru.orekingdom.states

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme

internal object PassesMissingStatesCopy {
    const val OFFLINE = "You're offline. Your pass status may be out of date."
    const val RETRY = "Retry"
    const val CROWN_HEADER = "THE CROWN"
    const val CROWN_EMPTY = "No Crown holder to show."
    const val CROWN_ERROR = "The Crown could not be loaded."
}

/**
 * Passes audit: the pass screen (loading / error; products are fixed, so no empty state), the
 * spotlight info surface (loading / error / vacant) and the market compose entry (submitting /
 * failed / submitted / not-entitled) are complete. Missing: the pass offline notice and the Crown
 * display's loading / empty / error. Display only: no price, no action.
 */
@Composable
fun PassOfflineNotice(modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    StateBanner(
        text = PassesMissingStatesCopy.OFFLINE,
        modifier = modifier,
        stale = true,
        actionLabel = if (onRetry != null) PassesMissingStatesCopy.RETRY else null,
        onAction = onRetry,
    )
}

@Composable
fun CrownDisplaySkeleton(modifier: Modifier = Modifier) {
    CrownStateCard(modifier) {
        StateBar(0.5f)
        StateBar(0.3f)
    }
}

@Composable
fun CrownDisplayEmpty(modifier: Modifier = Modifier) {
    CrownStateCard(modifier) {
        OreText(text = PassesMissingStatesCopy.CROWN_EMPTY, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
    }
}

@Composable
fun CrownDisplayError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    CrownStateCard(modifier) {
        OreText(text = PassesMissingStatesCopy.CROWN_ERROR, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
        OreGhostButton(text = PassesMissingStatesCopy.RETRY, onClick = onRetry)
    }
}

@Composable
private fun CrownStateCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth().background(OreTheme.color.surface.raised).padding(OreTheme.space.md),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
    ) {
        OreText(
            text = PassesMissingStatesCopy.CROWN_HEADER,
            style = OreTheme.type.overline,
            color = OreTheme.color.label.crown.content,
            maxLines = 1,
        )
        content()
    }
}
