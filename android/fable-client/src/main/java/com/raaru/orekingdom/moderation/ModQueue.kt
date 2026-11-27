package com.raaru.orekingdom.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme

enum class ModQueueStatus { Loading, Ready, Error }

/** One line of the report-time context capture (contract `snapshot.context: [Message]`), display-resolved. */
data class ModContextLineUi(val id: String, val authorName: String, val body: String, val timestamp: String)

/** Report-time snapshot. Rendered as captured; never replaced by the live (edited/deleted) message. */
data class ModSnapshotUi(
    val body: String,
    val authorId: String,
    val channelId: String,
    val createdAt: String,
    val context: List<ModContextLineUi> = emptyList(),
    val authorName: String? = null,
    val channelName: String? = null,
)

/** Contract `author_history` entry. */
data class ModHistoryUi(val rung: String, val reasonCategory: String, val at: String)

/** Contract `appeal` object on a queue item. [sanctionBy] is the sanctioning moderator's user id. */
data class ModAppealUi(val statement: String, val sanctionBy: String, val state: AppealState = AppealState.Pending)

/** Contract Mod queue item. [state] is the wire value: open | claimed | closed. */
data class ModQueueItemUi(
    val reportId: String,
    val state: String,
    val reason: String,
    val reporterCount: Int,
    val snapshot: ModSnapshotUi,
    val claimedBy: String? = null,
    val claimedByName: String? = null,
    val authorHistory: List<ModHistoryUi> = emptyList(),
    val appeal: ModAppealUi? = null,
)

/** [modLoft] comes from the entitlement capability set; when false the surface renders nothing (absence). */
data class ModQueueUiState(
    val viewerId: String,
    val modLoft: Boolean,
    val status: ModQueueStatus = ModQueueStatus.Loading,
    val items: List<ModQueueItemUi> = emptyList(),
)

/** Sanctions ladder. [noteRequired] is true for mute-and-above. */
enum class ModAction(val wire: String, val label: String, val noteRequired: Boolean) {
    RemoveContent("content_removed", "Remove content", false),
    Slowmode("slowmode", "Slowmode", false),
    Mute("mute", "Mute", true),
    Suspend("suspension", "Suspend", true),
    Ban("ban", "Ban", true),
}

/** Captured moderator input for a ladder action. No contract shape exists: stub payload (TODO-CONTRACT). */
data class ModActionRequest(
    val reportId: String,
    val action: String,
    val reasonCode: String,
    val note: String,
    val durationSec: Long?,
    val overrideClaim: Boolean,
)

/** Captured moderator input for an appeal outcome. No contract shape exists: stub payload (TODO-CONTRACT). */
data class ModAppealDecision(
    val reportId: String,
    val outcome: String,
    val reasonCode: String,
    val note: String,
    val overrideClaim: Boolean,
)

internal object ModQueueCopy {
    const val TITLE = "Mod queue"
    const val EMPTY = "The queue is clear."
    const val ERROR = "The mod queue could not be loaded."
    const val RETRY = "Retry"
    const val REPORT = "REPORT"
    const val APPEAL = "APPEAL"
    const val CLOSED = "Closed"
    const val STATE_CLOSED = "closed"
    const val REPORTERS = "Reporters"
    const val REASON = "Reason"
    const val REASON_UNKNOWN = "Not specified"
    const val SNAPSHOT = "REPORT-TIME SNAPSHOT"
    const val SNAPSHOT_NOTE = "Shown as captured at report time, even if the original was edited or deleted."
    const val CONTEXT_SHOW = "Show context"
    const val CONTEXT_HIDE = "Hide context"
    const val CONTEXT_NONE = "No surrounding context was captured."
    const val HISTORY = "AUTHOR SANCTION HISTORY"
    const val HISTORY_NONE = "No prior sanctions."
    const val UNCLAIMED = "Unclaimed"
    const val CLAIMED_BY = "Claimed by"
    const val CLAIMED_YOU = "Claimed by you"
    const val CLAIM = "Claim"
    const val ACTION = "ACTION"
    const val OUTCOME = "APPEAL OUTCOME"
    const val STATEMENT = "APPEAL STATEMENT"
    const val REASON_CODE = "REASON CODE (REQUIRED)"
    const val DURATION = "MUTE DURATION"
    const val CUSTOM = "Custom"
    const val CUSTOM_HOURS = "Hours"
    const val NOTE_REQUIRED = "Note (required)"
    const val NOTE_OPTIONAL = "Note (optional)"
    const val APPLY = "Apply action"
    const val DECIDE = "Decide appeal"
    const val OWN_SANCTION = "You issued this sanction. Another moderator decides this appeal."
    const val OVERRIDE = "This item is claimed by another moderator. Override their claim and act?"
    const val OVERRIDE_CONFIRM = "Override and act"
    const val CANCEL = "Cancel"
    const val NOTE_MAX = 1000
    const val SKELETON_CARDS = 3
    val PINNED_REASONS = setOf(ReportReason.Scam.wire, ReportReason.Illegal.wire)
    val MUTE_PRESETS = listOf("1h" to 3_600L, "24h" to 86_400L, "7d" to 604_800L)
    val OUTCOMES = listOf(AppealState.Upheld to "Upheld", AppealState.Reduced to "Reduced", AppealState.Overturned to "Overturned")
}

