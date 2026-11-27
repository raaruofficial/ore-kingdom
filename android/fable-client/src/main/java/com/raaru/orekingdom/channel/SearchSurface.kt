package com.raaru.orekingdom.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import com.raaru.orekingdom.designsystem.components.CategoryHeader
import com.raaru.orekingdom.designsystem.components.MessageBubble
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OreMessageDelivery
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.components.OreTombstoneKind
import com.raaru.orekingdom.designsystem.components.TombstoneRow
import com.raaru.orekingdom.designsystem.tokens.OreTheme

enum class SearchStatus { Idle, Loading, Ready, Error }

/**
 * One message hit from the contract search read (`messages: [Message]`).
 * [channelName] is resolved by the host from the caller's own channel list; null renders no channel line.
 */
data class SearchMessageHitUi(
    val channelId: String,
    val message: ChannelMessageUi,
    val channelName: String? = null,
)

/** One thread hit from the contract search read (`threads: [Thread]`). [lastActivity] is display-ready. */
data class SearchThreadHitUi(
    val id: String,
    val channelId: String,
    val title: String,
    val archived: Boolean = false,
    val replyCount: Int = 0,
    val lastActivity: String? = null,
    val channelName: String? = null,
)

/**
 * Search UI state. [messages] and [threads] hold exactly what the search read returned.
 * [readableChannelIds] is the caller's current channel list (ids present in the rail); any hit
 * outside it is dropped at render with no trace, so a cached result can never outlive a lapse.
 */
data class SearchUiState(
    val query: String = "",
    val status: SearchStatus = SearchStatus.Idle,
    val messages: List<SearchMessageHitUi> = emptyList(),
    val threads: List<SearchThreadHitUi> = emptyList(),
    val readableChannelIds: Set<String> = emptySet(),
)

/**
 * The render-visible hits of a search state (Q-15): hits outside the
 * readable set dropped, duplicate ids first-wins, order preserved.
 */
internal fun visibleSearchHits(state: SearchUiState): Pair<List<SearchThreadHitUi>, List<SearchMessageHitUi>> {
    val threads = state.threads.filter { it.channelId in state.readableChannelIds }.distinctBy { it.id }
    val messages = state.messages.filter { it.channelId in state.readableChannelIds }.distinctBy { it.message.id }
    return threads to messages
}

private object SearchSurfaceCopy {
    const val TITLE = "Search"
    const val PLACEHOLDER = "Search messages and threads"
    const val CLEAR = "Clear"
    const val IDLE = "Search the messages and thread titles you can read."
    const val EMPTY = "No results."
    const val ERROR = "Search could not be completed."
    const val RETRY = "Retry"
    const val SECTION_THREADS = "Threads"
    const val SECTION_MESSAGES = "Messages"
    const val ARCHIVED = "Archived"
    const val REPLIES_ONE = "reply"
    const val REPLIES_MANY = "replies"
    const val SKELETON_ROWS = 6
}

/**
 * Search surface over the contract search read. Renders only returned, still-readable hits:
 * no totals, no "hidden results" lines, no placeholders for anything outside the caller's entitlements.
 */
