package com.raaru.orekingdom.modules.nightshift

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
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme

internal object NightshiftStatesCopy {
    const val ERROR_TITLE = "NIGHTSHIFT could not be loaded"
    const val ERROR_BODY = "Check your connection and try again."
    const val RETRY = "Retry"
    const val PREVIEW_TITLE = "NIGHTSHIFT is in preview"
    const val PREVIEW_BODY = "You can look around: fleet status, assisted placement suggestions and intel are shown for information."
    const val SKELETON_FLEET_ROWS = 3
    const val SKELETON_SUGGESTIONS = 2
    const val SKELETON_INTEL = 2
}

/** Loading shapes for the console: title bar, fleet summary, assisted suggestion cards, intel cards. */
@Composable
fun NightshiftSkeleton(modifier: Modifier = Modifier) {
    val space = OreTheme.space
    Column(modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(OreTheme.color.surface.raised)
                .padding(horizontal = space.md, vertical = space.sm),
        ) { NightshiftSkeletonBar(fraction = 0.3f, tall = true) }
        Column(
            modifier = Modifier.fillMaxWidth().padding(space.md),
            verticalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            NightshiftSkeletonCard {
                NightshiftSkeletonBar(fraction = 0.2f)
                repeat(NightshiftStatesCopy.SKELETON_FLEET_ROWS) {
                    NightshiftSkeletonBar(fraction = if (it % 2 == 0) 0.7f else 0.45f)
                }
            }
            NightshiftSkeletonBar(fraction = 0.3f)
            repeat(NightshiftStatesCopy.SKELETON_SUGGESTIONS) {
                NightshiftSkeletonCard {
                    NightshiftSkeletonBar(fraction = 0.5f)
                    NightshiftSkeletonBar(fraction = 0.85f)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(space.sm),
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(OreTheme.size.buttonHeight)
                                .background(OreTheme.color.surface.highlight),
                        )
                        Box(
                            Modifier
                                .weight(1f)
                                .height(OreTheme.size.buttonHeight)
                                .background(OreTheme.color.surface.highlight),
                        )
                    }
                }
            }
            NightshiftSkeletonBar(fraction = 0.15f)
            repeat(NightshiftStatesCopy.SKELETON_INTEL) {
                NightshiftSkeletonCard {
                    NightshiftSkeletonBar(fraction = 0.45f)
                    NightshiftSkeletonBar(fraction = 0.8f)
                    NightshiftSkeletonBar(fraction = 0.25f)
                }
            }
        }
    }
}

/** Console error with retry. Fixed copy only — no raw error string is passed in or rendered. */
@Composable
fun NightshiftError(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
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
            OreText(
                text = NightshiftStatesCopy.ERROR_TITLE,
                style = OreTheme.type.titleSmall,
                color = OreTheme.color.text.primary,
            )
            OreText(
                text = NightshiftStatesCopy.ERROR_BODY,
                style = OreTheme.type.body,
                color = OreTheme.color.text.secondary,
            )
            OrePrimaryButton(text = NightshiftStatesCopy.RETRY, onClick = onRetry)
        }
    }
}

/**
 * Preview-flag informational surface: states that the module is in preview and that action
 * controls are disabled. It carries no action control of its own.
 */
@Composable
fun NightshiftPreviewNote(modifier: Modifier = Modifier) {
    val space = OreTheme.space
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.status.info.container)
            .padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.xs),
    ) {
        OreText(
            text = NightshiftStatesCopy.PREVIEW_TITLE,
            style = OreTheme.type.bodyStrong,
            color = OreTheme.color.status.info.content,
        )
        OreText(
            text = NightshiftStatesCopy.PREVIEW_BODY,
            style = OreTheme.type.body,
            color = OreTheme.color.status.info.content,
        )
        OreText(
            text = NightshiftConsoleCopy.PREVIEW_NOTE,
            style = OreTheme.type.caption,
            color = OreTheme.color.status.info.content,
        )
    }
}

@Composable
private fun NightshiftSkeletonCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(OreTheme.space.md),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
    ) { content() }
}

@Composable
private fun NightshiftSkeletonBar(fraction: Float, tall: Boolean = false) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(if (tall) OreTheme.space.lg else OreTheme.space.md)
            .background(OreTheme.color.surface.highlight),
    )
}
