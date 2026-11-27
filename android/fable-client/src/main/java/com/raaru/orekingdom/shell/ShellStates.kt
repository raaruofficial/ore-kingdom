package com.raaru.orekingdom.shell

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.raaru.orekingdom.designsystem.components.InfoCard
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.components.StaleBadge
import com.raaru.orekingdom.designsystem.tokens.OreTheme

private const val RailLoadingDescription = "Loading channels"
private const val RailEmptyTitle = "No channels to show"
private const val RailEmptyBody = "Reading is free forever." // standing copy, BRIEF §8
private const val RailErrorTitle = "Couldn't load the Kingdom"
private const val RailErrorBody = "Check your connection and try again."
private const val RailStaleNote = "Offline — showing saved channels"
private const val Retry = "Retry"
private const val Refresh = "Refresh"

private const val SkeletonCategories = 3
private const val SkeletonRowsPerCategory = 4
private const val SkeletonHeaderFraction = 0.35f
private val SkeletonRowFractions = listOf(0.7f, 0.5f, 0.6f, 0.45f)

/** Rail skeleton: category-header and channel-row shapes at their token heights. */
@Composable
fun ShellRailSkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shellRailSkeleton")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = OreTheme.alpha.sending,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = OreTheme.motion.duration.pulse,
                easing = OreTheme.motion.easing.standard,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shellRailSkeletonAlpha",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .alpha(pulse)
            .semantics { contentDescription = RailLoadingDescription },
    ) {
        repeat(SkeletonCategories) {
            SkeletonLine(
                rowHeight = OreTheme.size.categoryHeaderHeight,
                fraction = SkeletonHeaderFraction,
            )
            repeat(SkeletonRowsPerCategory) { row ->
                SkeletonLine(
                    rowHeight = OreTheme.size.channelRowHeight,
                    fraction = SkeletonRowFractions[row % SkeletonRowFractions.size],
                )
            }
        }
    }
}

@Composable
private fun SkeletonLine(rowHeight: androidx.compose.ui.unit.Dp, fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .padding(horizontal = OreTheme.space.md),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(OreTheme.space.md)
                .clip(OreTheme.shape.xs)
                .background(OreTheme.color.surface.highlight),
        )
    }
}

/** Rail loaded with no entitled channels. */
@Composable
fun ShellRailEmpty(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InfoCard(
        title = RailEmptyTitle,
        body = RailEmptyBody,
        modifier = modifier.padding(OreTheme.space.md),
        action = { OreGhostButton(text = Refresh, onClick = onRefresh) },
    )
}

/** Rail failed to load. Fixed copy only; no server or exception text is ever rendered. */
@Composable
fun ShellRailError(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(OreTheme.space.md)
            .clip(OreTheme.shape.lg)
            .background(OreTheme.color.status.error.container)
            .padding(OreTheme.space.lg),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
    ) {
        OreText(
            text = RailErrorTitle,
            style = OreTheme.type.titleSmall,
            color = OreTheme.color.status.error.content,
            maxLines = 2,
        )
        OreText(
            text = RailErrorBody,
            style = OreTheme.type.body,
            color = OreTheme.color.text.secondary,
        )
        OrePrimaryButton(text = Retry, onClick = onRetry)
    }
}

/** Shown above cached rail data while offline / awaiting a fresh read. */
@Composable
fun ShellRailStaleNotice(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = OreTheme.space.md, vertical = OreTheme.space.xs),
        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StaleBadge()
        OreText(
            text = RailStaleNote,
            style = OreTheme.type.caption,
            color = OreTheme.color.text.tertiary,
            maxLines = 1,
        )
    }
}
