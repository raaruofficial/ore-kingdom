package com.raaru.orekingdom.home

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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.MemberRow
import com.raaru.orekingdom.designsystem.components.MessageBubble
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OreMessageDelivery
import com.raaru.orekingdom.designsystem.components.OrePresence
import com.raaru.orekingdom.designsystem.components.OreReaction
import com.raaru.orekingdom.designsystem.components.OreRole
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.components.OreTombstoneKind
import com.raaru.orekingdom.designsystem.components.PresenceDot
import com.raaru.orekingdom.designsystem.components.PresenceRow
import com.raaru.orekingdom.designsystem.components.PresenceSectionHeader
import com.raaru.orekingdom.designsystem.components.SponsoredLabel
import com.raaru.orekingdom.designsystem.components.StaleBadge
import com.raaru.orekingdom.designsystem.components.TombstoneRow
import com.raaru.orekingdom.designsystem.components.presenceLabel
import com.raaru.orekingdom.designsystem.tokens.OreTheme

/** Which read-only home feed is rendered. Neither has a composer for any role. */
enum class HomeFeedKind { Announcements, RoundBoard }

enum class HomeSurfaceStatus { Loading, Ready, Error }

/** One rendered feed row; fields mirror the contract Message shape plus resolved author display. */
data class HomeFeedItem(
    val id: String,
    val seq: Int,
    val authorName: String,
    val authorRole: OreRole,
    val body: String,
    val timestamp: String,
    val edited: Boolean = false,
    val deleted: Boolean = false,
    val commercial: Boolean = false,
    val stale: Boolean = false,
    val sponsored: Boolean = false,
    val founding: Boolean = false,
    val moderator: Boolean = false,
    val crown: Boolean = false,
    val reactions: List<OreReaction> = emptyList(),
)

data class HomeFeedState(
    val kind: HomeFeedKind,
    val status: HomeSurfaceStatus = HomeSurfaceStatus.Loading,
    val items: List<HomeFeedItem> = emptyList(),
    val stale: Boolean = false,
)

/** One member in the presence summary; [activity] is the contract string, rendered verbatim. */
data class HomePresenceMember(
    val userId: String,
    val displayName: String,
    val role: OreRole,
    val presence: OrePresence,
    val activity: String? = null,
    val founding: Boolean = false,
    val moderator: Boolean = false,
    val crown: Boolean = false,
)

data class HomePresenceState(
    val status: HomeSurfaceStatus = HomeSurfaceStatus.Loading,
    val members: List<HomePresenceMember> = emptyList(),
)

internal object ServerHomeFeedCopy {
    const val ANNOUNCEMENTS_TITLE = "announcements"
    const val ROUND_BOARD_TITLE = "round-board"
    const val READ_ONLY = "Read-only"
    const val ANNOUNCEMENTS_EMPTY = "No announcements yet."
    const val ROUND_BOARD_EMPTY = "No round notices yet."
    const val FEED_ERROR = "This feed could not be loaded."
    const val PRESENCE_TITLE = "Who's here"
    const val PRESENCE_EMPTY = "Nobody to show yet."
    const val PRESENCE_ERROR = "Presence could not be loaded."
    const val RETRY = "Retry"
    const val SKELETON_ROWS = 5
}

/**
 * Read-only renderer for #announcements / #round-board. Strictly seq-ordered.
 * There is no composer parameter and no composer slot: no role can post here from the client.
 */
@Composable
fun ServerHomeFeed(
    state: HomeFeedState,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    val space = OreTheme.space
    val ordered = remember(state.items) { state.items.distinctBy { it.id }.sortedBy { it.seq } }
    val announcements = state.kind == HomeFeedKind.Announcements
    LazyColumn(
        modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base),
        contentPadding = PaddingValues(horizontal = space.md, vertical = space.sm),
        verticalArrangement = Arrangement.spacedBy(space.sm),
    ) {
        item(key = "feed_header") {
            HomeSectionTitle(
                title = if (announcements) ServerHomeFeedCopy.ANNOUNCEMENTS_TITLE else ServerHomeFeedCopy.ROUND_BOARD_TITLE,
                trailing = ServerHomeFeedCopy.READ_ONLY,
                stale = state.stale,
            )
        }
        when (state.status) {
            HomeSurfaceStatus.Loading -> item(key = "feed_loading") { HomeSkeleton() }
            HomeSurfaceStatus.Error -> item(key = "feed_error") {
                HomeNotice(text = ServerHomeFeedCopy.FEED_ERROR, onRetry = onRetry)
            }
            HomeSurfaceStatus.Ready -> if (ordered.isEmpty()) {
                item(key = "feed_empty") {
                    HomeNotice(
                        text = if (announcements) ServerHomeFeedCopy.ANNOUNCEMENTS_EMPTY else ServerHomeFeedCopy.ROUND_BOARD_EMPTY,
                    )
                }
            } else {
                items(ordered, key = { "m_${it.id}" }) { item -> HomeFeedRow(item) }
            }
        }
    }
}

