package com.raaru.orekingdom.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.raaru.orekingdom.designsystem.tokens.OreTheme

private fun presenceLabel(presence: OrePresence): String = when (presence) {
    OrePresence.Online -> OreComponentCopy.PresenceOnline
    OrePresence.Idle -> OreComponentCopy.PresenceIdle
    OrePresence.Offline -> OreComponentCopy.PresenceOffline
}

/** Presence dot in the state's token colour. */
@Composable
fun PresenceDot(
    presence: OrePresence,
    modifier: Modifier = Modifier,
) {
    val colors = OreTheme.color.presence
    Box(
        modifier = modifier
            .size(OreTheme.size.presenceDot)
            .clip(OreTheme.shape.pill)
            .background(
                when (presence) {
                    OrePresence.Online -> colors.online
                    OrePresence.Idle -> colors.idle
                    OrePresence.Offline -> colors.offline
                },
            ),
    )
}

/** Section header for one presence group, e.g. "ONLINE 12". */
@Composable
fun PresenceSectionHeader(
    presence: OrePresence,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(OreTheme.size.categoryHeaderHeight)
            .semantics(mergeDescendants = true) { heading() }
            .padding(horizontal = OreTheme.space.md),
        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresenceDot(presence)
        OreText(
            text = presenceLabel(presence).uppercase(),
            style = OreTheme.type.overline,
            color = OreTheme.color.text.tertiary,
            maxLines = 1,
        )
        OreText(
            text = count.toString(),
            style = OreTheme.type.numeric,
            color = OreTheme.color.text.tertiary,
            maxLines = 1,
        )
    }
}

/**
 * Presence panel row: avatar, role-coloured name, badges, and the server-supplied activity
 * string. Delegates to [MemberRow] so the two surfaces never diverge.
 */
@Composable
fun PresenceRow(
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
    MemberRow(
        displayName = displayName,
        role = role,
        presence = presence,
        modifier = modifier,
        activity = activity,
        founding = founding,
        moderator = moderator,
        crown = crown,
        avatar = avatar,
        onClick = onClick,
    )
}
