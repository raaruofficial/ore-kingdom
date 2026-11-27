package com.raaru.orekingdom.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.raaru.orekingdom.designsystem.components.AvailabilityCard
import com.raaru.orekingdom.designsystem.components.CommercialLabel
import com.raaru.orekingdom.designsystem.components.OreAvailability
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.delay

private const val SpotlightLabel = "SPOTLIGHT"
private const val SpotlightLoadingDescription = "Loading spotlight"
private const val VacantTitle = "The spotlight is open"
private const val RemainingRefreshMillis = 30_000L
private const val SpotlightBodyMaxLines = 4

/** Display string of record (BRIEF §8). The floor figure is the server's `floor_usd`. */
private fun floorLine(floorUsd: Int): String = "Spotlight from \$$floorUsd / 12h"

/**
 * The single server-wide paid-prominence slot, rendered above the channel rail for every
 * user. `null` = the read is still loading; [SpotlightUiState.Unavailable] renders nothing.
 * Display only: no bid or purchase control lives here (out of scope; no contract shape).
 */
@Composable
fun SpotlightSlot(
    state: SpotlightUiState?,
    modifier: Modifier = Modifier,
) {
    when (state) {
        null -> SpotlightSkeleton(modifier)
        is SpotlightUiState.Occupied -> SpotlightOccupied(state, modifier)
        is SpotlightUiState.Vacant -> AvailabilityCard(
            title = VacantTitle,
            body = floorLine(state.floorUsd),
            availability = OreAvailability.Open,
            modifier = modifier,
        )
        SpotlightUiState.Unavailable -> Unit
    }
}

@Composable
private fun SpotlightOccupied(state: SpotlightUiState.Occupied, modifier: Modifier) {
    val colors = OreTheme.color.spotlight
    val shape = OreTheme.shape.lg
    val remaining = rememberRemaining(state.windowEndsAt)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.container)
            .border(OreTheme.size.strokeHairline, colors.border, shape)
            .padding(OreTheme.space.md),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OreText(
                text = SpotlightLabel,
                style = OreTheme.type.badge,
                color = colors.label,
                maxLines = 1,
            )
            if (state.commercial) CommercialLabel()
            Box(modifier = Modifier.weight(1f))
            if (remaining != null) {
                OreText(
                    text = remaining,
                    style = OreTheme.type.numeric,
                    color = colors.label,
                    maxLines = 1,
                )
            }
        }
        val author = state.authorName
        if (author != null) {
            OreText(
                text = author,
                style = OreTheme.type.titleSmall,
                color = colors.content,
                maxLines = 1,
            )
        }
        OreText(
            text = state.body,
            style = OreTheme.type.body,
            color = colors.content,
            maxLines = SpotlightBodyMaxLines,
        )
        OreText(
            text = floorLine(state.floorUsd),
            style = OreTheme.type.caption,
            // A11Y-04: this line sits on the spotlight container
            // (ice900), where tertiary is 4.28 — under 4.5.
            color = OreTheme.color.text.secondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun SpotlightSkeleton(modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(OreTheme.size.memberRowHeight)
            .alpha(OreTheme.alpha.sending)
            .clip(OreTheme.shape.lg)
            .background(OreTheme.color.spotlight.container)
            .semantics { contentDescription = SpotlightLoadingDescription },
    )
}

/** Remaining window time for display, from the server's `window_ends_at`; null if unreadable or over. */
@Composable
private fun rememberRemaining(windowEndsAt: String): String? {
    val text by produceState(initialValue = remainingText(windowEndsAt), windowEndsAt) {
        while (true) {
            value = remainingText(windowEndsAt)
            delay(RemainingRefreshMillis)
        }
    }
    return text
}

private fun remainingText(windowEndsAt: String): String? {
    val end = runCatching { OffsetDateTime.parse(windowEndsAt).toInstant() }.getOrNull() ?: return null
    val minutes = Duration.between(Instant.now(), end).let {
        if (it <= Duration.ZERO) return null
        (it.seconds + 59 + if (it.nano > 0) 1 else 0) / 60
    }
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m left" else "${minutes}m left"
}
