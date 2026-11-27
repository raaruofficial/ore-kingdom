package com.raaru.orekingdom.states

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.tokens.OreTheme

internal object ModerationMissingStatesCopy {
    const val QUEUE_EMPTY_TITLE = "The queue is clear"
    const val QUEUE_EMPTY_BODY = "There are no open reports or appeals."
    const val QUEUE_ERROR_TITLE = "The mod queue could not be loaded"
    const val LOG_EMPTY_TITLE = "No log entries yet"
    const val LOG_EMPTY_BODY = "Moderation actions appear here as they happen."
    const val LOG_ERROR_TITLE = "The mod log could not be loaded"
    const val ERROR_BODY = "Check your connection and try again."
    const val RETRY = "Retry"
    const val OFFLINE = "You're offline. Moderation actions are unavailable until you reconnect."
    const val SKELETON_CARDS = 3
    const val SKELETON_LOG_ROWS = 8
}

/**
 * Moderation audit: the report sheet (submitting / failed / acknowledged) and sanction notices
 * (loading / empty / error) are complete. The mod queue and mod log state surfaces are supplied
 * here as standalone surfaces. Compose only for callers whose entitlement read carries `mod_loft`.
 */
@Composable
fun ModQueueSkeleton(modifier: Modifier = Modifier) {
    val space = OreTheme.space
    Column(
        modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base).padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.md),
    ) {
        repeat(ModerationMissingStatesCopy.SKELETON_CARDS) {
            Column(
                modifier = Modifier.fillMaxWidth().background(OreTheme.color.surface.raised).padding(space.md),
                verticalArrangement = Arrangement.spacedBy(space.sm),
            ) {
                StateBar(0.35f)
                StateBar(0.9f)
                StateBar(0.7f)
                StateBar(0.5f)
            }
        }
    }
}

@Composable
fun ModQueueEmpty(modifier: Modifier = Modifier, onRefresh: (() -> Unit)? = null) {
    StateMessage(
        title = ModerationMissingStatesCopy.QUEUE_EMPTY_TITLE,
        body = ModerationMissingStatesCopy.QUEUE_EMPTY_BODY,
        modifier = modifier,
        actionLabel = if (onRefresh != null) ModerationMissingStatesCopy.RETRY else null,
        onAction = onRefresh,
    )
}

@Composable
fun ModQueueError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    StateMessage(
        title = ModerationMissingStatesCopy.QUEUE_ERROR_TITLE,
        body = ModerationMissingStatesCopy.ERROR_BODY,
        modifier = modifier,
        actionLabel = ModerationMissingStatesCopy.RETRY,
        onAction = onRetry,
        primary = true,
    )
}

@Composable
fun ModLogSkeleton(modifier: Modifier = Modifier) {
    val space = OreTheme.space
    Column(
        modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base).padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.md),
    ) {
        repeat(ModerationMissingStatesCopy.SKELETON_LOG_ROWS) { index ->
            Column(verticalArrangement = Arrangement.spacedBy(space.xs)) {
                StateBar(if (index % 2 == 0) 0.8f else 0.6f)
                StateBar(0.3f)
            }
        }
    }
}

/** Mod log states carry no edit or delete affordance: the log is read-only for every role. */
@Composable
fun ModLogEmpty(modifier: Modifier = Modifier) {
    StateMessage(
        title = ModerationMissingStatesCopy.LOG_EMPTY_TITLE,
        body = ModerationMissingStatesCopy.LOG_EMPTY_BODY,
        modifier = modifier,
    )
}

@Composable
fun ModLogError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    StateMessage(
        title = ModerationMissingStatesCopy.LOG_ERROR_TITLE,
        body = ModerationMissingStatesCopy.ERROR_BODY,
        modifier = modifier,
        actionLabel = ModerationMissingStatesCopy.RETRY,
        onAction = onRetry,
        primary = true,
    )
}

@Composable
fun ModerationOfflineNotice(modifier: Modifier = Modifier) {
    StateBanner(text = ModerationMissingStatesCopy.OFFLINE, modifier = modifier, stale = true)
}
