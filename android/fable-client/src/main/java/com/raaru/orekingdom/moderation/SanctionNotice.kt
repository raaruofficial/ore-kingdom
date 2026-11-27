package com.raaru.orekingdom.moderation

import androidx.compose.foundation.background
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

/** Contract sanction `rung` values, verbatim in [wire]. */
enum class SanctionRung(val wire: String, val label: String) {
    ContentRemoved("content_removed", "Content removed"),
    Slowmode("slowmode", "Slowmode"),
    Mute("mute", "Mute"),
    Suspension("suspension", "Suspension"),
    Ban("ban", "Ban");

    companion object {
        fun of(wire: String): SanctionRung? = values().firstOrNull { it.wire == wire }
    }
}

/** Contract `appeal.state` values, verbatim in [wire]. */
enum class AppealState(val wire: String) {
    None("none"), Pending("pending"), Upheld("upheld"), Reduced("reduced"), Overturned("overturned");

    companion object {
        fun of(wire: String): AppealState = values().firstOrNull { it.wire == wire } ?: None
    }
}

/** Local phase of the appeal form, driven by the host. */
enum class AppealFormPhase { Editing, Submitting, Failed }

/**
 * Recipient view of one sanction. Mirrors the contract Sanction notice shape. There is no
 * reporter field here by design: the reporter's identity is never held or rendered.
 * [sanctionId] is required by the appeal submission shape; when null the appeal entry is hidden.
 */
data class SanctionNoticeUi(
    val sanctionId: String?,
    val rung: String,
    val reasonCategory: String,
    val durationSec: Long? = null,
    val appeal: AppealState = AppealState.None,
    val formPhase: AppealFormPhase = AppealFormPhase.Editing,
)

enum class SanctionNoticesStatus { Loading, Ready, Error }

data class SanctionNoticesUiState(
    val status: SanctionNoticesStatus = SanctionNoticesStatus.Loading,
    val notices: List<SanctionNoticeUi> = emptyList(),
)

/** Contract appeal submission shape: `{ sanction_id, statement }`. One per sanction. */
data class AppealSubmission(val sanctionId: String, val statement: String)

internal object SanctionCopy {
    const val TITLE = "Moderation notices"
    const val EMPTY = "No moderation notices."
    const val ERROR = "Moderation notices could not be loaded."
    const val RETRY = "Retry"
    const val RUNG_UNKNOWN = "Moderation action"
    const val REASON = "Reason"
    const val REASON_UNKNOWN = "Not specified"
    const val DURATION = "Duration"
    const val APPEAL_HEADER = "APPEAL"
    const val APPEAL_INTRO = "You can appeal this once. A different moderator reviews it."
    const val APPEAL_OPEN = "Appeal"
    const val APPEAL_PLACEHOLDER = "Explain why this should be reviewed."
    const val APPEAL_SUBMIT = "Submit appeal"
    const val APPEAL_SUBMITTING = "Sending your appeal…"
    const val APPEAL_FAILED = "Your appeal could not be sent. Your statement is kept — try again."
    const val APPEAL_RETRY = "Try again"
    const val APPEAL_CANCEL = "Cancel"
    const val APPEAL_PENDING = "Appeal pending"
    const val APPEAL_UPHELD = "Appeal decided: upheld"
    const val APPEAL_REDUCED = "Appeal decided: reduced"
    const val APPEAL_OVERTURNED = "Appeal decided: overturned"
    const val STATEMENT_MAX = 2000
    const val SKELETON_CARDS = 2
}

/** Display formatting of the supplied `duration_sec` only; no end time is computed. */
internal fun sanctionDurationText(durationSec: Long): String {
    val d = durationSec / 86_400
    val h = (durationSec % 86_400) / 3_600
    val m = (durationSec % 3_600) / 60
    val s = durationSec % 60
    val parts = listOfNotNull(
        if (d > 0) "${d}d" else null,
        if (h > 0) "${h}h" else null,
        if (m > 0) "${m}m" else null,
        if (s > 0 && d == 0L && h == 0L) "${s}s" else null,
    )
    return if (parts.isEmpty()) "0s" else parts.joinToString(" ")
}

/** Sanction notices for the sanctioned user. [onSubmitAppeal] is a stub callback (TODO-CONTRACT). */
@Composable
fun SanctionNoticeScreen(
    state: SanctionNoticesUiState,
    onSubmitAppeal: (AppealSubmission) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    val space = OreTheme.space
    Column(modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(OreTheme.color.surface.raised)
                .padding(horizontal = space.md, vertical = space.sm),
        ) {
            OreText(text = SanctionCopy.TITLE, style = OreTheme.type.titleSmall, color = OreTheme.color.text.primary, maxLines = 1)
        }
        when {
            state.status == SanctionNoticesStatus.Loading && state.notices.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth().padding(space.md),
                verticalArrangement = Arrangement.spacedBy(space.sm),
            ) {
                repeat(SanctionCopy.SKELETON_CARDS) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(OreTheme.size.touchTarget * 2)
                            .background(OreTheme.color.surface.highlight),
                    )
                }
            }
            state.status == SanctionNoticesStatus.Error && state.notices.isEmpty() -> SanctionCard(Modifier.padding(space.md)) {
                OreText(text = SanctionCopy.ERROR, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
                OreGhostButton(text = SanctionCopy.RETRY, onClick = onRetry)
            }
            state.notices.isEmpty() -> SanctionCard(Modifier.padding(space.md)) {
                OreText(text = SanctionCopy.EMPTY, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(space.md),
                verticalArrangement = Arrangement.spacedBy(space.sm),
            ) {
                items(state.notices) { notice -> SanctionNoticeCard(notice = notice, onSubmitAppeal = onSubmitAppeal) }
            }
        }
    }
}

