package com.raaru.orekingdom.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme

internal object ServerHomeStatesCopy {
    const val EMPTY_TITLE = "Nothing here yet"
    const val EMPTY_BODY = "The public home is empty for this reader. Channels appear here as soon as they are available."
    const val EMPTY_ACTION = "Refresh"
    const val ERROR_TITLE = "Home could not be loaded"
    const val ERROR_BODY = "Check your connection and try again."
    const val ERROR_ACTION = "Retry"
    const val SKELETON_CATEGORIES = 3
    const val SKELETON_CHANNELS_PER_CATEGORY = 3
    const val SKELETON_SPOTLIGHT_ROWS = 2
}

/**
 * Loading shapes matching ServerHomeScreen's layout: presence header, spotlight slot,
 * then category headers with channel rows.
 */
@Composable
fun ServerHomeSkeleton(modifier: Modifier = Modifier) {
    val space = OreTheme.space
    val size = OreTheme.size
    Column(modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(OreTheme.color.surface.raised)
                .padding(horizontal = space.md, vertical = space.sm),
            verticalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SkeletonBar(fraction = 0.4f, tall = true)
                Box(modifier = Modifier.weight(1f))
                SkeletonBlock(modifier = Modifier.width(size.touchTarget).height(space.md))
            }
            SkeletonBar(fraction = 0.25f)
            SkeletonBar(fraction = 0.6f)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = space.md, vertical = space.sm)
                .background(OreTheme.color.surface.raised)
                .padding(space.md),
            verticalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            SkeletonBar(fraction = 0.3f)
            repeat(ServerHomeStatesCopy.SKELETON_SPOTLIGHT_ROWS) { SkeletonBar(fraction = if (it == 0) 0.9f else 0.7f) }
        }
        repeat(ServerHomeStatesCopy.SKELETON_CATEGORIES) { category ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(size.categoryHeaderHeight)
                    .padding(horizontal = space.md),
                contentAlignment = Alignment.CenterStart,
            ) { SkeletonBar(fraction = 0.3f) }
            repeat(ServerHomeStatesCopy.SKELETON_CHANNELS_PER_CATEGORY) { channel ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(size.channelRowHeight)
                        .padding(horizontal = space.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(space.sm),
                ) {
                    SkeletonBlock(modifier = Modifier.width(size.iconSmall).height(size.iconSmall))
                    SkeletonBar(fraction = if ((category + channel) % 2 == 0) 0.55f else 0.4f)
                }
            }
        }
    }
}

/** No visible categories for this reader. Refresh renders only when a callback is supplied. */
@Composable
fun ServerHomeEmpty(
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
) {
    ServerHomeStateMessage(
        title = ServerHomeStatesCopy.EMPTY_TITLE,
        body = ServerHomeStatesCopy.EMPTY_BODY,
        modifier = modifier,
    ) {
        if (onRefresh != null) OreGhostButton(text = ServerHomeStatesCopy.EMPTY_ACTION, onClick = onRefresh)
    }
}

/** Error with retry. Fixed copy only — no raw error string is ever passed in or rendered. */
@Composable
fun ServerHomeError(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ServerHomeStateMessage(
        title = ServerHomeStatesCopy.ERROR_TITLE,
        body = ServerHomeStatesCopy.ERROR_BODY,
        modifier = modifier,
    ) {
        OrePrimaryButton(text = ServerHomeStatesCopy.ERROR_ACTION, onClick = onRetry)
    }
}

@Composable
private fun ServerHomeStateMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit,
) {
    val space = OreTheme.space
    Box(
        modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base).padding(space.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(OreTheme.color.surface.raised)
                .padding(space.lg),
            verticalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            OreText(text = title, style = OreTheme.type.titleSmall, color = OreTheme.color.text.primary)
            OreText(text = body, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
            action()
        }
    }
}

@Composable
private fun SkeletonBar(fraction: Float, tall: Boolean = false) {
    SkeletonBlock(
        modifier = Modifier
            .fillMaxWidth(fraction)
            .height(if (tall) OreTheme.space.lg else OreTheme.space.md),
    )
}

@Composable
private fun SkeletonBlock(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(OreTheme.color.surface.highlight))
}
