package com.raaru.orekingdom.modules.nightshift

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.components.StaleBadge
import com.raaru.orekingdom.designsystem.tokens.OreTheme

/**
 * One intel entry from the module read model (`{ "items": [ { title, body, severity, at } ] }`).
 * Every field renders verbatim. The client renders intel; it does not compute, rank or filter it.
 */
data class IntelItemUi(
    val title: String,
    val body: String,
    val severity: String,
    val timestamp: String,
)

data class IntelFeedUi(
    val status: NightshiftStatus = NightshiftStatus.Loading,
    val items: List<IntelItemUi> = emptyList(),
    val stale: Boolean = false,
)

/** One member's activity. [activity] is the contract Presence string, rendered verbatim. */
data class NightshiftActivityUi(
    val userId: String,
    val displayName: String,
    val activity: String,
)

internal object IntelFeedCopy {
    const val HEADER = "INTEL"
    const val CHANNEL_TITLE = "intel-feed"
    const val EMPTY = "No intel right now."
    const val ERROR = "Intel could not be loaded."
    const val RETRY = "Retry"
    const val VIEW_ALL = "View all intel"
    const val ACTIVITY_HEADER = "ACTIVITY"
    const val ACTIVITY_EMPTY = "No activity right now."
    const val ADD_ON_TITLE = "Autopilot add-on"
    const val ADD_ON_STATE = "Not yet available"
    const val ADD_ON_BODY = "This add-on is not yet available. There is nothing to turn on."
    const val CONSOLE_MAX_ITEMS = 20
    const val SKELETON_ROWS = 4
}

/** The single intel renderer, used by both hosts below. */
@Composable
fun IntelItemCard(item: IntelItemUi, modifier: Modifier = Modifier) {
    val space = OreTheme.space
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
            OreText(
                text = item.title,
                style = OreTheme.type.bodyStrong,
                color = OreTheme.color.text.primary,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            if (item.severity.isNotEmpty()) {
                OreText(
                    text = item.severity,
                    style = OreTheme.type.badge,
                    color = OreTheme.color.text.secondary,
                    modifier = Modifier
                        .background(OreTheme.color.surface.highlight)
                        .padding(horizontal = space.sm, vertical = space.xxs),
                    maxLines = 1,
                )
            }
        }
        OreText(text = item.body, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
        OreText(text = item.timestamp, style = OreTheme.type.caption, color = OreTheme.color.text.tertiary, maxLines = 1)
    }
}

/**
 * Console host: a non-lazy section for the console's `intel` slot (it sits inside the console's
 * own lazy list). Shows the first [maxItems] items in supplied order; [onViewAll] opens the
 * channel surface when more exist.
 */
@Composable
fun IntelConsoleSection(
    state: IntelFeedUi,
    modifier: Modifier = Modifier,
    maxItems: Int = IntelFeedCopy.CONSOLE_MAX_ITEMS,
    onViewAll: (() -> Unit)? = null,
    onRetry: () -> Unit = {},
) {
    val space = OreTheme.space
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
            OreText(
                text = IntelFeedCopy.HEADER,
                style = OreTheme.type.overline,
                color = OreTheme.color.text.tertiary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (state.stale) StaleBadge()
        }
        when {
            state.status == NightshiftStatus.Loading && state.items.isEmpty() -> IntelSkeleton()
            state.status == NightshiftStatus.Error && state.items.isEmpty() ->
                IntelNotice(text = IntelFeedCopy.ERROR, onRetry = onRetry)
            state.items.isEmpty() -> IntelNotice(text = IntelFeedCopy.EMPTY)
            else -> {
                state.items.take(maxItems).forEach { IntelItemCard(it) }
                if (state.items.size > maxItems && onViewAll != null) {
                    OreGhostButton(text = IntelFeedCopy.VIEW_ALL, onClick = onViewAll)
                }
            }
        }
    }
}