/**
 * One sanction notice: rung, reason category, duration where timed, appeal state. The appeal form
 * renders only while the appeal state is `none` (one appeal per sanction); afterwards only the
 * appeal state shows.
 */
@Composable
fun SanctionNoticeCard(
    notice: SanctionNoticeUi,
    onSubmitAppeal: (AppealSubmission) -> Unit,
    modifier: Modifier = Modifier,
) {
    val space = OreTheme.space
    val formKey = notice.sanctionId ?: notice.rung
    var formOpen by rememberSaveable(formKey) { mutableStateOf(false) }
    var statement by rememberSaveable(formKey) { mutableStateOf("") }
    val submitting = notice.formPhase == AppealFormPhase.Submitting
    val failed = notice.formPhase == AppealFormPhase.Failed

    SanctionCard(modifier) {
        OreText(
            text = SanctionRung.of(notice.rung)?.label ?: SanctionCopy.RUNG_UNKNOWN,
            style = OreTheme.type.bodyStrong,
            color = OreTheme.color.status.warning.content,
            maxLines = 1,
        )
        SanctionLine(
            label = SanctionCopy.REASON,
            value = ReportReason.values().firstOrNull { it.wire == notice.reasonCategory }?.label
                ?: SanctionCopy.REASON_UNKNOWN,
        )
        if (notice.durationSec != null && notice.durationSec > 0) {
            SanctionLine(label = SanctionCopy.DURATION, value = sanctionDurationText(notice.durationSec))
        }
        OreText(text = SanctionCopy.APPEAL_HEADER, style = OreTheme.type.overline, color = OreTheme.color.text.tertiary, maxLines = 1)
        when (notice.appeal) {
            AppealState.Pending -> AppealStateLine(SanctionCopy.APPEAL_PENDING)
            AppealState.Upheld -> AppealStateLine(SanctionCopy.APPEAL_UPHELD)
            AppealState.Reduced -> AppealStateLine(SanctionCopy.APPEAL_REDUCED)
            AppealState.Overturned -> AppealStateLine(SanctionCopy.APPEAL_OVERTURNED)
            AppealState.None -> if (notice.sanctionId != null) {
                OreText(text = SanctionCopy.APPEAL_INTRO, style = OreTheme.type.caption, color = OreTheme.color.text.secondary)
                if (!formOpen && !submitting && !failed) {
                    OreGhostButton(text = SanctionCopy.APPEAL_OPEN, onClick = { formOpen = true })
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = OreTheme.size.composerMinHeight * 2)
                            .background(OreTheme.color.surface.sunken)
                            .padding(space.sm),
                    ) {
                        if (statement.isEmpty()) {
                            OreText(
                                text = SanctionCopy.APPEAL_PLACEHOLDER,
                                style = OreTheme.type.body,
                                color = OreTheme.color.text.tertiary,
                            )
                        }
                        BasicTextField(
                            value = statement,
                            onValueChange = { if (it.length <= SanctionCopy.STATEMENT_MAX) statement = it },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !submitting,
                            textStyle = OreTheme.type.body.copy(color = OreTheme.color.text.primary),
                            cursorBrush = SolidColor(OreTheme.color.accent.primary),
                        )
                    }
                    if (submitting) {
                        OreText(text = SanctionCopy.APPEAL_SUBMITTING, style = OreTheme.type.caption, color = OreTheme.color.text.secondary)
                    } else if (failed) {
                        OreText(text = SanctionCopy.APPEAL_FAILED, style = OreTheme.type.caption, color = OreTheme.color.status.error.content)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(space.sm),
                    ) {
                        OrePrimaryButton(
                            text = if (failed) SanctionCopy.APPEAL_RETRY else SanctionCopy.APPEAL_SUBMIT,
                            onClick = { onSubmitAppeal(AppealSubmission(notice.sanctionId, statement.trim())) },
                            modifier = Modifier.weight(1f),
                            enabled = statement.isNotBlank() && !submitting,
                        )
                        OreGhostButton(text = SanctionCopy.APPEAL_CANCEL, onClick = { formOpen = false }, enabled = !submitting && !failed)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppealStateLine(text: String) {
    OreText(text = text, style = OreTheme.type.body, color = OreTheme.color.text.primary, maxLines = 1)
}

@Composable
private fun SanctionLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm)) {
        OreText(
            text = label,
            style = OreTheme.type.caption,
            color = OreTheme.color.text.tertiary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        OreText(text = value, style = OreTheme.type.body, color = OreTheme.color.text.primary, maxLines = 1)
    }
}

@Composable
private fun SanctionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(OreTheme.space.md),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) { content() }
}
