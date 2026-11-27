package com.raaru.orekingdom.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.raaru.orekingdom.designsystem.tokens.OreTheme

/** Display role: the caller's single highest active role, as delivered by the server. */
enum class OreRole { Free, Member, Operator, Syndicate, Prestige }

/** The one place a role resolves to its colour. Every name render goes through this. */
@Composable
@ReadOnlyComposable
fun roleColor(role: OreRole): Color = when (role) {
    OreRole.Free -> OreTheme.color.role.free
    OreRole.Member -> OreTheme.color.role.member
    OreRole.Operator -> OreTheme.color.role.operator
    OreRole.Syndicate -> OreTheme.color.role.syndicate
    OreRole.Prestige -> OreTheme.color.role.prestige
}

private fun roleLabel(role: OreRole): String = when (role) {
    OreRole.Free -> OreComponentCopy.RoleFree
    OreRole.Member -> OreComponentCopy.RoleMember
    OreRole.Operator -> OreComponentCopy.RoleOperator
    OreRole.Syndicate -> OreComponentCopy.RoleSyndicate
    OreRole.Prestige -> OreComponentCopy.RolePrestige
}

/** Shared chip primitive for every badge and label. */
@Composable
internal fun OreChip(
    text: String,
    content: Color,
    container: Color,
    modifier: Modifier = Modifier,
    shape: Shape = OreTheme.shape.xs,
    borderColor: Color? = null,
    leadingDot: Boolean = false,
) {
    Row(
        modifier = modifier
            .height(OreTheme.size.badgeHeight)
            .clip(shape)
            .background(container)
            .then(
                if (borderColor != null) {
                    Modifier.border(OreTheme.size.strokeHairline, borderColor, shape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = OreTheme.space.xs),
        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingDot) {
            Box(
                modifier = Modifier
                    .size(OreTheme.space.xs)
                    .clip(OreTheme.shape.pill)
                    .background(content),
            )
        }
        OreText(
            text = text,
            style = OreTheme.type.badge,
            color = content,
            maxLines = 1,
        )
    }
}

@Composable
private fun TintedBadge(text: String, tint: Color, modifier: Modifier = Modifier) {
    OreChip(
        text = text,
        content = tint,
        container = tint.copy(alpha = OreTheme.alpha.tintContainer),
        modifier = modifier,
    )
}

@Composable
fun RoleBadge(role: OreRole, modifier: Modifier = Modifier) {
    TintedBadge(text = roleLabel(role), tint = roleColor(role), modifier = modifier)
}

/** Cosmetic Founding mark. */
@Composable
fun FoundingBadge(modifier: Modifier = Modifier) {
    TintedBadge(
        text = OreComponentCopy.Founding,
        tint = OreTheme.color.role.founding,
        modifier = modifier,
    )
}

/** Moderator mark; orthogonal to the paid role and shown alongside it. */
@Composable
fun ModeratorBadge(modifier: Modifier = Modifier) {
    TintedBadge(
        text = OreComponentCopy.Moderator,
        tint = OreTheme.color.role.moderator,
        modifier = modifier,
    )
}

/** Crown flair for the current monthly holder. */
@Composable
fun CrownBadge(modifier: Modifier = Modifier) {
    val tone = OreTheme.color.label.crown
    OreChip(
        text = OreComponentCopy.Crown,
        content = tone.content,
        container = tone.container,
        modifier = modifier,
        borderColor = tone.content,
    )
}

/** Permanent commercial label: filled, square-cornered. */
@Composable
fun CommercialLabel(modifier: Modifier = Modifier) {
    val tone = OreTheme.color.label.commercial
    OreChip(
        text = OreComponentCopy.Commercial,
        content = tone.content,
        container = tone.container,
        modifier = modifier,
    )
}

/** Mandatory sponsored label. */
@Composable
fun SponsoredLabel(modifier: Modifier = Modifier) {
    val tone = OreTheme.color.label.sponsored
    OreChip(
        text = OreComponentCopy.Sponsored,
        content = tone.content,
        container = tone.container,
        modifier = modifier,
    )
}

/**
 * Native (free, moderator-managed) pin marker: outlined pill with a leading dot.
 * Shares no hue, shape, or word with the commercial label or the spotlight treatment.
 */
@Composable
fun PinnedMarker(modifier: Modifier = Modifier) {
    val tone = OreTheme.color.label.nativePin
    OreChip(
        text = OreComponentCopy.Pinned,
        content = tone.content,
        container = tone.container,
        modifier = modifier,
        shape = OreTheme.shape.pill,
        borderColor = tone.content,
        leadingDot = true,
    )
}

/** Marks offline-cached content as stale. */
@Composable
fun StaleBadge(modifier: Modifier = Modifier) {
    val tone = OreTheme.color.message.stale
    OreChip(
        text = OreComponentCopy.Stale,
        content = tone.content,
        container = tone.container,
        modifier = modifier,
    )
}

/** Role badge plus any orthogonal marks, in a fixed order. Used wherever a name renders. */
@Composable
fun IdentityBadges(
    role: OreRole,
    modifier: Modifier = Modifier,
    founding: Boolean = false,
    moderator: Boolean = false,
    crown: Boolean = false,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoleBadge(role)
        if (moderator) ModeratorBadge()
        if (founding) FoundingBadge()
        if (crown) CrownBadge()
    }
}
