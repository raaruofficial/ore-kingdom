package com.raaru.orekingdom.designsystem.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/** Fixed component labels. Mandatory labels live here so no call site can alter or omit them. */
internal object OreComponentCopy {
    const val RoleFree = "READER"
    const val RoleMember = "MEMBER"
    const val RoleOperator = "OPERATOR"
    const val RoleSyndicate = "SYNDICATE"
    const val RolePrestige = "PRESTIGE"
    const val Founding = "FOUNDING"
    const val Moderator = "MOD"
    const val Crown = "CROWN"
    const val Commercial = "COMMERCIAL"
    const val Sponsored = "SPONSORED"
    const val Pinned = "PINNED"
    const val Spotlight = "SPOTLIGHT"
    const val Stale = "STALE"
    const val Queued = "QUEUED, NOT SENT"
    const val Sending = "Sending…"
    const val Edited = "edited"
    const val Tombstone = "Message deleted"
    const val StageReserved = "Coming later"
    const val Send = "Send"
    const val PresenceOnline = "online"
    const val PresenceIdle = "idle"
    const val PresenceOffline = "offline"
}

/** Text primitive: every component renders text through this, with a token style and token colour. */
@Composable
fun OreText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines,
    )
}
