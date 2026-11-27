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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme

internal object IdentityStatesCopy {
    const val MEMBERS_EMPTY_TITLE = "No members to show"
    const val MEMBERS_EMPTY_BODY = "Members appear here as they join this channel."
    const val REFRESH = "Refresh"
    const val ERROR_TITLE = "This could not be loaded"
    const val ERROR_BODY = "Check your connection and try again."
    const val RETRY = "Retry"
    const val SKELETON_SECTIONS = 2
    const val SKELETON_ROWS_PER_SECTION = 4
}

/** Loading shapes for the member list: title bar, then presence section headers with member rows. */
@Composable
fun MemberListSkeleton(modifier: Modifier = Modifier) {
    val space = OreTheme.space
    val size = OreTheme.size
    Column(modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(OreTheme.color.surface.raised)
                .padding(horizontal = space.md, vertical = space.sm),
        ) { IdentitySkeletonBar(fraction = 0.3f, tall = true) }
        repeat(IdentityStatesCopy.SKELETON_SECTIONS) { section ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(size.categoryHeaderHeight)
                    .padding(horizontal = space.md),
                contentAlignment = Alignment.CenterStart,
            ) { IdentitySkeletonBar(fraction = 0.25f) }
            repeat(IdentityStatesCopy.SKELETON_ROWS_PER_SECTION) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(size.memberRowHeight)
                        .padding(horizontal = space.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(space.sm),
                ) {
                    IdentitySkeletonBlock(Modifier.size(size.avatarSmall))
                    IdentitySkeletonBar(fraction = if ((section + row) % 2 == 0) 0.5f else 0.35f)
                }
            }
        }
    }
}

/** Empty member list. Refresh renders only when a callback is supplied. */
@Composable
fun MemberListEmpty(
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
) {
    IdentityStateMessage(
        title = IdentityStatesCopy.MEMBERS_EMPTY_TITLE,
        body = IdentityStatesCopy.MEMBERS_EMPTY_BODY,
        modifier = modifier,
    ) {
        if (onRefresh != null) OreGhostButton(text = IdentityStatesCopy.REFRESH, onClick = onRefresh)
    }
}

/** Loading shapes for the profile: identity card (avatar, name, role, badges) and the pass card. */
@Composable
fun ProfileSkeleton(modifier: Modifier = Modifier) {
    val space = OreTheme.space
    val size = OreTheme.size
    Column(
        modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base).padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.md),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().background(OreTheme.color.surface.raised).padding(space.md),
            verticalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(space.sm),
            ) {
                IdentitySkeletonBlock(Modifier.size(size.avatarMedium))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(space.xs)) {
                    IdentitySkeletonBar(fraction = 0.5f, tall = true)
                    IdentitySkeletonBar(fraction = 0.3f)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(space.xs)) {
                repeat(3) { IdentitySkeletonBlock(Modifier.size(width = size.touchTarget, height = size.badgeHeight)) }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().background(OreTheme.color.surface.raised).padding(space.md),
            verticalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            IdentitySkeletonBar(fraction = 0.2f)
            IdentitySkeletonBar(fraction = 0.6f)
            IdentitySkeletonBlock(Modifier.fillMaxWidth(0.4f).height(size.buttonHeight))
        }
    }
}

/** Error for member list or profile. Fixed copy only — no raw error string is passed in or rendered. */
@Composable
fun IdentityError(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IdentityStateMessage(
        title = IdentityStatesCopy.ERROR_TITLE,
        body = IdentityStatesCopy.ERROR_BODY,
        modifier = modifier,
    ) {
        OrePrimaryButton(text = IdentityStatesCopy.RETRY, onClick = onRetry)
    }
}

@Composable
private fun IdentityStateMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit,
) {
    val space = OreTheme.space
    Box(
        modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base).padding(space.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().background(OreTheme.color.surface.raised).padding(space.lg),
            verticalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            OreText(text = title, style = OreTheme.type.titleSmall, color = OreTheme.color.text.primary)
            OreText(text = body, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
            action()
        }
    }
}

@Composable
private fun IdentitySkeletonBar(fraction: Float, tall: Boolean = false) {
    IdentitySkeletonBlock(
        Modifier.fillMaxWidth(fraction).height(if (tall) OreTheme.space.lg else OreTheme.space.md),
    )
}

@Composable
private fun IdentitySkeletonBlock(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(OreTheme.color.surface.highlight))
}
