package com.raaru.orekingdom.identity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.IdentityBadges
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OrePresence
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreRole
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.components.PresenceDot
import com.raaru.orekingdom.designsystem.components.presenceLabel
import com.raaru.orekingdom.designsystem.components.roleColor
import com.raaru.orekingdom.designsystem.components.roleLabel
import com.raaru.orekingdom.designsystem.tokens.OreTheme
import com.raaru.orekingdom.shell.Entitlements

enum class ProfileStatus { Loading, Ready, Error }

/**
 * Profile state. For the caller's own profile ([isSelf]) role, Founding and expiry render from the
 * Entitlements parameter of [ProfileScreen]; [memberRole]/[founding] here are used for other users
 * (contract Member shape). [moderator] is orthogonal to the paid role. [crownMonth] and [presence]
 * / [activity] are rendered verbatim as supplied; null renders nothing.
 */
data class ProfileUiState(
    val status: ProfileStatus = ProfileStatus.Loading,
    val userId: String = "",
    val displayName: String = "",
    val isSelf: Boolean = false,
    val memberRole: String = "free",
    val founding: Boolean = false,
    val moderator: Boolean = false,
    val crown: Boolean = false,
    val crownMonth: String? = null,
    val presence: OrePresence? = null,
    val activity: String? = null,
)

internal object ProfileCopy {
    const val ERROR = "This profile could not be loaded."
    const val RETRY = "Retry"
    const val PASS_HEADER = "PASS"
    const val PASS_ACTIVE_UNTIL = "Pass active until"
    const val PASS_VIEW = "View passes"
    const val PASS_EXTEND = "Extend pass"
    const val READER_TITLE = "You're reading for free"
    const val READER_BODY = "Reading is free forever. Your messages, profile and marks stay yours. " +
        "A pass unlocks posting. Nothing auto-renews."
    const val LIFETIME = "Lifetime"
    const val CROWN_HEADER = "THE CROWN"
    const val REPORT = "Report profile"
    const val TIP = "Tip"
}

/**
 * Display mapping of the contract role string to the F1 role mark. This is the single place a
 * role string is read; it selects a badge/colour only. Unknown strings render as the Free mark.
 */
internal fun oreRoleOf(role: String): OreRole = when (role) {
    "member" -> OreRole.Member
    "operator" -> OreRole.Operator
    "syndicate" -> OreRole.Syndicate
    "prestige" -> OreRole.Prestige
    else -> OreRole.Free
}

