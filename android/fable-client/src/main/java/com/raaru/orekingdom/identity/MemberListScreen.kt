package com.raaru.orekingdom.identity

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.MemberRow
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OrePresence
import com.raaru.orekingdom.designsystem.components.OreRole
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.components.PresenceRow
import com.raaru.orekingdom.designsystem.components.PresenceSectionHeader
import com.raaru.orekingdom.designsystem.components.StaleBadge
import com.raaru.orekingdom.designsystem.tokens.OreTheme

enum class MemberListStatus { Loading, Ready, Error }

/**
 * One member row. Mirrors the contract Member + Presence shapes. [presence] and [activity] are
 * rendered exactly as supplied: an invisible member the server reports as offline renders offline
 * (F-RT-1), and [activity] is the contract string verbatim (null = nothing rendered).
 * [moderator] is orthogonal to [role]; both marks show when both apply.
 */
data class MemberUi(
    val userId: String,
    val displayName: String,
    val role: OreRole,
    val presence: OrePresence,
    val activity: String? = null,
    val founding: Boolean = false,
    val moderator: Boolean = false,
    val crown: Boolean = false,
)

data class MemberListUiState(
    val status: MemberListStatus = MemberListStatus.Loading,
    val members: List<MemberUi> = emptyList(),
    val stale: Boolean = false,
)

internal object MemberListCopy {
    const val TITLE = "Members"
    const val EMPTY = "No members to show here yet."
    const val ERROR = "Members could not be loaded."
    const val RETRY = "Retry"
    const val SKELETON_ROWS = 8
}

/**
 * Member list + presence panel: grouped by presence state, online first; order within a group is
 * as supplied. Role colours and badges come from the F1 rows only. Rows are tappable (MemberRow)
 * when [onMemberSelected] is supplied, otherwise static (PresenceRow).
 */
@Composable
fun MemberListScreen(
    state: MemberListUiState,
    modifier: Modifier = Modifier,
    onMemberSelected: ((userId: String) -> Unit)? = null,
    onRetry: () -> Unit = {},
) {
    val space = OreTheme.space
    val order = remember { listOf(OrePresence.Online, OrePresence.Idle, OrePresence.Offline) }
    val grouped = remember(state.members) { state.members.distinctBy { it.userId }.groupBy { it.presence } }

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
                text = MemberListCopy.TITLE,
                style = OreTheme.type.titleSmall,
                color = OreTheme.color.text.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (state.stale) StaleBadge()
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.status == MemberListStatus.Loading && state.members.isEmpty() -> MemberListSkeleton()
                state.status == MemberListStatus.Error && state.members.isEmpty() ->
                    MemberListNotice(text = MemberListCopy.ERROR, onRetry = onRetry)
                state.members.isEmpty() -> MemberListNotice(text = MemberListCopy.EMPTY)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = space.sm),
                ) {
                    order.forEach { presence ->
                        val members = grouped[presence].orEmpty()
                        if (members.isNotEmpty()) {
                            item(key = "header_${presence.name}") {
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
}

@Composable
private fun MemberListNotice(text: String, onRetry: (() -> Unit)? = null) {
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
            if (onRetry != null) OreGhostButton(text = MemberListCopy.RETRY, onClick = onRetry)
        }
    }
}

@Composable
private fun MemberListSkeleton() {
    val space = OreTheme.space
    val size = OreTheme.size
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = space.md, vertical = space.sm)) {
        repeat(MemberListCopy.SKELETON_ROWS) { index ->
            Row(
                modifier = Modifier.fillMaxWidth().height(size.memberRowHeight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(space.sm),
            ) {
                Box(Modifier.size(size.avatarSmall).background(OreTheme.color.surface.highlight))
                Box(
                    Modifier
                        .fillMaxWidth(if (index % 2 == 0) 0.5f else 0.35f)
                        .height(space.md)
                        .background(OreTheme.color.surface.highlight),
                )
            }
        }
    }
}
