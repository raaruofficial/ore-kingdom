package com.raaru.orekingdom.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.CategoryHeader
import com.raaru.orekingdom.designsystem.components.ChannelRow
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OrePresence
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.components.PresenceDot
import com.raaru.orekingdom.designsystem.tokens.OreTheme
import com.raaru.orekingdom.shell.ChannelKind
import com.raaru.orekingdom.shell.ModuleEntry
import com.raaru.orekingdom.shell.ModuleFlag
import com.raaru.orekingdom.shell.RailState
import com.raaru.orekingdom.shell.RailStatus
import com.raaru.orekingdom.shell.ShellRailEmpty
import com.raaru.orekingdom.shell.ShellRailError
import com.raaru.orekingdom.shell.ShellRailSkeleton
import com.raaru.orekingdom.shell.SpotlightSlot
import com.raaru.orekingdom.shell.SpotlightUiState

/** Standing copy for the server home surface. */
internal object ServerHomeCopy {
    const val TITLE = "The Kingdom"
    const val ONLINE_SUFFIX = "online"
    const val MODULES_HEADER = "MODULE AREAS"
    const val MODULE_PREVIEW_NOTE = "Preview"
    const val STAGE_RESERVED_NOTE = "Coming later"
    const val PROFILE_ENTRY = "Members & profile"
    const val ACTIVITY_SEPARATOR = " · "
}

/**
 * Server home root: presence summary header, spotlight slot (home placement, above the
 * overview, for every caller), category/channel overview in server-returned order,
 * module-area entries with flag states, and the member/profile entry point.
 * Channels/categories the caller cannot read are absent from [rail] and therefore absent here.
 */
@Composable
fun ServerHomeScreen(
    rail: RailState,
    spotlight: SpotlightUiState?,
    modules: List<ModuleEntry>,
    onlineCount: Int,
    activityLines: List<String>,
    onChannelSelected: (String) -> Unit,
    onModuleSelected: (ModuleEntry) -> Unit,
    onProfileSelected: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    val space = OreTheme.space
    val visibleModules = remember(modules) { modules.filter { it.state != ModuleFlag.Off } }
    val categories = remember(rail.categories) { rail.categories.filter { it.channels.isNotEmpty() } }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base),
        contentPadding = PaddingValues(bottom = space.xl),
    ) {
        item(key = "home_header") {
            ServerHomeHeader(
                onlineCount = onlineCount,
                activityLines = activityLines,
                onProfileSelected = onProfileSelected,
            )
        }
        item(key = "home_spotlight") {
            SpotlightSlot(
                state = spotlight,
                modifier = Modifier.fillMaxWidth().padding(horizontal = space.md, vertical = space.sm),
            )
        }
        when (rail.status) {
            RailStatus.Loading -> item(key = "home_loading") { ShellRailSkeleton() }
            RailStatus.Error -> item(key = "home_error") { ShellRailError(onRetry = onRetry) }
            RailStatus.Ready -> {
                if (categories.isEmpty()) {
                    item(key = "home_empty") { ShellRailEmpty(onRefresh = onRetry) }
                }
                categories.forEachIndexed { index, category ->
                    item(key = "cat_$index") { CategoryHeader(title = category.title) }
                    items(category.channels, key = { "ch_${it.id}" }) { channel ->
                        ChannelRow(
                            name = channel.name,
                            onClick = { onChannelSelected(channel.id) },
                            selected = channel.id == rail.lastVisitedChannelId,
                            unread = channel.unreadCount > 0,
                            unreadCount = channel.unreadCount,
                            note = if (channel.kind == ChannelKind.StageReserved) {
                                ServerHomeCopy.STAGE_RESERVED_NOTE
                            } else {
                                null
                            },
                        )
                    }
                }
                if (visibleModules.isNotEmpty()) {
                    item(key = "mod_header") { CategoryHeader(title = ServerHomeCopy.MODULES_HEADER) }
                    items(visibleModules, key = { "mod_${it.route}" }) { entry ->
                        ChannelRow(
                            name = entry.label,
                            onClick = { onModuleSelected(entry) },
                            note = if (entry.state == ModuleFlag.Preview) {
                                ServerHomeCopy.MODULE_PREVIEW_NOTE
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Compact presence summary: online count + activity strings verbatim, with the profile entry. */
@Composable
private fun ServerHomeHeader(
    onlineCount: Int,
    activityLines: List<String>,
    onProfileSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val space = OreTheme.space
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(horizontal = space.md, vertical = space.sm),
        verticalArrangement = Arrangement.spacedBy(space.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            OreText(
                text = ServerHomeCopy.TITLE,
                style = OreTheme.type.titleMedium,
                color = OreTheme.color.text.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            OreGhostButton(text = ServerHomeCopy.PROFILE_ENTRY, onClick = onProfileSelected)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(space.xs),
        ) {
            PresenceDot(presence = OrePresence.Online)
            OreText(
                text = "$onlineCount ${ServerHomeCopy.ONLINE_SUFFIX}",
                style = OreTheme.type.numeric,
                color = OreTheme.color.text.secondary,
                maxLines = 1,
            )
        }
        if (activityLines.isNotEmpty()) {
            OreText(
                text = activityLines.joinToString(ServerHomeCopy.ACTIVITY_SEPARATOR),
                style = OreTheme.type.caption,
                color = OreTheme.color.text.tertiary,
                maxLines = 2,
            )
        }
    }
}