/**
 * Profile surface. Re-renders from whatever [entitlements] object is passed (bootstrap read,
 * `role.update`, `entitlement.update`); it computes no role logic and no expiry maths.
 * Lapse shape: when the read carries no active pass, the profile, name and Founding mark remain
 * and the pass entry is offered again — no dead end.
 */
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    entitlements: Entitlements?,
    onReport: (userId: String) -> Unit,
    modifier: Modifier = Modifier,
    onPassSelected: () -> Unit = {},
    onTip: (userId: String) -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val space = OreTheme.space
    val awaitingSelf = state.isSelf && entitlements == null
    Box(modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base)) {
        when {
            state.status == ProfileStatus.Error -> ProfileError(onRetry = onRetry)
            state.status == ProfileStatus.Loading || awaitingSelf -> ProfileSkeleton()
            else -> {
                val role = oreRoleOf(if (state.isSelf && entitlements != null) entitlements.role else state.memberRole)
                val founding = if (state.isSelf && entitlements != null) entitlements.founding else state.founding
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(space.md),
                    verticalArrangement = Arrangement.spacedBy(space.md),
                ) {
                    ProfileSection {
                        OreText(
                            text = state.displayName,
                            style = OreTheme.type.titleMedium,
                            color = roleColor(role),
                            maxLines = 1,
                        )
                        OreText(
                            text = roleLabel(role),
                            style = OreTheme.type.label,
                            color = OreTheme.color.text.secondary,
                            maxLines = 1,
                        )
                        IdentityBadges(
                            role = role,
                            founding = founding,
                            moderator = state.moderator,
                            crown = state.crown,
                        )
                        if (state.presence != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(space.xs),
                            ) {
                                PresenceDot(presence = state.presence)
                                OreText(
                                    text = presenceLabel(state.presence),
                                    style = OreTheme.type.caption,
                                    color = OreTheme.color.text.secondary,
                                    maxLines = 1,
                                )
                                if (state.activity != null) {
                                    OreText(
                                        text = state.activity,
                                        style = OreTheme.type.caption,
                                        color = OreTheme.color.text.tertiary,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                    if (state.crown && state.crownMonth != null) {
                        ProfileSection {
                            ProfileOverline(ProfileCopy.CROWN_HEADER)
                            OreText(
                                text = state.crownMonth,
                                style = OreTheme.type.bodyStrong,
                                color = OreTheme.color.label.crown.content,
                                maxLines = 1,
                            )
                        }
                    }
                    if (state.isSelf && entitlements != null) {
                        PassSection(role = role, expiresAt = entitlements.expiresAt, onPassSelected = onPassSelected)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(space.sm)) {
                            if (entitlements?.capabilities?.tip == true) {
                                OreGhostButton(text = ProfileCopy.TIP, onClick = { onTip(state.userId) })
                            }
                            OreGhostButton(text = ProfileCopy.REPORT, onClick = { onReport(state.userId) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Own-profile pass block. Active pass: expiry verbatim from the read + extend entry.
 * Lifetime holder: no expiry, no offer. No active pass (Free or lapsed): the pass entry.
 */
@Composable
private fun PassSection(role: OreRole, expiresAt: String?, onPassSelected: () -> Unit) {
    val lifetime = role == OreRole.Prestige
    ProfileSection {
        ProfileOverline(ProfileCopy.PASS_HEADER)
        when {
            lifetime -> OreText(
                text = "${roleLabel(role)} · ${ProfileCopy.LIFETIME}",
                style = OreTheme.type.bodyStrong,
                color = OreTheme.color.text.primary,
            )
            expiresAt != null -> {
                OreText(
                    text = ProfileCopy.PASS_ACTIVE_UNTIL,
                    style = OreTheme.type.caption,
                    color = OreTheme.color.text.secondary,
                )
                OreText(
                    text = expiresAt,
                    style = OreTheme.type.numeric,
                    color = OreTheme.color.text.primary,
                    maxLines = 1,
                )
                OreGhostButton(text = ProfileCopy.PASS_EXTEND, onClick = onPassSelected)
            }
            else -> {
                OreText(
                    text = ProfileCopy.READER_TITLE,
                    style = OreTheme.type.bodyStrong,
                    color = OreTheme.color.text.primary,
                )
                OreText(
                    text = ProfileCopy.READER_BODY,
                    style = OreTheme.type.body,
                    color = OreTheme.color.text.secondary,
                )
                OrePrimaryButton(text = ProfileCopy.PASS_VIEW, onClick = onPassSelected)
            }
        }
    }
}

@Composable
private fun ProfileSection(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(OreTheme.space.md),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) { content() }
}

@Composable
private fun ProfileOverline(text: String) {
    OreText(text = text, style = OreTheme.type.overline, color = OreTheme.color.text.tertiary, maxLines = 1)
}

@Composable
private fun ProfileError(onRetry: () -> Unit) {
    val space = OreTheme.space
    Box(modifier = Modifier.fillMaxSize().padding(space.lg), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().background(OreTheme.color.surface.raised).padding(space.md),
            verticalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            OreText(text = ProfileCopy.ERROR, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
            OreGhostButton(text = ProfileCopy.RETRY, onClick = onRetry)
        }
    }
}

@Composable
private fun ProfileSkeleton() {
    val space = OreTheme.space
    val block = OreTheme.color.surface.highlight
    Column(
        modifier = Modifier.fillMaxSize().padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
            Box(Modifier.size(OreTheme.size.avatarMedium).background(block))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(space.xs)) {
                Box(Modifier.fillMaxWidth(0.5f).height(space.lg).background(block))
                Box(Modifier.fillMaxWidth(0.3f).height(space.md).background(block))
            }
        }
        Box(Modifier.fillMaxWidth().height(OreTheme.size.touchTarget * 2).background(block))
    }
}
