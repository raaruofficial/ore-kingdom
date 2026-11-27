package com.raaru.orekingdom.shell

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.CategoryHeader
import com.raaru.orekingdom.designsystem.components.ChannelRow
import com.raaru.orekingdom.designsystem.tokens.OreTheme

private const val StageReservedNote = "Coming later"
private const val ModulesHeader = "Modules"
private const val ModuleGlyph = "◆"
private const val ModulePreviewNote = "preview"

private const val TypeCategory = "category"
private const val TypeChannel = "channel"
private const val TypeModule = "module"
private const val TypeState = "state"

/**
 * Channel rail: categories and channels exactly as [RailState] supplies them, in the order
 * supplied. Only channels present in the state render; anything absent has no row, no header
 * and no placeholder. Pure render of already-loaded state: nothing is fetched in composition.
 *
 * [spotlight] defaults to [SpotlightUiState.Unavailable] (renders nothing) and [modules] to
 * empty, because the landed AppShell hosts both itself; a host that does not passes them here.
 */
@Composable
fun ChannelRail(
    rail: RailState,
    onChannelSelected: (channelId: String) -> Unit,
    modifier: Modifier = Modifier,
    selectedChannelId: String? = rail.lastVisitedChannelId,
    spotlight: SpotlightUiState? = SpotlightUiState.Unavailable,
    modules: List<ModuleEntry> = emptyList(),
    onModuleSelected: (ModuleEntry) -> Unit = {},
    selectedModuleRoute: String? = null,
    onRetry: () -> Unit = {},
) {
    val hasChannels = rail.categories.any { it.channels.isNotEmpty() }
    val visibleModules = modules.filter { it.state != ModuleFlag.Off }
    LazyColumn(modifier = modifier) {
        if (spotlight != SpotlightUiState.Unavailable) {
            item(key = "spotlight", contentType = TypeState) {
                SpotlightSlot(
                    state = spotlight,
                    modifier = Modifier.padding(
                        horizontal = OreTheme.space.md,
                        vertical = OreTheme.space.xs,
                    ),
                )
            }
        }
        when {
            hasChannels -> {
                if (rail.status == RailStatus.Error) {
                    item(key = "stale", contentType = TypeState) { ShellRailStaleNotice() }
                }
                rail.categories.forEach { category ->
                    if (category.channels.isEmpty()) return@forEach
                    item(key = "category:${category.title}", contentType = TypeCategory) {
                        CategoryHeader(title = category.title)
                    }
                    items(
                        items = category.channels,
                        key = { "channel:${it.id}" },
                        contentType = { TypeChannel },
                    ) { channel ->
                        ChannelRow(
                            name = channel.name,
                            onClick = { onChannelSelected(channel.id) },
                            selected = channel.id == selectedChannelId,
                            unread = channel.unreadCount > 0,
                            unreadCount = channel.unreadCount,
                            note = if (channel.kind == ChannelKind.StageReserved) StageReservedNote else null,
                        )
                    }
                }
            }
            rail.status == RailStatus.Loading -> item(key = "loading", contentType = TypeState) {
                ShellRailSkeleton()
            }
            rail.status == RailStatus.Error -> item(key = "error", contentType = TypeState) {
                ShellRailError(onRetry = onRetry)
            }
            else -> item(key = "empty", contentType = TypeState) {
                ShellRailEmpty(onRefresh = onRetry)
            }
        }
        if (visibleModules.isNotEmpty()) {
            item(key = "modules", contentType = TypeCategory) {
                CategoryHeader(title = ModulesHeader)
            }
            items(
                items = visibleModules,
                key = { "module:${it.route}" },
                contentType = { TypeModule },
            ) { entry ->
                ChannelRow(
                    name = entry.label,
                    onClick = { onModuleSelected(entry) },
                    selected = entry.route == selectedModuleRoute,
                    glyph = ModuleGlyph,
                    note = if (entry.state == ModuleFlag.Preview) ModulePreviewNote else null,
                )
            }
        }
    }
}
