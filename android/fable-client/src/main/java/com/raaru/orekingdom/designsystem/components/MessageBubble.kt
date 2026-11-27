package com.raaru.orekingdom.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.raaru.orekingdom.designsystem.tokens.OreTheme

/** Delivery state of a message as known to the client. */
enum class OreMessageDelivery { Delivered, Sending, Queued }

/** Aggregate reaction state for one emoji. */
@Immutable
data class OreReaction(
    val emoji: String,
    val count: Int,
    val mine: Boolean,
)

/**
 * Message bubble. [own] selects the own-message container; [deleted] renders the tombstone.
 * Sending and queued states are never rendered as delivered.
 */
@Composable
fun MessageBubble(
    authorName: String,
    authorRole: OreRole,
    body: String,
    timestamp: String,
    modifier: Modifier = Modifier,
    own: Boolean = false,
    delivery: OreMessageDelivery = OreMessageDelivery.Delivered,
    edited: Boolean = false,
    deleted: Boolean = false,
    commercial: Boolean = false,
    stale: Boolean = false,
    founding: Boolean = false,
    moderator: Boolean = false,
    crown: Boolean = false,
    reactions: List<OreReaction> = emptyList(),
    reactionsEnabled: Boolean = false,
    onReactionToggle: (emoji: String) -> Unit = {},
) {
    val colors = OreTheme.color
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OreText(
                text = authorName,
                style = OreTheme.type.titleSmall,
                color = roleColor(authorRole),
                maxLines = 1,
            )
            IdentityBadges(
                role = authorRole,
                founding = founding,
                moderator = moderator,
                crown = crown,
            )
            OreText(
                text = timestamp,
                style = OreTheme.type.caption,
                color = colors.text.tertiary,
                maxLines = 1,
            )
        }
        if (deleted) {
            OreText(
                text = OreComponentCopy.Tombstone,
                style = OreTheme.type.body,
                color = colors.message.tombstoneContent,
                modifier = Modifier
                    .clip(OreTheme.shape.md)
                    .background(colors.message.tombstone)
                    .padding(horizontal = OreTheme.space.md, vertical = OreTheme.space.sm),
            )
        } else {
            Column(
                modifier = Modifier
                    .clip(OreTheme.shape.md)
                    .background(if (own) colors.message.bubbleOwn else colors.message.bubble)
                    .padding(horizontal = OreTheme.space.md, vertical = OreTheme.space.sm),
                verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
            ) {
                if (commercial) CommercialLabel()
                OreText(
                    text = body,
                    style = OreTheme.type.body,
                    color = colors.text.primary,
                    modifier = Modifier.alpha(
                        if (delivery == OreMessageDelivery.Delivered) 1f else OreTheme.alpha.sending,
                    ),
                )
                if (edited || stale || delivery != OreMessageDelivery.Delivered) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (delivery) {
                            OreMessageDelivery.Sending -> OreText(
                                text = OreComponentCopy.Sending,
                                style = OreTheme.type.caption,
                                color = colors.message.sending,
                            )
                            OreMessageDelivery.Queued -> OreChip(
                                text = OreComponentCopy.Queued,
                                content = colors.message.queued.content,
                                container = colors.message.queued.container,
                            )
                            OreMessageDelivery.Delivered -> Unit
                        }
                        if (edited) {
                            OreText(
                                text = OreComponentCopy.Edited,
                                style = OreTheme.type.caption,
                                color = colors.text.tertiary,
                            )
                        }
                        if (stale) StaleBadge()
                    }
                }
            }
            if (reactions.isNotEmpty()) {
                ReactionRow(
                    reactions = reactions,
                    enabled = reactionsEnabled,
                    onToggle = onReactionToggle,
                )
            }
        }
    }
}

/** Reaction chips under a message; counts are aggregate per emoji. */
@Composable
fun ReactionRow(
    reactions: List<OreReaction>,
    modifier: Modifier = Modifier,
    enabled: Boolean = false,
    onToggle: (emoji: String) -> Unit = {},
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        reactions.forEach { reaction ->
            ReactionChip(reaction = reaction, enabled = enabled, onToggle = onToggle)
        }
    }
}

@Composable
private fun ReactionChip(
    reaction: OreReaction,
    enabled: Boolean,
    onToggle: (emoji: String) -> Unit,
) {
    val colors = OreTheme.color
    val shape = OreTheme.shape.sm
    val container by animateColorAsState(
        targetValue = if (reaction.mine) colors.message.reactionChipMine else colors.message.reactionChip,
        animationSpec = tween(
            durationMillis = OreTheme.motion.duration.fast,
            easing = OreTheme.motion.easing.standard,
        ),
        label = "oreReactionChipContainer",
    )
    Box(
        modifier = Modifier
            .heightIn(min = OreTheme.size.touchTarget)
            .toggleable(
                value = reaction.mine,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle(reaction.emoji) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = OreTheme.size.reactionChipHeight)
                .clip(shape)
                .background(container)
                .then(
                    if (reaction.mine) {
                        Modifier.border(
                            OreTheme.size.strokeHairline,
                            colors.message.reactionChipMineBorder,
                            shape,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = OreTheme.space.sm),
            horizontalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OreText(
                text = reaction.emoji,
                style = OreTheme.type.label,
                color = colors.text.primary,
                maxLines = 1,
            )
            OreText(
                text = reaction.count.toString(),
                style = OreTheme.type.numeric,
                color = if (reaction.mine) colors.accent.onSubtle else colors.text.secondary,
                maxLines = 1,
            )
        }
    }
}