@Composable
private fun HomeFeedRow(item: HomeFeedItem, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xxs),
    ) {
        if (item.sponsored) SponsoredLabel()
        if (item.deleted) {
            TombstoneRow(kind = OreTombstoneKind.Deleted)
        } else {
            MessageBubble(
                authorName = item.authorName,
                authorRole = item.authorRole,
                body = item.body,
                timestamp = item.timestamp,
                modifier = Modifier.fillMaxWidth(),
                own = false,
                delivery = OreMessageDelivery.Delivered,
                edited = item.edited,
                deleted = false,
                commercial = item.commercial,
                stale = item.stale,
                founding = item.founding,
                moderator = item.moderator,
                crown = item.crown,
                reactions = item.reactions,
                reactionsEnabled = false,
                onReactionToggle = {},
            )
        }
    }
}

/**
 * Presence summary: counts by state (online first), activity strings verbatim, then members
 * grouped by state. Rows are tappable (MemberRow) only when [onMemberSelected] is supplied.
 */
@Composable
fun ServerHomePresencePanel(
    state: HomePresenceState,
    modifier: Modifier = Modifier,
    onMemberSelected: ((String) -> Unit)? = null,
    onRetry: () -> Unit = {},
) {
    val space = OreTheme.space
    val order = remember { listOf(OrePresence.Online, OrePresence.Idle, OrePresence.Offline) }
    val unique = remember(state.members) { state.members.distinctBy { it.userId } }
    val grouped = remember(unique) { unique.groupBy { it.presence } }
    val activities = remember(unique) {
        unique.mapNotNull { it.activity }.groupingBy { it }.eachCount().toList()
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base),
        contentPadding = PaddingValues(vertical = space.sm),
    ) {
        item(key = "presence_title") {
            HomeSectionTitle(
                title = ServerHomeFeedCopy.PRESENCE_TITLE,
                modifier = Modifier.padding(horizontal = space.md),
            )
        }
        when (state.status) {
            HomeSurfaceStatus.Loading -> item(key = "presence_loading") {
                HomeSkeleton(modifier = Modifier.padding(horizontal = space.md, vertical = space.sm))
            }
            HomeSurfaceStatus.Error -> item(key = "presence_error") {
                HomeNotice(
                    text = ServerHomeFeedCopy.PRESENCE_ERROR,
                    onRetry = onRetry,
                    modifier = Modifier.padding(horizontal = space.md, vertical = space.sm),
                )
            }
            HomeSurfaceStatus.Ready -> if (state.members.isEmpty()) {
                item(key = "presence_empty") {
                    HomeNotice(
                        text = ServerHomeFeedCopy.PRESENCE_EMPTY,
                        modifier = Modifier.padding(horizontal = space.md, vertical = space.sm),
                    )
                }
            } else {
                item(key = "presence_counts") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = space.md, vertical = space.xs),
                        horizontalArrangement = Arrangement.spacedBy(space.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        order.forEach { presence ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(space.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PresenceDot(presence = presence)
                                OreText(
                                    text = (grouped[presence]?.size ?: 0).toString(),
                                    style = OreTheme.type.numeric,
                                    color = OreTheme.color.text.primary,
                                    maxLines = 1,
                                )
                                OreText(
                                    text = presenceLabel(presence),
                                    style = OreTheme.type.caption,
                                    color = OreTheme.color.text.secondary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                items(activities, key = { "activity_${it.first}" }) { (activity, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = space.md, vertical = space.xxs),
                        horizontalArrangement = Arrangement.spacedBy(space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OreText(
                            text = activity,
                            style = OreTheme.type.caption,
                            color = OreTheme.color.text.secondary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        OreText(
                            text = count.toString(),
                            style = OreTheme.type.numeric,
                            color = OreTheme.color.text.tertiary,
                            maxLines = 1,
                        )
                    }
                }
                order.forEach { presence ->
                    val members = grouped[presence].orEmpty()
                    if (members.isNotEmpty()) {
                        item(key = "presence_header_${presence.name}") {
                            PresenceSectionHeader(presence = presence, count = members.size)
                        }
                        items(members, key = { "member_${it.userId}" }) { member ->
                            if (onMemberSelected != null) {
                                MemberRow(
                                    displayName = member.displayName,
                                    role = member.role,
                                    presence = member.presence,
                                    activity = member.activity,
                                    founding = member.founding,
                                    moderator = member.moderator,
                                    crown = member.crown,
                                    onClick = { onMemberSelected(member.userId) },
                                )
                            } else {
                                PresenceRow(
                                    displayName = member.displayName,
                                    role = member.role,
                                    presence = member.presence,
                                    activity = member.activity,
                                    founding = member.founding,
                                    moderator = member.moderator,
                                    crown = member.crown,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    stale: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OreText(
            text = title,
            style = OreTheme.type.titleSmall,
            color = OreTheme.color.text.primary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (stale) StaleBadge()
        if (trailing != null) {
            OreText(
                text = trailing,
                style = OreTheme.type.overline,
                color = OreTheme.color.text.tertiary,
                maxLines = 1,
            )
        }
    }
}

/** Inline empty / error notice; retry renders only when a callback is supplied. */
@Composable
private fun HomeNotice(
    text: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(OreTheme.space.md),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
    ) {
        OreText(text = text, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
        if (onRetry != null) OreGhostButton(text = ServerHomeFeedCopy.RETRY, onClick = onRetry)
    }
}

@Composable
private fun HomeSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
    ) {
        repeat(ServerHomeFeedCopy.SKELETON_ROWS) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(OreTheme.size.memberRowHeight)
                    .background(OreTheme.color.surface.raised),
            )
        }
    }
}
