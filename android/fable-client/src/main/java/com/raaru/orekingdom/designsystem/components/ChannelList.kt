package com.raaru.orekingdom.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.raaru.orekingdom.designsystem.tokens.OreTheme

private const val ChannelGlyph = "#"
private const val UnreadCountCap = 99
private const val UnreadCountOverflow = "99+"

/** Category header in the channel rail. Title is rendered as supplied by the server. */
@Composable
fun CategoryHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(OreTheme.size.categoryHeaderHeight)
            .semantics { heading() }
            .padding(horizontal = OreTheme.space.md),
        contentAlignment = Alignment.CenterStart,
    ) {
        OreText(
            text = title.uppercase(),
            style = OreTheme.type.overline,
            color = OreTheme.color.text.tertiary,
            maxLines = 1,
        )
    }
}

/**
 * Channel row. [unread] switches the name to the strong style; [unreadCount] > 0 shows the
 * count badge. [note] is an optional trailing caption (e.g. a placeholder-kind channel).
 */
@Composable
fun ChannelRow(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    unread: Boolean = false,
    unreadCount: Int = 0,
    glyph: String = ChannelGlyph,
    note: String? = null,
) {
    val colors = OreTheme.color
    val container by animateColorAsState(
        targetValue = if (selected) colors.surface.highlight else colors.surface.highlight.copy(alpha = 0f),
        animationSpec = tween(
            durationMillis = OreTheme.motion.duration.standard,
            easing = OreTheme.motion.easing.standard,
        ),
        label = "oreChannelRowContainer",
    )
    val emphasised = unread || selected
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(OreTheme.size.channelRowHeight)
            .padding(horizontal = OreTheme.space.xs)
            .clip(OreTheme.shape.sm)
            .background(container)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = OreTheme.space.sm),
        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OreText(
            text = glyph,
            style = OreTheme.type.body,
            color = colors.text.tertiary,
            maxLines = 1,
        )
        OreText(
            text = name,
            style = if (unread) OreTheme.type.bodyStrong else OreTheme.type.body,
            color = if (emphasised) colors.text.primary else colors.text.secondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (note != null) {
            OreText(
                text = note,
                style = OreTheme.type.caption,
                color = colors.text.tertiary,
                maxLines = 1,
            )
        }
        if (unreadCount > 0) UnreadBadge(count = unreadCount)
    }
}

@Composable
fun UnreadBadge(count: Int) {
    Box(
        modifier = Modifier
            .height(OreTheme.size.badgeHeight)
            .defaultMinSize(minWidth = OreTheme.size.badgeHeight)
            .clip(OreTheme.shape.pill)
            .background(OreTheme.color.accent.primary)
            .padding(horizontal = OreTheme.space.xs),
        contentAlignment = Alignment.Center,
    ) {
        OreText(
            text = if (count > UnreadCountCap) UnreadCountOverflow else count.toString(),
            style = OreTheme.type.badge,
            color = OreTheme.color.accent.onPrimary,
            maxLines = 1,
        )
    }
}
