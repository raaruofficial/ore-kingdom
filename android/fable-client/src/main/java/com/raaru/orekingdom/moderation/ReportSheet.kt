package com.raaru.orekingdom.moderation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme

/** Contract `target_kind` values, verbatim in [wire]. */
enum class ReportTargetKind(val wire: String) {
    Message("message"), Thread("thread"), Profile("profile"),
    Spotlight("spotlight"), CommercialPost("commercial_post"),
}

/** Contract `reason` values, verbatim in [wire]. */
enum class ReportReason(val wire: String, val label: String) {
    Spam("spam", "Spam"), Abuse("abuse", "Abuse"), Scam("scam", "Scam"),
    Illegal("illegal", "Illegal"), Other("other", "Other"),
}

/** Contract report submission shape: `{ target_kind, target_id, reason, note }`. */
data class ReportSubmission(
    val targetKind: String,
    val targetId: String,
    val reason: String,
    val note: String,
)

/** Sheet phase, driven by the host: Submitting = loading, Failed = error (fixed copy, retryable). */
enum class ReportPhase { Editing, Submitting, Acknowledged, Failed }

data class ReportSheetUiState(
    val targetKind: ReportTargetKind,
    val targetId: String,
    val phase: ReportPhase = ReportPhase.Editing,
)

internal object ReportCopy {
    const val TITLE = "Report"
    const val REASON_HEADER = "REASON"
    const val NOTE_HEADER = "DETAILS (OPTIONAL)"
    const val NOTE_PLACEHOLDER = "Add anything that helps a moderator understand."
    const val SUBMIT = "Submit report"
    const val SUBMITTING = "Sending your report…"
    const val CANCEL = "Cancel"
    const val CHOOSE_REASON = "Choose a reason to continue."
    const val ACK_TITLE = "Report received"
    const val ACK_BODY = "Thanks. A moderator will review it. You'll get a notice when it is closed."
    const val DONE = "Done"
    const val FAILED = "Your report could not be sent. Nothing was lost — try again."
    const val RETRY = "Retry"
    const val CLOSED_TITLE = "Your report was closed"
    const val CLOSED_ACTIONED = "Outcome: actioned"
    const val CLOSED_NO_ACTION = "Outcome: no action"
    const val CLOSED_GENERIC = "This report has been closed."
    const val OUTCOME_ACTIONED = "actioned"
    const val OUTCOME_NO_ACTION = "no_action"
    const val NOTE_MAX = 1000
}

/**
 * Report sheet content (host places it in its sheet/dialog container). Available to every role,
 * including Free readers: nothing here is entitlement-gated.
 * [onSubmit] is a stub callback (TODO-CONTRACT): the host performs the report submission and
 * drives [ReportSheetUiState.phase]. Selection and note survive Failed, so retry loses nothing.
 */
@Composable
fun ReportSheet(
    state: ReportSheetUiState,
    onSubmit: (ReportSubmission) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val space = OreTheme.space
    val stateKey = state.targetKind.wire + "/" + state.targetId
    var reasonWire by rememberSaveable(stateKey) { mutableStateOf<String?>(null) }
    var note by rememberSaveable(stateKey) { mutableStateOf("") }
    val submitting = state.phase == ReportPhase.Submitting
    val submit = {
        reasonWire?.let { onSubmit(ReportSubmission(state.targetKind.wire, state.targetId, it, note.trim())) }
        Unit
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.overlay)
            .verticalScroll(rememberScrollState())
            .padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.sm),
    ) {
        OreText(text = ReportCopy.TITLE, style = OreTheme.type.titleSmall, color = OreTheme.color.text.primary, maxLines = 1)
        if (state.phase == ReportPhase.Acknowledged) {
            OreText(text = ReportCopy.ACK_TITLE, style = OreTheme.type.bodyStrong, color = OreTheme.color.text.primary)
            OreText(text = ReportCopy.ACK_BODY, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
            OrePrimaryButton(text = ReportCopy.DONE, onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            return@Column
        }
        ReportOverline(ReportCopy.REASON_HEADER)
        ReportReason.values().forEach { reason ->
            val selected = reasonWire == reason.wire
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(OreTheme.size.touchTarget)
                    .background(if (selected) OreTheme.color.accent.subtle else OreTheme.color.surface.raised)
                    .clickable(enabled = !submitting) { reasonWire = reason.wire }
                    .padding(horizontal = space.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(space.sm),
            ) {
                Box(
                    Modifier
                        .size(OreTheme.size.presenceDot)
                        .background(if (selected) OreTheme.color.accent.primary else OreTheme.color.border.strong),
                )
                OreText(
                    text = reason.label,
                    style = OreTheme.type.body,
                    color = if (selected) OreTheme.color.accent.onSubtle else OreTheme.color.text.primary,
                    maxLines = 1,
                )
            }
        }
        ReportOverline(ReportCopy.NOTE_HEADER)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = OreTheme.size.composerMinHeight * 2)
                .background(OreTheme.color.surface.sunken)
                .padding(space.sm),
        ) {
            if (note.isEmpty()) {
                OreText(text = ReportCopy.NOTE_PLACEHOLDER, style = OreTheme.type.body, color = OreTheme.color.text.tertiary)
            }
            BasicTextField(
                value = note,
                onValueChange = { if (it.length <= ReportCopy.NOTE_MAX) note = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !submitting,
                textStyle = OreTheme.type.body.copy(color = OreTheme.color.text.primary),
                cursorBrush = SolidColor(OreTheme.color.accent.primary),
            )
        }
        when {
            submitting -> OreText(
                text = ReportCopy.SUBMITTING,
                style = OreTheme.type.caption,
                color = OreTheme.color.text.secondary,
            )
            state.phase == ReportPhase.Failed -> OreText(
                text = ReportCopy.FAILED,
                style = OreTheme.type.caption,
                color = OreTheme.color.status.error.content,
            )
            reasonWire == null -> OreText(
                text = ReportCopy.CHOOSE_REASON,
                style = OreTheme.type.caption,
                color = OreTheme.color.text.tertiary,
            )
        }
        OrePrimaryButton(
            text = if (state.phase == ReportPhase.Failed) ReportCopy.RETRY else ReportCopy.SUBMIT,
            onClick = submit,
            modifier = Modifier.fillMaxWidth(),
            enabled = reasonWire != null && !submitting,
        )
        OreGhostButton(text = ReportCopy.CANCEL, onClick = onDismiss, enabled = !submitting)
    }
}

/**
 * Closing notice for the reporter (`{ kind: "report_closed", outcome }`). Outcome only:
 * actioned / no-action. No sanction detail is accepted as a parameter or rendered.
 */
@Composable
fun ReportClosedNotice(
    outcome: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    val space = OreTheme.space
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.status.info.container)
            .padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.xs),
    ) {
        OreText(text = ReportCopy.CLOSED_TITLE, style = OreTheme.type.bodyStrong, color = OreTheme.color.status.info.content)
        OreText(
            text = when (outcome) {
                ReportCopy.OUTCOME_ACTIONED -> ReportCopy.CLOSED_ACTIONED
                ReportCopy.OUTCOME_NO_ACTION -> ReportCopy.CLOSED_NO_ACTION
                else -> ReportCopy.CLOSED_GENERIC
            },
            style = OreTheme.type.body,
            color = OreTheme.color.status.info.content,
        )
        if (onDismiss != null) OreGhostButton(text = ReportCopy.DONE, onClick = onDismiss)
    }
}

@Composable
private fun ReportOverline(text: String) {
    OreText(text = text, style = OreTheme.type.overline, color = OreTheme.color.text.tertiary, maxLines = 1)
}