@Composable
fun SearchSurface(
    state: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onMessageOpen: (channelId: String, messageId: String) -> Unit,
    onThreadOpen: (channelId: String, threadId: String) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = onSubmit,
) {
    val space = OreTheme.space
    val (threads, messages) = remember(state.threads, state.messages, state.readableChannelIds) {
        visibleSearchHits(state)
    }
    Column(modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(OreTheme.color.surface.raised)
                .padding(horizontal = space.md, vertical = space.sm),
            verticalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            OreText(
                text = SearchSurfaceCopy.TITLE,
                style = OreTheme.type.titleSmall,
                color = OreTheme.color.text.primary,
                maxLines = 1,
            )
            SearchQueryField(query = state.query, onQueryChange = onQueryChange, onSubmit = onSubmit)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.status == SearchStatus.Loading -> SearchSkeleton()
                state.status == SearchStatus.Error -> SearchNotice(SearchSurfaceCopy.ERROR, onRetry)
                state.status == SearchStatus.Idle -> SearchNotice(SearchSurfaceCopy.IDLE)
                threads.isEmpty() && messages.isEmpty() -> SearchNotice(SearchSurfaceCopy.EMPTY)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = space.md, vertical = space.sm),
                    verticalArrangement = Arrangement.spacedBy(space.sm),
                ) {
                    if (threads.isNotEmpty()) {
                        item(key = "section_threads") { CategoryHeader(title = SearchSurfaceCopy.SECTION_THREADS) }
                        items(threads, key = { "t_${it.id}" }) { thread ->
                            SearchThreadRow(thread = thread, onClick = { onThreadOpen(thread.channelId, thread.id) })
                        }
                    }
                    if (messages.isNotEmpty()) {
                        item(key = "section_messages") { CategoryHeader(title = SearchSurfaceCopy.SECTION_MESSAGES) }
                        items(messages, key = { "m_${it.message.id}" }) { hit ->
                            SearchMessageRow(hit = hit, onClick = { onMessageOpen(hit.channelId, hit.message.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchQueryField(query: String, onQueryChange: (String) -> Unit, onSubmit: () -> Unit) {
    val space = OreTheme.space
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OreTheme.size.composerMinHeight)
            .background(OreTheme.color.surface.sunken)
            .border(OreTheme.size.strokeHairline, OreTheme.color.border.subtle)
            .padding(horizontal = space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.sm),
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                OreText(
                    text = SearchSurfaceCopy.PLACEHOLDER,
                    style = OreTheme.type.body,
                    color = OreTheme.color.text.tertiary,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = OreTheme.type.body.copy(color = OreTheme.color.text.primary),
                cursorBrush = SolidColor(OreTheme.color.accent.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) onSubmit() }),
            )
        }
        if (query.isNotEmpty()) {
            OreGhostButton(text = SearchSurfaceCopy.CLEAR, onClick = { onQueryChange("") })
        }
    }
}

@Composable
private fun SearchThreadRow(thread: SearchThreadHitUi, onClick: () -> Unit) {
    val space = OreTheme.space
    val replies = "${thread.replyCount} " +
        if (thread.replyCount == 1) SearchSurfaceCopy.REPLIES_ONE else SearchSurfaceCopy.REPLIES_MANY
    val meta = listOfNotNull(
        thread.channelName?.let { "#$it" },
        replies,
        thread.lastActivity,
        SearchSurfaceCopy.ARCHIVED.takeIf { thread.archived },
    ).joinToString(" · ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OreTheme.size.touchTarget)
            .background(OreTheme.color.surface.raised)
            .clickable(onClick = onClick)
            .padding(horizontal = space.md, vertical = space.sm),
        verticalArrangement = Arrangement.spacedBy(space.xxs),
    ) {
        OreText(
            text = thread.title,
            style = OreTheme.type.bodyStrong,
            color = OreTheme.color.text.primary,
            maxLines = 2,
        )
        OreText(text = meta, style = OreTheme.type.caption, color = OreTheme.color.text.tertiary, maxLines = 1)
    }
}

/** Message hit: same UI message shape as the channel list; reactions render read-only here. */
@Composable
private fun SearchMessageRow(hit: SearchMessageHitUi, onClick: () -> Unit) {
    val message = hit.message
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xxs),
    ) {
        if (hit.channelName != null) {
            OreText(
                text = "#${hit.channelName}",
                style = OreTheme.type.caption,
                color = OreTheme.color.text.tertiary,
                maxLines = 1,
            )
        }
        if (message.deleted) {
            TombstoneRow(kind = OreTombstoneKind.Deleted)
        } else {
            MessageBubble(
                authorName = message.authorName,
                authorRole = message.authorRole,
                body = message.body,
                timestamp = message.timestamp,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
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
                reactionsEnabled = false,
                onReactionToggle = {},
            )
        }
    }
}

@Composable
private fun SearchNotice(text: String, onRetry: (() -> Unit)? = null) {
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
            if (onRetry != null) OreGhostButton(text = SearchSurfaceCopy.RETRY, onClick = onRetry)
        }
    }
}

@Composable
private fun SearchSkeleton() {
    val space = OreTheme.space
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = space.md, vertical = space.sm),
        verticalArrangement = Arrangement.spacedBy(space.md),
    ) {
        repeat(SearchSurfaceCopy.SKELETON_ROWS) { index ->
            Column(verticalArrangement = Arrangement.spacedBy(space.xs)) {
                Box(Modifier.fillMaxWidth(0.35f).height(space.md).background(OreTheme.color.surface.highlight))
                Box(
                    Modifier
                        .fillMaxWidth(if (index % 2 == 0) 0.85f else 0.6f)
                        .height(space.md)
                        .background(OreTheme.color.surface.highlight),
                )
            }
        }
    }
}
