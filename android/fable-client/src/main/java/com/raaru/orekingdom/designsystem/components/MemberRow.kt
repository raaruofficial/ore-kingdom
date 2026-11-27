package com.raaru.orekingdom.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.raaru.orekingdom.designsystem.tokens.OreTheme

/** Presence state as delivered by the presence feed. */
enum class OrePresence { Online, Idle, Offline }

/**
 * Member row for the member list and presence panel. [activity] is rendered verbatim as
 * supplied by the server; null renders nothing.
 */
@Composable
fun MemberRow(
    displayName: String,
    role: OreRole,
    presence: OrePresence,
    modifier: Modifier = Modifier,
    activity: String? = null,
    founding: Boolean = false,
    moderator: Boolean = false,
    crown: Boolean = false,
    avatar: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = OreTheme.color
    val presenceLabel = when (presence) {
        OrePresence.Online -> OreComponentCopy.PresenceOnline
        OrePresence.Idle -> OreComponentCopy.PresenceIdle
        OrePresence.Offline -> OreComponentCopy.PresenceOffline
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(OreTheme.size.memberRowHeight)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { stateDescription = presenceLabel }
            .padding(horizontal = OreTheme.space.md),
        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(OreTheme.size.avatarMedium)) {
            Box(
                modifier = Modifier
                    .size(OreTheme.size.avatarMedium)
                    .clip(OreTheme.shape.pill)
                    .background(colors.surface.highlight),
                contentAlignment = Alignment.Center,
            ) {
                if (avatar != null) {
                    avatar()
                } else {
                    OreText(
                        text = displayName.take(1).uppercase(),
                        style = OreTheme.type.label,
                        color = colors.text.secondary,
                        maxLines = 1,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(OreTheme.size.presenceDot)
                    .clip(OreTheme.shape.pill)
                    .background(
                        when (presence) {
                            OrePresence.Online -> colors.presence.online
                            OrePresence.Idle -> colors.presence.idle
                            OrePresence.Offline -> colors.presence.offline
                        },
                    )
                    .border(OreTheme.size.strokeEmphasis, colors.surface.base, OreTheme.shape.pill),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OreText(
                    text = displayName,
                    style = OreTheme.type.titleSmall,
                    color = roleColor(role),
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                )
                IdentityBadges(
                    role = role,
                    founding = founding,
                    moderator = moderator,
                    crown = crown,
                )
            }
            if (activity != null) {
                OreText(
                    text = activity,
                    style = OreTheme.type.caption,
                    color = colors.text.tertiary,
                    maxLines = 1,
                )
            }
        }
    }
}