/**
 * #intel-feed channel host: full-height virtualised list of the same cards, in supplied order.
 * [composer] is a slot: the host supplies the channel composer keyed off the entitlement
 * capability set, or nothing.
 */
@Composable
fun IntelFeedChannelSurface(
    state: IntelFeedUi,
    modifier: Modifier = Modifier,
    title: String = IntelFeedCopy.CHANNEL_TITLE,
    onRetry: () -> Unit = {},
    composer: @Composable () -> Unit = {},
) {
    val space = OreTheme.space
    Column(modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OreTheme.color.surface.raised)
                .padding(horizontal = space.md, vertical = space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            OreText(
                text = title,
                style = OreTheme.type.titleSmall,
                color = OreTheme.color.text.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (state.stale) StaleBadge()
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.status == NightshiftStatus.Loading && state.items.isEmpty() ->
                    Box(Modifier.padding(space.md)) { IntelSkeleton() }
                state.status == NightshiftStatus.Error && state.items.isEmpty() ->
                    Box(Modifier.padding(space.md)) { IntelNotice(text = IntelFeedCopy.ERROR, onRetry = onRetry) }
                state.items.isEmpty() -> Box(Modifier.padding(space.md)) { IntelNotice(text = IntelFeedCopy.EMPTY) }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(space.md),
                    verticalArrangement = Arrangement.spacedBy(space.sm),
                ) {
                    itemsIndexed(state.items, key = { index, _ -> "intel_$index" }) { _, item -> IntelItemCard(item) }
                }
            }
        }
        composer()
    }
}

/** Presence activity strings, verbatim, in supplied order. Never invented, never rewritten. */
@Composable
fun NightshiftActivitySection(
    activities: List<NightshiftActivityUi>,
    modifier: Modifier = Modifier,
) {
    val space = OreTheme.space
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.xs),
    ) {
        OreText(
            text = IntelFeedCopy.ACTIVITY_HEADER,
            style = OreTheme.type.overline,
            color = OreTheme.color.text.tertiary,
            maxLines = 1,
        )
        if (activities.isEmpty()) {
            OreText(text = IntelFeedCopy.ACTIVITY_EMPTY, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
        } else {
            activities.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
                    OreText(
                        text = entry.displayName,
                        style = OreTheme.type.body,
                        color = OreTheme.color.text.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    OreText(
                        text = entry.activity,
                        style = OreTheme.type.caption,
                        color = OreTheme.color.text.secondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Add-on surface. Composes NOTHING unless [autopilotSurface] (Capabilities.autopilotSurface from
 * the entitlement read) is true. When true it is a disabled, informational "not yet available"
 * card: no control, no callback, no execution UI.
 */
@Composable
fun NightshiftAddOnSurface(
    autopilotSurface: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!autopilotSurface) return
    val space = OreTheme.space
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.sunken)
            .padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
            OreText(
                text = IntelFeedCopy.ADD_ON_TITLE,
                style = OreTheme.type.bodyStrong,
                color = OreTheme.color.text.disabled,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            OreText(
                text = IntelFeedCopy.ADD_ON_STATE,
                style = OreTheme.type.badge,
                color = OreTheme.color.text.secondary,
                modifier = Modifier
                    .background(OreTheme.color.surface.highlight)
                    .padding(horizontal = space.sm, vertical = space.xxs),
                maxLines = 1,
            )
        }
        OreText(text = IntelFeedCopy.ADD_ON_BODY, style = OreTheme.type.caption, color = OreTheme.color.text.disabled)
    }
}

@Composable
private fun IntelNotice(text: String, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(OreTheme.space.md),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
    ) {
        OreText(text = text, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
        if (onRetry != null) OreGhostButton(text = IntelFeedCopy.RETRY, onClick = onRetry)
    }
}

@Composable
private fun IntelSkeleton() {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(OreTheme.space.sm)) {
        repeat(IntelFeedCopy.SKELETON_ROWS) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(OreTheme.size.touchTarget * 2)
                    .background(OreTheme.color.surface.highlight),
            )
        }
    }
}
