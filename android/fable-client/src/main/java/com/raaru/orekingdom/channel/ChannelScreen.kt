package com.raaru.orekingdom.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.MessageBubble
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OreMessageDelivery
import com.raaru.orekingdom.designsystem.components.OreReaction
import com.raaru.orekingdom.designsystem.components.OreRole
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.components.OreTombstoneKind
import com.raaru.orekingdom.designsystem.components.QueuedMessage
import com.raaru.orekingdom.designsystem.components.SendingMessage
import com.raaru.orekingdom.designsystem.components.StaleBadge
import com.raaru.orekingdom.designsystem.components.TombstoneRow
import com.raaru.orekingdom.designsystem.tokens.OreTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

enum class ChannelStatus { Loading, Ready, Error }

/** Thumbnail-only media reference. The list never loads full media; tap hands off to the host. */
data class ChannelMediaUi(val id: String, val label: String? = null)

/**
 * UI message row. Mirrors the contract Message shape plus resolved author display.
 * [seq] is null only for own unacked rows (sending / queued), which have no server sequence yet;
 * [localKey] is the idempotency key the row was composed with and is its stable identity until ack.
 */
data class ChannelMessageUi(
    val id: String,
    val seq: Long?,
    val authorName: String,
    val authorRole: OreRole,
    val body: String,
    val timestamp: String,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    val commercial: Boolean = false,
    val stale: Boolean = false,
    val delivery: OreMessageDelivery = OreMessageDelivery.Delivered,
    val reactions: List<OreReaction> = emptyList(),
    val own: Boolean = false,
    val founding: Boolean = false,
    val moderator: Boolean = false,
    val crown: Boolean = false,
    val media: List<ChannelMediaUi> = emptyList(),
    val localKey: String? = null,
) {
    val rowKey: String get() = localKey ?: id
}

data class ChannelUiState(
    val channelName: String,
    val status: ChannelStatus = ChannelStatus.Loading,
    val messages: List<ChannelMessageUi> = emptyList(),
    val stale: Boolean = false,
    val typists: List<String> = emptyList(),
    val reactionsEnabled: Boolean = false,
    val hasOlder: Boolean = false,
)

internal object ChannelScreenCopy {
    const val EMPTY = "No messages here yet."
    const val ERROR = "This channel could not be loaded."
    const val RETRY = "Retry"
    const val MEDIA_HINT = "Tap to view"
    const val TYPING_ONE = "is typing…"
    const val TYPING_TWO = "are typing…"
    const val TYPING_MANY = "Several people are typing…"
    const val SKELETON_ROWS = 8
    const val LOAD_OLDER_THRESHOLD = 5
    const val FOLLOW_TAIL_WINDOW = 3
}

/**
 * Channel view. Acked rows render strictly in seq order; own unacked rows follow in compose order.
 * Rows are keyed, so prepending older pages does not move the scroll position.
 * [composer] is a slot: the caller supplies the composer (or nothing, for composer-less channels).
 */
@Composable
fun ChannelScreen(
    state: ChannelUiState,
    onReactionToggle: (messageId: String, emoji: String) -> Unit,
    onMediaOpen: (messageId: String, mediaId: String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onMessageSelected: (messageId: String) -> Unit = {},
    onLoadOlder: () -> Unit = {},
    onRetry: () -> Unit = {},
    composer: @Composable () -> Unit = {},
) {
    val space = OreTheme.space
    val ordered = remember(state.messages) {
        val (acked, pending) = state.messages.partition { it.seq != null }
        (acked.sortedBy { it.seq } + pending).distinctBy { it.rowKey }
    }
    val loadOlder by rememberUpdatedState(onLoadOlder)
    val hasOlder by rememberUpdatedState(state.hasOlder)

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex <= ChannelScreenCopy.LOAD_OLDER_THRESHOLD }
            .distinctUntilChanged()
            .filter { it && hasOlder }
            .collect { loadOlder() }
    }
    val tailKey = ordered.lastOrNull()?.rowKey
    LaunchedEffect(tailKey) {
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (tailKey != null && lastVisible >= info.totalItemsCount - 1 - ChannelScreenCopy.FOLLOW_TAIL_WINDOW) {
            listState.animateScrollToItem(info.totalItemsCount - 1)
        }
    }

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
                text = state.channelName,
                style = OreTheme.type.titleSmall,
                color = OreTheme.color.text.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (state.stale) StaleBadge()
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.status == ChannelStatus.Loading && ordered.isEmpty() -> ChannelSkeleton()
                state.status == ChannelStatus.Error && ordered.isEmpty() ->
                    ChannelNotice(text = ChannelScreenCopy.ERROR, onRetry = onRetry)
                ordered.isEmpty() && state.typists.isEmpty() -> ChannelNotice(text = ChannelScreenCopy.EMPTY)
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = space.md, vertical = space.sm),
                    verticalArrangement = Arrangement.spacedBy(space.sm),
                ) {
                    items(ordered, key = { it.rowKey }) { message ->
                        ChannelMessageRow(
                            message = message,
                            reactionsEnabled = state.reactionsEnabled,
                            onReactionToggle = onReactionToggle,
                            onMediaOpen = onMediaOpen,
                            onMessageSelected = onMessageSelected,
                        )
                    }
                    if (state.typists.isNotEmpty()) {
                        item(key = "typing_indicator") { TypingIndicatorRow(typists = state.typists) }
                    }
                }
            }
        }
        composer()
    }
}

