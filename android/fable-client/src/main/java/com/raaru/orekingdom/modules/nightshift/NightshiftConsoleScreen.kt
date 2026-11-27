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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.components.StaleBadge
import com.raaru.orekingdom.designsystem.tokens.OreTheme
import com.raaru.orekingdom.shell.ModuleFlag

enum class NightshiftStatus { Loading, Ready, Error }

/** One assisted-placement suggestion, exactly as the module read model supplies it. */
data class SuggestionUi(val id: String, val title: String, val body: String)

/** One label/value line of the fleet summary, both strings supplied by the read model. */
data class FleetSummaryLineUi(val label: String, val value: String)

/** Fleet status summary. Rendered verbatim; the client computes nothing. */
data class FleetSummaryUi(
    val headline: String,
    val lines: List<FleetSummaryLineUi> = emptyList(),
)

data class NightshiftConsoleUi(
    val status: NightshiftStatus = NightshiftStatus.Loading,
    val fleet: FleetSummaryUi? = null,
    val suggestions: List<SuggestionUi> = emptyList(),
    val stale: Boolean = false,
)

internal object NightshiftConsoleCopy {
    const val TITLE = "NIGHTSHIFT"
    const val PREVIEW_TAG = "Preview"
    const val PREVIEW_NOTE = "Preview — actions are not available yet."
    const val NOT_ENTITLED_NOTE = "Console actions are not part of your current access."
    const val FLEET_HEADER = "FLEET STATUS"
    const val FLEET_EMPTY = "No fleet status to show yet."
    const val ASSISTED_HEADER = "ASSISTED PLACEMENT"
    const val ASSISTED_NOTE = "Assisted: you review every suggestion. Nothing is placed unless you accept it."
    const val ASSISTED_TAG = "Assisted"
    const val SUGGESTIONS_EMPTY = "No assisted placement suggestions right now."
    const val ACCEPT = "Accept"
    const val DISMISS = "Dismiss"
    const val ERROR = "NIGHTSHIFT could not be loaded."
    const val RETRY = "Retry"
    const val SKELETON_CARDS = 3
}

/**
 * NIGHTSHIFT console host. [flag] Off composes nothing (the shell handles absence); Preview renders
 * every action control disabled with the preview note; On renders the full surface.
 * [consoleEntitled] is the entitlement capability (`nightshiftConsole`), passed by the host; when
 * false the console stays informational with actions disabled.
 * [onAccept] / [onDismiss] are stub callbacks (TODO-CONTRACT). [intel] and [addOn] are slots for
 * the intel items and the entitlement-driven add-on surface.
 */
@Composable
fun NightshiftConsoleScreen(
    state: NightshiftConsoleUi,
    flag: ModuleFlag,
    modifier: Modifier = Modifier,
    consoleEntitled: Boolean = true,
    onAccept: (suggestionId: String) -> Unit = {},
    onDismiss: (suggestionId: String) -> Unit = {},
    onRetry: () -> Unit = {},
    intel: @Composable () -> Unit = {},
    addOn: @Composable () -> Unit = {},
) {
    if (flag == ModuleFlag.Off) return
    val space = OreTheme.space
    val preview = flag == ModuleFlag.Preview
    val actionsEnabled = !preview && consoleEntitled
    val actionNote = when {
        preview -> NightshiftConsoleCopy.PREVIEW_NOTE
        !consoleEntitled -> NightshiftConsoleCopy.NOT_ENTITLED_NOTE
        else -> null
    }
    val hasContent = state.fleet != null || state.suggestions.isNotEmpty()

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
                text = NightshiftConsoleCopy.TITLE,
                style = OreTheme.type.titleSmall,
                color = OreTheme.color.text.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (state.stale) StaleBadge()
            if (preview) {
                OreText(
                    text = NightshiftConsoleCopy.PREVIEW_TAG,
                    style = OreTheme.type.overline,
                    color = OreTheme.color.status.info.content,
                    maxLines = 1,
                )
            }
        }
        when {
            state.status == NightshiftStatus.Loading && !hasContent -> NightshiftConsoleSkeleton()
            state.status == NightshiftStatus.Error && !hasContent -> NightshiftCard(Modifier.padding(space.md)) {
                OreText(
                    text = NightshiftConsoleCopy.ERROR,
                    style = OreTheme.type.body,
                    color = OreTheme.color.text.secondary,
                )
                OreGhostButton(text = NightshiftConsoleCopy.RETRY, onClick = onRetry)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(space.md),
                verticalArrangement = Arrangement.spacedBy(space.sm),
            ) {
                if (actionNote != null) {
                    item(key = "action_note") {
                        OreText(
                            text = actionNote,
                            style = OreTheme.type.caption,
                            color = OreTheme.color.status.info.content,
                        )
                    }
                }
                item(key = "fleet") { FleetSummaryCard(state.fleet) }
                item(key = "assisted_header") {
                    Column(verticalArrangement = Arrangement.spacedBy(space.xxs)) {
                        NightshiftOverline(NightshiftConsoleCopy.ASSISTED_HEADER)
                        OreText(
                            text = NightshiftConsoleCopy.ASSISTED_NOTE,
                            style = OreTheme.type.caption,
                            color = OreTheme.color.text.secondary,
                        )
                    }
                }
                if (state.suggestions.isEmpty()) {
                    item(key = "assisted_empty") {
                        NightshiftCard {
                            OreText(
                                text = NightshiftConsoleCopy.SUGGESTIONS_EMPTY,
                                style = OreTheme.type.body,
                                color = OreTheme.color.text.secondary,
                            )
                        }
                    }
                } else {
                    items(state.suggestions, key = { "suggestion_${it.id}" }) { suggestion ->
                        SuggestionCard(
                            suggestion = suggestion,
                            actionsEnabled = actionsEnabled,
                            actionNote = actionNote,
                            onAccept = { onAccept(suggestion.id) },
                            onDismiss = { onDismiss(suggestion.id) },
                        )
                    }
                }
                item(key = "intel") { intel() }
                item(key = "add_on") { addOn() }
            }
        }
    }
}

