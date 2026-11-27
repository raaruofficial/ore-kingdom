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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.Composer
import com.raaru.orekingdom.designsystem.components.ComposerState
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme

/** One thread-list row. Mirrors the contract Thread shape plus resolved anchor-author display. */
data class ThreadSummaryUi(
    val id: String,
    val title: String,
    val anchorAuthorName: String,
    val replyCount: Int,
    val lastActivity: String,
    val archived: Boolean = false,
)

data class ThreadListUiState(
    val status: ChannelStatus = ChannelStatus.Loading,
    val threads: List<ThreadSummaryUi> = emptyList(),
)

/**
 * Thread view state. [anchor] is the parent message; [replies] are the thread's messages.
 * [canUnarchive] is supplied by the host (thread author or moderator); nothing is inferred here.
 */
data class ThreadUiState(
    val threadId: String,
    val title: String,
    val status: ChannelStatus = ChannelStatus.Loading,
    val archived: Boolean = false,
    val canUnarchive: Boolean = false,
    val anchor: ChannelMessageUi? = null,
    val replies: List<ChannelMessageUi> = emptyList(),
    val stale: Boolean = false,
    val typists: List<String> = emptyList(),
    val reactionsEnabled: Boolean = false,
    val hasOlder: Boolean = false,
)

internal object ThreadCopy {
    const val LIST_EMPTY = "No threads yet."
    const val LIST_ERROR = "Threads could not be loaded."
    const val VIEW_ERROR = "This thread could not be loaded."
    const val RETRY = "Retry"
    const val ARCHIVED_TAG = "Archived"
    const val ARCHIVED_BANNER = "Archived · read-only"
    const val ARCHIVED_NOTE = "This thread is archived and read-only."
    const val UNARCHIVE = "Unarchive"
    const val REPLY_ONE = "reply"
    const val REPLY_MANY = "replies"
    const val SEPARATOR = " · "
    const val SKELETON_ROWS = 6
}

/** Thread list: title, anchor author, reply count, last-activity time. Order is as supplied. */
@Composable
fun ThreadList(
    state: ThreadListUiState,
    onThreadSelected: (threadId: String) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    val space = OreTheme.space
    Box(modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base)) {
        when {
            state.status == ChannelStatus.Loading && state.threads.isEmpty() -> Column(
                modifier = Modifier.fillMaxSize().padding(space.md),
                verticalArrangement = Arrangement.spacedBy(space.sm),
            ) {
                repeat(ThreadCopy.SKELETON_ROWS) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(OreTheme.size.memberRowHeight)
                            .background(OreTheme.color.surface.highlight),
                    )
                }
            }
            state.status == ChannelStatus.Error && state.threads.isEmpty() ->
                ThreadNotice(text = ThreadCopy.LIST_ERROR, onRetry = onRetry)
            state.threads.isEmpty() -> ThreadNotice(text = ThreadCopy.LIST_EMPTY)
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = space.sm),
            ) {
                items(state.threads, key = { it.id }) { thread ->
                    ThreadListRow(thread = thread, onClick = { onThreadSelected(thread.id) })
                }
            }
        }
    }
}

@Composable
private fun ThreadListRow(thread: ThreadSummaryUi, onClick: () -> Unit) {
    val space = OreTheme.space
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = space.md, vertical = space.sm),
        verticalArrangement = Arrangement.spacedBy(space.xxs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            OreText(
                text = thread.title,
                style = OreTheme.type.bodyStrong,
                color = OreTheme.color.text.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (thread.archived) {
                OreText(
                    text = ThreadCopy.ARCHIVED_TAG,
                    style = OreTheme.type.overline,
                    color = OreTheme.color.text.tertiary,
                    maxLines = 1,
                )
            }
        }
        val replies = if (thread.replyCount == 1) ThreadCopy.REPLY_ONE else ThreadCopy.REPLY_MANY
        OreText(
            text = listOf(thread.anchorAuthorName, "${thread.replyCount} $replies", thread.lastActivity)
                .joinToString(ThreadCopy.SEPARATOR),
            style = OreTheme.type.caption,
            color = OreTheme.color.text.secondary,
            maxLines = 1,
        )
    }
}

/**
 * Thread view. Permissions are inherited: [composerState] is the PARENT CHANNEL's composer state.
 * Archived threads are read-only for everyone. Read-only callers (Upsell / Disabled) get the F1
 * disabled composer rendered here, so the content plus upsell shape always shows (F-IA-1).
 * [activeComposer] is invoked only for Enabled / Slowmode (host supplies ChannelComposer with
 * this thread's id). The unarchive control renders only when archived AND [ThreadUiState.canUnarchive].
 */
@Composable
fun ThreadView(
    state: ThreadUiState,
    composerState: ComposerState,
    onReactionToggle: (messageId: String, emoji: String) -> Unit,
    onMediaOpen: (messageId: String, mediaId: String) -> Unit,
    activeComposer: @Composable (ComposerState) -> Unit,
    modifier: Modifier = Modifier,
    onUnarchive: () -> Unit = {},
    onUpsellClick: () -> Unit = {},
    onMessageSelected: (messageId: String) -> Unit = {},
    onLoadOlder: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val space = OreTheme.space
    val messages = remember(state.anchor, state.replies) { listOfNotNull(state.anchor) + state.replies }
    val effective = if (state.archived) ComposerState.Disabled(ThreadCopy.ARCHIVED_NOTE) else composerState

    Column(modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base)) {
        if (state.archived) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OreTheme.color.surface.sunken)
                    .padding(horizontal = space.md, vertical = space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(space.sm),
            ) {
                OreText(
                    text = ThreadCopy.ARCHIVED_BANNER,
                    style = OreTheme.type.label,
                    color = OreTheme.color.text.secondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (state.canUnarchive) OreGhostButton(text = ThreadCopy.UNARCHIVE, onClick = onUnarchive)
            }
        }
        if (state.status == ChannelStatus.Error && messages.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                ThreadNotice(text = ThreadCopy.VIEW_ERROR, onRetry = onRetry)
            }
        } else {
            ChannelScreen(
                state = ChannelUiState(
                    channelName = state.title,
                    status = state.status,
                    messages = messages,
                    stale = state.stale,
                    typists = if (state.archived) emptyList() else state.typists,
                    reactionsEnabled = state.reactionsEnabled && !state.archived,
                    hasOlder = state.hasOlder,
                ),
                onReactionToggle = onReactionToggle,
                onMediaOpen = onMediaOpen,
                modifier = Modifier.weight(1f),
                onMessageSelected = onMessageSelected,
                onLoadOlder = onLoadOlder,
                onRetry = onRetry,
                composer = {
                    when (effective) {
                        is ComposerState.Enabled, is ComposerState.Slowmode -> activeComposer(effective)
                        else -> Composer(
                            value = "",
                            onValueChange = {},
                            onSend = {},
                            state = effective,
                            modifier = Modifier.fillMaxWidth(),
                            onUpsellClick = onUpsellClick,
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun ThreadNotice(text: String, onRetry: (() -> Unit)? = null) {
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
            if (onRetry != null) OreGhostButton(text = ThreadCopy.RETRY, onClick = onRetry)
        }
    }
}
