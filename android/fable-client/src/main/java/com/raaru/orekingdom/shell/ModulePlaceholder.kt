package com.raaru.orekingdom.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.raaru.orekingdom.designsystem.components.InfoCard
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme

private const val PreviewTitle = "Preview"
private const val PreviewNote = "Preview — actions are not yet available."
private const val LoadingDescription = "Loading"
private const val EmptyTitle = "Nothing here yet"
private const val EmptyBody = "This area is on its way."
private const val ErrorTitle = "Couldn't load this area"
private const val ErrorBody = "Check your connection and try again."
private const val Retry = "Retry"
private const val SkeletonBlocks = 3

enum class ModulePlaceholderStatus { Loading, Empty, Error }

/**
 * A rendered action control. [onClick] is a stub callback only.
 * TODO-CONTRACT: no action shape exists in the contract; nothing here moves value.
 */
@Immutable
data class ModulePlaceholderAction(
    val label: String,
    val onClick: () -> Unit = {}, // TODO-CONTRACT stub
)

/**
 * Placeholder surface for a preview/on module until its console lands. Never blank: an
 * `Off` entry (not routable via the registry) renders nothing because it is never reached.
 */
@Composable
fun ModulePlaceholder(
    entry: ModuleEntry,
    modifier: Modifier = Modifier,
    status: ModulePlaceholderStatus = ModulePlaceholderStatus.Empty,
    actions: List<ModulePlaceholderAction> = emptyList(),
    onRetry: () -> Unit = {},
) {
    if (entry.state == ModuleFlag.Off) return
    val preview = entry.state == ModuleFlag.Preview
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OreTheme.color.surface.base)
            .padding(OreTheme.space.lg),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.lg),
    ) {
        OreText(
            text = entry.label,
            style = OreTheme.type.titleMedium,
            color = OreTheme.color.text.primary,
            maxLines = 1,
        )
        if (preview) InfoCard(title = PreviewTitle, body = PreviewNote)
        when (status) {
            ModulePlaceholderStatus.Loading -> PlaceholderSkeleton()
            ModulePlaceholderStatus.Error -> {
                PlaceholderMessage(
                    title = ErrorTitle,
                    body = ErrorBody,
                    titleColor = OreTheme.color.status.error.content,
                )
                OreGhostButton(text = Retry, onClick = onRetry)
            }
            ModulePlaceholderStatus.Empty -> {
                PlaceholderMessage(
                    title = EmptyTitle,
                    body = EmptyBody,
                    titleColor = OreTheme.color.text.primary,
                )
                actions.forEach { action ->
                    OrePrimaryButton(
                        text = action.label,
                        onClick = action.onClick, // TODO-CONTRACT stub
                        enabled = !preview,
                        disabledNote = PreviewNote,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceholderMessage(
    title: String,
    body: String,
    titleColor: androidx.compose.ui.graphics.Color,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs)) {
        OreText(text = title, style = OreTheme.type.titleSmall, color = titleColor, maxLines = 2)
        OreText(text = body, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
    }
}

@Composable
private fun PlaceholderSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = LoadingDescription },
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.md),
    ) {
        repeat(SkeletonBlocks) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(OreTheme.size.memberRowHeight)
                    .clip(OreTheme.shape.lg)
                    .background(OreTheme.color.surface.raised),
            )
        }
    }
}