@Composable
private fun FleetSummaryCard(fleet: FleetSummaryUi?) {
    NightshiftCard {
        NightshiftOverline(NightshiftConsoleCopy.FLEET_HEADER)
        if (fleet == null) {
            OreText(
                text = NightshiftConsoleCopy.FLEET_EMPTY,
                style = OreTheme.type.body,
                color = OreTheme.color.text.secondary,
            )
        } else {
            OreText(text = fleet.headline, style = OreTheme.type.bodyStrong, color = OreTheme.color.text.primary)
            fleet.lines.forEach { line ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
                ) {
                    OreText(
                        text = line.label,
                        style = OreTheme.type.caption,
                        color = OreTheme.color.text.tertiary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    OreText(
                        text = line.value,
                        style = OreTheme.type.numeric,
                        color = OreTheme.color.text.primary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Reviewable assisted-placement card. Accept / dismiss are stubs; disabled with the note. */
@Composable
private fun SuggestionCard(
    suggestion: SuggestionUi,
    actionsEnabled: Boolean,
    actionNote: String?,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val space = OreTheme.space
    NightshiftCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
            OreText(
                text = suggestion.title,
                style = OreTheme.type.bodyStrong,
                color = OreTheme.color.text.primary,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            OreText(
                text = NightshiftConsoleCopy.ASSISTED_TAG,
                style = OreTheme.type.overline,
                color = OreTheme.color.accent.onSubtle,
                modifier = Modifier
                    .background(OreTheme.color.accent.subtle)
                    .padding(horizontal = space.sm, vertical = space.xxs),
                maxLines = 1,
            )
        }
        OreText(text = suggestion.body, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
            OrePrimaryButton(
                text = NightshiftConsoleCopy.ACCEPT,
                onClick = onAccept,
                modifier = Modifier.weight(1f),
                enabled = actionsEnabled,
                note = if (actionsEnabled) null else actionNote,
            )
            OreGhostButton(text = NightshiftConsoleCopy.DISMISS, onClick = onDismiss, enabled = actionsEnabled)
        }
    }
}

@Composable
private fun NightshiftOverline(text: String) {
    OreText(text = text, style = OreTheme.type.overline, color = OreTheme.color.text.tertiary, maxLines = 1)
}

@Composable
private fun NightshiftCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(OreTheme.space.md),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) { content() }
}

@Composable
private fun NightshiftConsoleSkeleton() {
    val space = OreTheme.space
    Column(
        modifier = Modifier.fillMaxSize().padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.sm),
    ) {
        repeat(NightshiftConsoleCopy.SKELETON_CARDS) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(OreTheme.size.touchTarget * 2)
                    .background(OreTheme.color.surface.highlight),
            )
        }
    }
}
