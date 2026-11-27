package com.raaru.orekingdom.modules.pump

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme

internal object PumpStatesCopy {
    const val ERROR_TITLE = "THE PUMP could not be loaded"
    const val ERROR_BODY = "Check your connection and try again."
    const val RETRY = "Retry"
    const val PREVIEW_TITLE = "THE PUMP is in preview"
    const val PREVIEW_BODY = "You can look around: your fleet, day bank and ATM status are shown for information."
}

/** Console error with retry. Fixed copy only — no raw error string is passed in or rendered. */
@Composable
fun PumpError(
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
            OreText(text = PumpStatesCopy.ERROR_TITLE, style = OreTheme.type.titleSmall, color = OreTheme.color.text.primary)
            OreText(text = PumpStatesCopy.ERROR_BODY, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
            OrePrimaryButton(text = PumpStatesCopy.RETRY, onClick = onRetry)
        }
    }
}

/**
 * Preview-flag informational surface: states that the module is in preview and that action
 * controls are disabled. It carries no action control of its own.
 */
@Composable
fun PumpPreviewNote(modifier: Modifier = Modifier) {
    val space = OreTheme.space
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.status.info.container)
            .padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.xs),
    ) {
        OreText(
            text = PumpStatesCopy.PREVIEW_TITLE,
            style = OreTheme.type.bodyStrong,
            color = OreTheme.color.status.info.content,
        )
        OreText(
            text = PumpStatesCopy.PREVIEW_BODY,
            style = OreTheme.type.body,
            color = OreTheme.color.status.info.content,
        )
        OreText(
            text = PumpConsoleCopy.PREVIEW_NOTE,
            style = OreTheme.type.caption,
            color = OreTheme.color.status.info.content,
        )
    }
}
