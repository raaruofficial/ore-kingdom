package com.raaru.orekingdom.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.raaru.orekingdom.designsystem.tokens.OreTheme

private const val TombstoneRemoved = "Message removed"

/** Why the message is gone: deleted by its author, or removed by moderation. */
enum class OreTombstoneKind { Deleted, Removed }

/** Standalone tombstone row for a deleted or removed message. */
@Composable
fun TombstoneRow(
    modifier: Modifier = Modifier,
    kind: OreTombstoneKind = OreTombstoneKind.Deleted,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(OreTheme.shape.sm)
            .background(OreTheme.color.message.tombstone)
            .padding(horizontal = OreTheme.space.md, vertical = OreTheme.space.sm),
        contentAlignment = Alignment.CenterStart,
    ) {
        OreText(
            text = when (kind) {
                OreTombstoneKind.Deleted -> OreComponentCopy.Tombstone
                OreTombstoneKind.Removed -> TombstoneRemoved
            },
            style = OreTheme.type.caption,
            color = OreTheme.color.message.tombstoneContent,
            maxLines = 1,
        )
    }
}