private fun reasonLabel(wire: String): String =
    ReportReason.values().firstOrNull { it.wire == wire }?.label ?: ModQueueCopy.REASON_UNKNOWN

/**
 * #mod-queue surface. Server order (severity, then age) is kept; scam/illegal items are stably
 * pinned to the top. All callbacks are stubs (TODO-CONTRACT): the client captures moderator input,
 * the server enforces.
 */
@Composable
fun ModQueueScreen(
    state: ModQueueUiState,
    onClaim: (reportId: String) -> Unit,
    onAction: (ModActionRequest) -> Unit,
    onDecideAppeal: (ModAppealDecision) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    if (!state.modLoft) return
    val space = OreTheme.space
    val ordered = remember(state.items) {
        state.items.distinctBy { it.reportId }.sortedBy { if (it.reason in ModQueueCopy.PINNED_REASONS) 0 else 1 }
    }
    Column(modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(OreTheme.color.surface.raised)
                .padding(horizontal = space.md, vertical = space.sm),
        ) {
            OreText(text = ModQueueCopy.TITLE, style = OreTheme.type.titleSmall, color = OreTheme.color.text.primary, maxLines = 1)
        }
        when {
            state.status == ModQueueStatus.Loading && ordered.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth().padding(space.md),
                verticalArrangement = Arrangement.spacedBy(space.sm),
            ) {
                repeat(ModQueueCopy.SKELETON_CARDS) {
                    Box(Modifier.fillMaxWidth().height(OreTheme.size.touchTarget * 3).background(OreTheme.color.surface.highlight))
                }
            }
            state.status == ModQueueStatus.Error && ordered.isEmpty() -> ModCard(Modifier.padding(space.md)) {
                OreText(text = ModQueueCopy.ERROR, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
                OreGhostButton(text = ModQueueCopy.RETRY, onClick = onRetry)
            }
            ordered.isEmpty() -> ModCard(Modifier.padding(space.md)) {
                OreText(text = ModQueueCopy.EMPTY, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(space.md),
                verticalArrangement = Arrangement.spacedBy(space.md),
            ) {
                items(ordered, key = { it.reportId }) { item ->
                    ModQueueItemCard(item, state.viewerId, onClaim, onAction, onDecideAppeal)
                }
            }
        }
    }
}

