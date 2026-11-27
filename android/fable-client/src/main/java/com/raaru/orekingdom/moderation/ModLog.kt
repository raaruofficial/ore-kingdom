package com.raaru.orekingdom.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme

enum class ModLogStatus { Loading, Ready, Error }

/**
 * Contract Mod log entry: `{ actor_id, action, target_id, reason, at }`.
 * [actorName] / [targetName] are host-resolved display names; ids render when they are null.
 */
data class ModLogEntryUi(
    val actorId: String,
    val action: String,
    val targetId: String,
    val reason: String,
    val at: String,
    val actorName: String? = null,
    val targetName: String? = null,
)

/** [modLoft] comes from the entitlement capability set; when false the surface renders nothing (absence). */
data class ModLogUiState(
    val modLoft: Boolean,
    val status: ModLogStatus = ModLogStatus.Loading,
    val entries: List<ModLogEntryUi> = emptyList(),
)

private object ModLogCopy {
    const val TITLE = "Mod log"
    const val READ_ONLY = "Read-only record. Entries cannot be edited or removed."
    const val EMPTY = "No moderation actions have been logged."
    const val ERROR = "The mod log could not be loaded."
    const val RETRY = "Retry"
    const val ACTOR = "Actor"
    const val TARGET = "Target"
    const val REASON = "Reason"
    const val SKELETON_ROWS = 6
}

/**
 * #mod-log surface. Renders entries exactly as returned, in the returned order. Rows carry no
 * click target, menu, or any edit/delete affordance for any role; the only control is the
 * reload on the error state.
 */
@Composable
fun ModLogScreen(
    state: ModLogUiState,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    if (!state.modLoft) return
    val space = OreTheme.space
    Column(modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(OreTheme.color.surface.raised)
                .padding(horizontal = space.md, vertical = space.sm),
            verticalArrangement = Arrangement.spacedBy(space.xxs),
        ) {
            OreText(text = ModLogCopy.TITLE, style = OreTheme.type.titleSmall, color = OreTheme.color.text.primary, maxLines = 1)
            OreText(text = ModLogCopy.READ_ONLY, style = OreTheme.type.caption, color = OreTheme.color.text.tertiary)
        }
        when {
            state.status == ModLogStatus.Loading && state.entries.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth().padding(space.md),
                verticalArrangement = Arrangement.spacedBy(space.sm),
            ) {
                repeat(ModLogCopy.SKELETON_ROWS) {
                    Box(Modifier.fillMaxWidth().height(OreTheme.size.touchTarget).background(OreTheme.color.surface.highlight))
                }
            }
            state.status == ModLogStatus.Error && state.entries.isEmpty() -> ModLogNotice(ModLogCopy.ERROR, onRetry)
            state.entries.isEmpty() -> ModLogNotice(ModLogCopy.EMPTY)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(space.md),
                verticalArrangement = Arrangement.spacedBy(space.sm),
            ) {
                itemsIndexed(state.entries) { _, entry -> ModLogRow(entry) }
            }
        }
    }
}

@Composable
private fun ModLogRow(entry: ModLogEntryUi) {
    val space = OreTheme.space
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
            OreText(
                text = SanctionRung.of(entry.action)?.label ?: entry.action,
                style = OreTheme.type.bodyStrong,
                color = OreTheme.color.text.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            OreText(text = entry.at, style = OreTheme.type.caption, color = OreTheme.color.text.tertiary, maxLines = 1)
        }
        ModLogLine(ModLogCopy.ACTOR, entry.actorName ?: entry.actorId)
        ModLogLine(ModLogCopy.TARGET, entry.targetName ?: entry.targetId)
        ModLogLine(ModLogCopy.REASON, ReportReason.values().firstOrNull { it.wire == entry.reason }?.label ?: entry.reason)
    }
}

@Composable
private fun ModLogLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm)) {
        OreText(
            text = label,
            style = OreTheme.type.caption,
            color = OreTheme.color.text.tertiary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        OreText(text = value, style = OreTheme.type.body, color = OreTheme.color.text.secondary, maxLines = 1)
    }
}

@Composable
private fun ModLogNotice(text: String, onRetry: (() -> Unit)? = null) {
    val space = OreTheme.space
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(space.md)
            .background(OreTheme.color.surface.raised)
            .padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.sm),
    ) {
        OreText(text = text, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
        if (onRetry != null) OreGhostButton(text = ModLogCopy.RETRY, onClick = onRetry)
    }
}
