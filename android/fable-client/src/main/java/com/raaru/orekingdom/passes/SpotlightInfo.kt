package com.raaru.orekingdom.passes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.AvailabilityCard
import com.raaru.orekingdom.designsystem.components.CommercialLabel
import com.raaru.orekingdom.designsystem.components.OreAvailability
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme
import com.raaru.orekingdom.shell.SpotlightUiState

internal object SpotlightInfoCopy {
    const val HEADER = "SPOTLIGHT"
    const val FLOOR_LINE = "Spotlight from $50 / 12h"
    const val REMAINING = "Window remaining"
    const val WINDOW_ENDS = "Window ends"
    const val VACANT_TITLE = "The spotlight is open"
    const val VACANT_BODY = "One slot, server-wide, shown above the channel rail for every reader."
    const val BID = "Bid for the spotlight"
    const val BID_UNAVAILABLE = "Spotlight bidding is not part of your current access."
    const val ERROR = "Spotlight status could not be loaded."
    const val RETRY = "Retry"
    const val SKELETON_ROWS = 3
}

/**
 * Spotlight info surface. [state] null = loading; Unavailable = error with retry.
 * [windowRemainingText] is host-formatted remaining time; when null the supplied window end is
 * shown verbatim (no time maths here). [canBid] is `capabilities.spotlightBid`.
 * [onBid] is a stub callback (TODO-CONTRACT): no bid shape exists in the contract.
 */
@Composable
fun SpotlightInfo(
    state: SpotlightUiState?,
    canBid: Boolean,
    modifier: Modifier = Modifier,
    windowRemainingText: String? = null,
    onBid: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val space = OreTheme.space
    Column(
        modifier = modifier.fillMaxWidth().background(OreTheme.color.surface.raised).padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.sm),
    ) {
        OreText(text = SpotlightInfoCopy.HEADER, style = OreTheme.type.overline, color = OreTheme.color.spotlight.label, maxLines = 1)
        when (state) {
            null -> repeat(SpotlightInfoCopy.SKELETON_ROWS) { index ->
                Box(
                    Modifier
                        .fillMaxWidth(if (index % 2 == 0) 0.7f else 0.45f)
                        .height(space.md)
                        .background(OreTheme.color.surface.highlight),
                )
            }
            is SpotlightUiState.Unavailable -> {
                OreText(text = SpotlightInfoCopy.ERROR, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
                OreGhostButton(text = SpotlightInfoCopy.RETRY, onClick = onRetry)
            }
            is SpotlightUiState.Vacant -> {
                AvailabilityCard(
                    title = SpotlightInfoCopy.VACANT_TITLE,
                    body = SpotlightInfoCopy.VACANT_BODY,
                    availability = OreAvailability.Open,
                )
                SpotlightFloorAndBid(canBid, onBid)
            }
            is SpotlightUiState.Occupied -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
                    OreText(
                        text = state.authorName ?: state.authorId,
                        style = OreTheme.type.bodyStrong,
                        color = OreTheme.color.text.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (state.commercial) CommercialLabel()
                }
                OreText(text = state.body, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
                    OreText(
                        text = if (windowRemainingText != null) SpotlightInfoCopy.REMAINING else SpotlightInfoCopy.WINDOW_ENDS,
                        style = OreTheme.type.caption,
                        color = OreTheme.color.text.tertiary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    OreText(
                        text = windowRemainingText ?: state.windowEndsAt,
                        style = OreTheme.type.numeric,
                        color = OreTheme.color.text.primary,
                        maxLines = 1,
                    )
                }
                SpotlightFloorAndBid(canBid, onBid)
            }
        }
    }
}

@Composable
private fun SpotlightFloorAndBid(canBid: Boolean, onBid: () -> Unit) {
    OreText(text = SpotlightInfoCopy.FLOOR_LINE, style = OreTheme.type.bodyStrong, color = OreTheme.color.text.primary, maxLines = 1)
    OrePrimaryButton(
        text = SpotlightInfoCopy.BID,
        onClick = onBid,
        modifier = Modifier.fillMaxWidth(),
        enabled = canBid,
        note = if (canBid) null else SpotlightInfoCopy.BID_UNAVAILABLE,
    )
}