@Composable
private fun ChannelMessageRow(
    message: ChannelMessageUi,
    reactionsEnabled: Boolean,
    onReactionToggle: (String, String) -> Unit,
    onMediaOpen: (String, String) -> Unit,
    onMessageSelected: (String) -> Unit,
) {
    val pending = message.own && message.delivery != OreMessageDelivery.Delivered
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) {
        when {
            message.deleted -> TombstoneRow(kind = OreTombstoneKind.Deleted)
            pending && message.delivery == OreMessageDelivery.Queued -> QueuedMessage(
                authorName = message.authorName,
                authorRole = message.authorRole,
                body = message.body,
                timestamp = message.timestamp,
                modifier = Modifier.fillMaxWidth(),
            )
            pending -> SendingMessage(
                authorName = message.authorName,
                authorRole = message.authorRole,
                body = message.body,
                timestamp = message.timestamp,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> MessageBubble(
                authorName = message.authorName,
                authorRole = message.authorRole,
                body = message.body,
                timestamp = message.timestamp,
                modifier = Modifier.fillMaxWidth().clickable { onMessageSelected(message.id) },
                own = message.own,
                delivery = OreMessageDelivery.Delivered,
                edited = message.edited,
                deleted = false,
                commercial = message.commercial,
                stale = message.stale,
                founding = message.founding,
                moderator = message.moderator,
                crown = message.crown,
                reactions = message.reactions,
                reactionsEnabled = reactionsEnabled,
                onReactionToggle = { emoji -> onReactionToggle(message.id, emoji) },
            )
        }
        if (!message.deleted && message.media.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(OreTheme.space.xs)) {
                message.media.forEach { media ->
                    MediaThumbnail(media = media, onClick = { onMediaOpen(message.id, media.id) })
                }
            }
        }
    }
}

/** Thumbnail placeholder block. Full media is never loaded in the list; tap delegates to the host. */
@Composable
private fun MediaThumbnail(media: ChannelMediaUi, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(OreTheme.size.touchTarget * 2)
            .background(OreTheme.color.surface.sunken)
            .clickable(onClick = onClick)
            .padding(OreTheme.space.xs),
        contentAlignment = Alignment.Center,
    ) {
        OreText(
            text = media.label ?: ChannelScreenCopy.MEDIA_HINT,
            style = OreTheme.type.caption,
            color = OreTheme.color.text.tertiary,
            maxLines = 2,
        )
    }
}

/** Transient typing row at the list foot. Never a message, never keyed into history. */
@Composable
private fun TypingIndicatorRow(typists: List<String>) {
    val text = when (typists.size) {
        1 -> "${typists[0]} ${ChannelScreenCopy.TYPING_ONE}"
        2 -> "${typists[0]}, ${typists[1]} ${ChannelScreenCopy.TYPING_TWO}"
        else -> ChannelScreenCopy.TYPING_MANY
    }
    OreText(
        text = text,
        style = OreTheme.type.caption,
        color = OreTheme.color.text.tertiary,
        modifier = Modifier.fillMaxWidth().padding(vertical = OreTheme.space.xxs),
        maxLines = 1,
    )
}

@Composable
private fun ChannelNotice(text: String, onRetry: (() -> Unit)? = null) {
    val space = OreTheme.space
    Box(modifier = Modifier.fillMaxSize().padding(space.lg), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(OreTheme.color.surface.raised)
                .padding(space.md),
            verticalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            OreText(text = text, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
            if (onRetry != null) OreGhostButton(text = ChannelScreenCopy.RETRY, onClick = onRetry)
        }
    }
}

@Composable
private fun ChannelSkeleton() {
    val space = OreTheme.space
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = space.md, vertical = space.sm),
        verticalArrangement = Arrangement.spacedBy(space.md),
    ) {
        repeat(ChannelScreenCopy.SKELETON_ROWS) { index ->
            Row(horizontalArrangement = Arrangement.spacedBy(space.sm)) {
                Box(Modifier.size(OreTheme.size.avatarSmall).background(OreTheme.color.surface.highlight))
                Column(verticalArrangement = Arrangement.spacedBy(space.xs)) {
                    Box(Modifier.fillMaxWidth(0.3f).height(space.md).background(OreTheme.color.surface.highlight))
                    Box(
                        Modifier
                            .fillMaxWidth(if (index % 2 == 0) 0.8f else 0.55f)
                            .height(space.md)
                            .background(OreTheme.color.surface.highlight),
                    )
                }
            }
        }
    }
}
