package com.raaru.orekingdom.states

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.components.StaleBadge
import com.raaru.orekingdom.designsystem.tokens.OreTheme

internal object HomeMissingStatesCopy {
    const val OFFLINE = "You're offline. Showing the last loaded home."
    const val RETRY = "Retry"
}

/**
 * Home audit: skeleton / empty / error already delivered (ServerHomeStates, inline feed and
 * presence states). Missing: the stale-offline notice for a cached home overview.
 */
@Composable
fun HomeOfflineNotice(modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    StateBanner(
        text = HomeMissingStatesCopy.OFFLINE,
        modifier = modifier,
        stale = true,
        actionLabel = if (onRetry != null) HomeMissingStatesCopy.RETRY else null,
        onAction = onRetry,
    )
}

// ---- Shared state primitives for the states package (no F1 state component exists) ----

/** Inline banner on the stale tone; optional stale badge and one optional action. */
@Composable
internal fun StateBanner(
    text: String,
    modifier: Modifier = Modifier,
    stale: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val space = OreTheme.space
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.message.stale.container)
            .padding(horizontal = space.md, vertical = space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.sm),
    ) {
        if (stale) StaleBadge()
        OreText(
            text = text,
            style = OreTheme.type.caption,
            color = OreTheme.color.message.stale.content,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        if (actionLabel != null && onAction != null) OreGhostButton(text = actionLabel, onClick = onAction)
    }
}

/** Centred full-surface message with fixed copy and one optional action. Never a raw error string. */
@Composable
internal fun StateMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    primary: Boolean = false,
) {
    val space = OreTheme.space
    Box(
        modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base).padding(space.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().background(OreTheme.color.surface.raised).padding(space.lg),
            verticalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            OreText(text = title, style = OreTheme.type.titleSmall, color = OreTheme.color.text.primary)
            OreText(text = body, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
            if (actionLabel != null && onAction != null) {
                if (primary) OrePrimaryButton(text = actionLabel, onClick = onAction)
                else OreGhostButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}

@Composable
internal fun StateBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(fraction).height(OreTheme.space.md).background(OreTheme.color.surface.highlight))
}
