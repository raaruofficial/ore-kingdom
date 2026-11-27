package com.raaru.orekingdom.states

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.channel.ChannelOutbox
import com.raaru.orekingdom.designsystem.components.Composer
import com.raaru.orekingdom.designsystem.components.ComposerState
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme

internal object ChannelMissingStatesCopy {
    const val OFFLINE = "You're offline. Showing saved messages."
    const val CATCHING_UP = "Catching up…"
    const val PAGE_ERROR = "Older messages could not be loaded."
    const val RETRY = "Retry"
    const val UPSELL_NOTE = "Reading is free forever. A pass unlocks posting."
    const val UPSELL_ACTION = "View passes"
    const val LAPSED = "Your pass has ended, so this message was not sent. You can still read everything public."
    const val READ_ONLY_FOOTER = "This channel is read-only."
    const val UNAVAILABLE_TITLE = "Not available"
    const val UNAVAILABLE_BODY = "There is nothing to show here."
    const val BACK = "Go back"
    const val THREADS_EMPTY_TITLE = "No threads yet"
    const val THREADS_EMPTY_BODY = "Threads keep long conversations out of the channel. Open one from any message."
}

/**
 * Channel audit: list skeleton / empty / error, per-row sending + queued states and the outbox-full
 * note are already delivered. Missing surfaces follow.
 */

/** Stale-offline banner for cached history, with the queued-not-sent count when the outbox holds entries. */
@Composable
fun ChannelOfflineBanner(queuedCount: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        StateBanner(text = ChannelMissingStatesCopy.OFFLINE, stale = true)
        if (queuedCount > 0) {
            OreText(
                text = "$queuedCount · ${ChannelOutbox.QUEUED_LABEL}",
                style = OreTheme.type.caption,
                color = OreTheme.color.message.queued.content,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OreTheme.color.message.queued.container)
                    .padding(horizontal = OreTheme.space.md, vertical = OreTheme.space.xxs),
                maxLines = 1,
            )
        }
    }
}

/** Shown while a channel is in catch-up mode (resume replay / resync refetch). */
@Composable
fun ChannelCatchUpNotice(modifier: Modifier = Modifier) {
    StateBanner(text = ChannelMissingStatesCopy.CATCHING_UP, modifier = modifier)
}

/** List-head row while an older history page loads. */
@Composable
fun ChannelHistoryPageLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = OreTheme.space.sm),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) {
        StateBar(0.6f)
        StateBar(0.4f)
    }
}

/** List-head row when an older history page failed. Fixed copy, retry with the same cursor. */
@Composable
fun ChannelHistoryPageError(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = OreTheme.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
    ) {
        OreText(
            text = ChannelMissingStatesCopy.PAGE_ERROR,
            style = OreTheme.type.caption,
            color = OreTheme.color.text.secondary,
            modifier = Modifier.weight(1f),
        )
        OreGhostButton(text = ChannelMissingStatesCopy.RETRY, onClick = onRetry)
    }
}

/**
 * The F-IA-1 shape: disabled composer with the pass upsell, for read-only callers in a channel or
 * thread. [onUpsellClick] is navigation to the pass screen only.
 */
@Composable
fun ReadOnlyUpsellComposer(onUpsellClick: () -> Unit, modifier: Modifier = Modifier) {
    Composer(
        value = "",
        onValueChange = {},
        onSend = {},
        state = ComposerState.Upsell(ChannelMissingStatesCopy.UPSELL_NOTE, ChannelMissingStatesCopy.UPSELL_ACTION),
        modifier = modifier.fillMaxWidth(),
        onUpsellClick = onUpsellClick,
    )
}

/**
 * F-RP-2 shape: a send refused with `not_entitled` after lapse renders as this notice above the
 * upsell composer — never a crash, a silent drop, or the raw error.
 */
@Composable
fun ChannelLapsedSendNotice(onUpsellClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        StateBanner(text = ChannelMissingStatesCopy.LAPSED)
        ReadOnlyUpsellComposer(onUpsellClick = onUpsellClick)
    }
}

/** Footer for channels with no composer for the caller's role class (house / system / commercial-only). */
@Composable
fun ChannelReadOnlyFooter(modifier: Modifier = Modifier) {
    OreText(
        text = ChannelMissingStatesCopy.READ_ONLY_FOOTER,
        style = OreTheme.type.caption,
        color = OreTheme.color.text.tertiary,
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(horizontal = OreTheme.space.md, vertical = OreTheme.space.sm),
        maxLines = 1,
    )
}

/**
 * Direct link to a channel or thread the caller has no entitlement for (F-IA-2), or one that no
 * longer exists. Deliberately identical for both cases: no name, count or metadata is shown.
 */
@Composable
fun ChannelUnavailable(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    StateMessage(
        title = ChannelMissingStatesCopy.UNAVAILABLE_TITLE,
        body = ChannelMissingStatesCopy.UNAVAILABLE_BODY,
        modifier = modifier,
        actionLabel = if (onBack != null) ChannelMissingStatesCopy.BACK else null,
        onAction = onBack,
    )
}

/** Fuller empty state for the thread list (the inline one is a single line). */
@Composable
fun ThreadListEmpty(modifier: Modifier = Modifier) {
    StateMessage(
        title = ChannelMissingStatesCopy.THREADS_EMPTY_TITLE,
        body = ChannelMissingStatesCopy.THREADS_EMPTY_BODY,
        modifier = modifier,
    )
}