/** One queue item. Appeal items render as a distinct type with the outcome control instead of the ladder. */
@Composable
fun ModQueueItemCard(
    item: ModQueueItemUi,
    viewerId: String,
    onClaim: (String) -> Unit,
    onAction: (ModActionRequest) -> Unit,
    onDecideAppeal: (ModAppealDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    val space = OreTheme.space
    val appeal = item.appeal
    val closed = item.state == ModQueueCopy.STATE_CLOSED
    val ownSanction = appeal != null && appeal.sanctionBy == viewerId
    val claimedByOther = item.claimedBy != null && item.claimedBy != viewerId
    val controlsEnabled = !ownSanction
    var contextOpen by rememberSaveable(item.reportId) { mutableStateOf(false) }
    var choice by rememberSaveable(item.reportId) { mutableStateOf<String?>(null) }
    var reasonCode by rememberSaveable(item.reportId) { mutableStateOf<String?>(null) }
    var note by rememberSaveable(item.reportId) { mutableStateOf("") }
    var muteSel by rememberSaveable(item.reportId) { mutableStateOf(-1L) }
    var customHours by rememberSaveable(item.reportId) { mutableStateOf("") }
    var confirmOverride by rememberSaveable(item.reportId) { mutableStateOf(false) }

    val action = ModAction.values().firstOrNull { it.wire == choice }
    val isMute = action == ModAction.Mute
    val durationSec: Long? = when {
        !isMute -> null
        muteSel > 0L -> muteSel
        muteSel == 0L -> customHours.toLongOrNull()?.takeIf { it > 0 }?.times(3_600L)
        else -> null
    }
    val noteRequired = appeal != null || action?.noteRequired == true
    val valid = choice != null && reasonCode != null && (!noteRequired || note.isNotBlank()) && (!isMute || durationSec != null)
    val fire = { override: Boolean ->
        val c = choice
        val r = reasonCode
        if (c != null && r != null) {
            if (appeal != null) onDecideAppeal(ModAppealDecision(item.reportId, c, r, note.trim(), override))
            else onAction(ModActionRequest(item.reportId, c, r, note.trim(), durationSec, override))
        }
    }

    ModCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
            val tone = if (appeal != null) OreTheme.color.status.info else OreTheme.color.status.warning
            Box(Modifier.background(tone.container).padding(horizontal = space.xs, vertical = space.xxs)) {
                OreText(
                    text = if (appeal != null) ModQueueCopy.APPEAL else ModQueueCopy.REPORT,
                    style = OreTheme.type.badge,
                    color = tone.content,
                    maxLines = 1,
                )
            }
            OreText(
                text = "${ModQueueCopy.REASON}: ${reasonLabel(item.reason)}",
                style = OreTheme.type.bodyStrong,
                color = OreTheme.color.text.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            OreText(
                text = "${ModQueueCopy.REPORTERS}: ${item.reporterCount}",
                style = OreTheme.type.numeric,
                color = OreTheme.color.text.secondary,
                maxLines = 1,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
            OreText(
                text = when {
                    closed -> ModQueueCopy.CLOSED
                    item.claimedBy == null -> ModQueueCopy.UNCLAIMED
                    !claimedByOther -> ModQueueCopy.CLAIMED_YOU
                    else -> "${ModQueueCopy.CLAIMED_BY} ${item.claimedByName ?: item.claimedBy}"
                },
                style = OreTheme.type.caption,
                color = OreTheme.color.text.secondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (!closed && item.claimedBy == null) {
                OreGhostButton(text = ModQueueCopy.CLAIM, onClick = { onClaim(item.reportId) }, enabled = controlsEnabled)
            }
        }

        ModOverline(ModQueueCopy.SNAPSHOT)
        Column(
            modifier = Modifier.fillMaxWidth().background(OreTheme.color.surface.sunken).padding(space.sm),
            verticalArrangement = Arrangement.spacedBy(space.xxs),
        ) {
            OreText(
                text = "${item.snapshot.authorName ?: item.snapshot.authorId} · #${item.snapshot.channelName ?: item.snapshot.channelId} · ${item.snapshot.createdAt}",
                style = OreTheme.type.caption,
                color = OreTheme.color.text.tertiary,
                maxLines = 1,
            )
            OreText(text = item.snapshot.body, style = OreTheme.type.body, color = OreTheme.color.text.primary)
        }
        OreText(text = ModQueueCopy.SNAPSHOT_NOTE, style = OreTheme.type.caption, color = OreTheme.color.text.tertiary)
        OreGhostButton(
            text = if (contextOpen) ModQueueCopy.CONTEXT_HIDE else ModQueueCopy.CONTEXT_SHOW,
            onClick = { contextOpen = !contextOpen },
        )
        if (contextOpen) {
            if (item.snapshot.context.isEmpty()) {
                OreText(text = ModQueueCopy.CONTEXT_NONE, style = OreTheme.type.caption, color = OreTheme.color.text.tertiary)
            }
            item.snapshot.context.forEach { line ->
                Column(modifier = Modifier.fillMaxWidth().padding(start = space.sm)) {
                    OreText(
                        text = "${line.authorName} · ${line.timestamp}",
                        style = OreTheme.type.caption,
                        color = OreTheme.color.text.tertiary,
                        maxLines = 1,
                    )
                    OreText(text = line.body, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
                }
            }
        }

        ModOverline(ModQueueCopy.HISTORY)
        if (item.authorHistory.isEmpty()) {
            OreText(text = ModQueueCopy.HISTORY_NONE, style = OreTheme.type.caption, color = OreTheme.color.text.tertiary)
        }
        item.authorHistory.forEach { entry ->
            OreText(
                text = "${SanctionRung.of(entry.rung)?.label ?: SanctionCopy.RUNG_UNKNOWN} · ${reasonLabel(entry.reasonCategory)} · ${entry.at}",
                style = OreTheme.type.caption,
                color = OreTheme.color.text.secondary,
                maxLines = 1,
            )
        }

        if (appeal != null) {
            ModOverline(ModQueueCopy.STATEMENT)
            OreText(text = appeal.statement, style = OreTheme.type.body, color = OreTheme.color.text.primary)
        }
        if (closed) return@ModCard

        if (ownSanction) {
            OreText(text = ModQueueCopy.OWN_SANCTION, style = OreTheme.type.caption, color = OreTheme.color.status.warning.content)
        }
        ModOverline(if (appeal != null) ModQueueCopy.OUTCOME else ModQueueCopy.ACTION)
        ModChoiceRow {
            if (appeal != null) {
                ModQueueCopy.OUTCOMES.forEach { (outcome, label) ->
                    ModChoice(label, choice == outcome.wire, controlsEnabled) { choice = outcome.wire }
                }
            } else {
                ModAction.values().forEach { a -> ModChoice(a.label, choice == a.wire, controlsEnabled) { choice = a.wire } }
            }
        }
        if (isMute) {
            ModOverline(ModQueueCopy.DURATION)
            ModChoiceRow {
                ModQueueCopy.MUTE_PRESETS.forEach { (label, sec) -> ModChoice(label, muteSel == sec, controlsEnabled) { muteSel = sec } }
                ModChoice(ModQueueCopy.CUSTOM, muteSel == 0L, controlsEnabled) { muteSel = 0L }
            }
            if (muteSel == 0L) {
                ModField(customHours, ModQueueCopy.CUSTOM_HOURS, controlsEnabled) { v ->
                    if (v.length <= 4 && v.all { it.isDigit() }) customHours = v
                }
            }
        }
        ModOverline(ModQueueCopy.REASON_CODE)
        ModChoiceRow {
            ReportReason.values().forEach { r -> ModChoice(r.label, reasonCode == r.wire, controlsEnabled) { reasonCode = r.wire } }
        }
        ModField(note, if (noteRequired) ModQueueCopy.NOTE_REQUIRED else ModQueueCopy.NOTE_OPTIONAL, controlsEnabled) { v ->
            if (v.length <= ModQueueCopy.NOTE_MAX) note = v
        }
        if (confirmOverride && claimedByOther) {
            Column(
                modifier = Modifier.fillMaxWidth().background(OreTheme.color.status.warning.container).padding(space.sm),
                verticalArrangement = Arrangement.spacedBy(space.xs),
            ) {
                OreText(text = ModQueueCopy.OVERRIDE, style = OreTheme.type.body, color = OreTheme.color.status.warning.content)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
                    OrePrimaryButton(
                        text = ModQueueCopy.OVERRIDE_CONFIRM,
                        onClick = { confirmOverride = false; fire(true) },
                        modifier = Modifier.weight(1f),
                        enabled = valid && controlsEnabled,
                    )
                    OreGhostButton(text = ModQueueCopy.CANCEL, onClick = { confirmOverride = false })
                }
            }
        } else {
            OrePrimaryButton(
                text = if (appeal != null) ModQueueCopy.DECIDE else ModQueueCopy.APPLY,
                onClick = { if (claimedByOther) confirmOverride = true else fire(false) },
                modifier = Modifier.fillMaxWidth(),
                enabled = valid && controlsEnabled,
            )
        }
    }
}

@Composable
private fun ModChoiceRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) { content() }
}

