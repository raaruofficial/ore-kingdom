package com.raaru.orekingdom.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.raaru.orekingdom.designsystem.components.CategoryHeader
import com.raaru.orekingdom.designsystem.components.ChannelRow
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreDarkColors
import com.raaru.orekingdom.designsystem.tokens.OreTheme

/** Server-driven module flag (BRIEF §5); mapped from the entitlement read by [ModuleRegistry]. */
enum class ModuleFlag { Off, Preview, On }

/** One navigable module area, as built by [ModuleRegistry.entries]. */
@Immutable
data class ModuleEntry(val route: String, val label: String, val state: ModuleFlag)

private const val ServerTitle = "The Kingdom"
private const val ProfileLabel = "Profile"
private const val OpenRailLabel = "Channels"
private const val ModulesHeader = "Modules"
private const val ModuleGlyph = "◆"
private const val ChannelPrefix = "# "
private const val PreviewNote = "preview"
private const val StageReservedNote = "Coming later"
private const val RailWidthFraction = 0.86f

/**
 * Root navigation host: content area with the rail one swipe away. Opens to the last-visited
 * channel. The module route table derives from [modules] only, so a removed registry entry
 * leaves no navigation and no reachable route.
 */
@Composable
fun AppShell(
    rail: RailState,
    spotlight: SpotlightUiState?,
    modules: List<ModuleEntry>,
    onChannelSelected: (channelId: String) -> Unit,
    onModuleSelected: (ModuleEntry) -> Unit,
    onProfileSelected: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    channelContent: @Composable (RailChannel) -> Unit = { ChannelContentPlaceholder(it) },
    moduleContent: @Composable (ModuleEntry) -> Unit = { ModulePlaceholder(entry = it) },
) {
    OreTheme(colors = OreDarkColors) {
        var railOpen by rememberSaveable { mutableStateOf(false) }
        var moduleRoute by rememberSaveable { mutableStateOf<String?>(null) }
        var pickedChannelId by rememberSaveable { mutableStateOf<String?>(null) }

        val routes = remember(modules) {
            modules.filter { it.state != ModuleFlag.Off }.associateBy { it.route }
        }
        val channels = remember(rail.categories) { rail.categories.flatMap { it.channels } }
        // A route or channel that is no longer supplied simply stops resolving (mid-session gating).
        val activeModule = moduleRoute?.let { routes[it] }
        val activeChannel = channels.firstOrNull { it.id == pickedChannelId }
            ?: channels.firstOrNull { it.id == rail.lastVisitedChannelId }
            ?: channels.firstOrNull()

        val fraction by animateFloatAsState(
            targetValue = if (railOpen) 1f else 0f,
            animationSpec = tween(
                durationMillis = OreTheme.motion.duration.standard,
                easing = OreTheme.motion.easing.decelerate,
            ),
            label = "appShellRail",
        )
        val swipePx = with(LocalDensity.current) { OreTheme.size.touchTarget.toPx() }

        BackHandler(enabled = railOpen) { railOpen = false }

        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(OreTheme.color.surface.base)
                .safeDrawingPadding()
                .pointerInput(swipePx) {
                    var total = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total > swipePx) railOpen = true
                            else if (total < -swipePx) railOpen = false
                        },
                    ) { _, drag -> total += drag }
                },
        ) {
            val railWidth = maxWidth * RailWidthFraction

            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OreTheme.color.surface.raised)
                        .padding(horizontal = OreTheme.space.md, vertical = OreTheme.space.xs),
                    horizontalArrangement = Arrangement.spacedBy(OreTheme.space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OreGhostButton(text = OpenRailLabel, onClick = { railOpen = true })
                    OreText(
                        text = activeModule?.label
                            ?: activeChannel?.let { ChannelPrefix + it.name }
                            ?: ServerTitle,
                        style = OreTheme.type.titleSmall,
                        color = OreTheme.color.text.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        activeModule != null -> moduleContent(activeModule)
                        activeChannel != null -> channelContent(activeChannel)
                        rail.status == RailStatus.Loading -> ShellRailSkeleton()
                        rail.status == RailStatus.Error -> ShellRailError(onRetry = onRetry)
                        else -> ShellRailEmpty(onRefresh = onRetry)
                    }
                }
            }

            if (railOpen || fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(fraction)
                        .background(OreTheme.color.surface.scrim)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { railOpen = false },
                        ),
                )
                Column(
                    modifier = Modifier
                        .width(railWidth)
                        .fillMaxHeight()
                        .graphicsLayer { translationX = -(1f - fraction) * size.width }
                        .background(OreTheme.color.surface.raised)
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = OreTheme.space.md, vertical = OreTheme.space.sm),
                        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OreText(
                            text = ServerTitle,
                            style = OreTheme.type.titleMedium,
                            color = OreTheme.color.text.primary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        OreGhostButton(
                            text = ProfileLabel,
                            onClick = {
                                railOpen = false
                                onProfileSelected()
                            },
                        )
                    }
                    // Spotlight sits above the channel rail for every user (BRIEF §8).
                    SpotlightSlot(
                        state = spotlight,
                        modifier = Modifier.padding(
                            horizontal = OreTheme.space.md,
                            vertical = OreTheme.space.xs,
                        ),
                    )
                    ChannelRail(
                        rail = rail,
                        selectedChannelId = if (activeModule == null) activeChannel?.id else null,
                        onChannelSelected = { id ->
                            pickedChannelId = id
                            moduleRoute = null
                            railOpen = false
                            onChannelSelected(id)
                        },
                        onRetry = onRetry,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    if (routes.isNotEmpty()) {
                        CategoryHeader(title = ModulesHeader)
                        routes.values.forEach { entry ->
                            ChannelRow(
                                name = entry.label,
                                onClick = {
                                    moduleRoute = entry.route
                                    railOpen = false
                                    onModuleSelected(entry)
                                },
                                selected = activeModule?.route == entry.route,
                                glyph = ModuleGlyph,
                                note = if (entry.state == ModuleFlag.Preview) PreviewNote else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Default content slot until the channel screens land (later task). Never blank. */
@Composable
private fun ChannelContentPlaceholder(channel: RailChannel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(OreTheme.space.lg),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) {
        OreText(
            text = ChannelPrefix + channel.name,
            style = OreTheme.type.titleMedium,
            color = OreTheme.color.text.primary,
            maxLines = 1,
        )
        if (channel.kind == ChannelKind.StageReserved) {
            OreText(
                text = StageReservedNote,
                style = OreTheme.type.body,
                color = OreTheme.color.text.secondary,
            )
        }
    }
}
