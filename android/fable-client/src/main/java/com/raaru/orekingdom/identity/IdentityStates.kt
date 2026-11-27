package com.raaru.orekingdom.identity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