@Composable
private fun ModChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = OreTheme.size.touchTarget)
            .background(if (selected) OreTheme.color.accent.subtle else OreTheme.color.surface.sunken)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = OreTheme.space.md),
        contentAlignment = Alignment.Center,
    ) {
        OreText(
            text = label,
            style = OreTheme.type.label,
            color = when {
                !enabled -> OreTheme.color.text.disabled
                selected -> OreTheme.color.accent.onSubtle
                else -> OreTheme.color.text.primary
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun ModField(value: String, placeholder: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OreTheme.size.composerMinHeight)
            .background(OreTheme.color.surface.sunken)
            .padding(OreTheme.space.sm),
    ) {
        if (value.isEmpty()) {
            OreText(text = placeholder, style = OreTheme.type.body, color = OreTheme.color.text.tertiary, maxLines = 1)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            textStyle = OreTheme.type.body.copy(color = if (enabled) OreTheme.color.text.primary else OreTheme.color.text.disabled),
            cursorBrush = SolidColor(OreTheme.color.accent.primary),
        )
    }
}

@Composable
private fun ModOverline(text: String) {
    OreText(text = text, style = OreTheme.type.overline, color = OreTheme.color.text.tertiary, maxLines = 1)
}

@Composable
private fun ModCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(OreTheme.space.md),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) { content() }
}
