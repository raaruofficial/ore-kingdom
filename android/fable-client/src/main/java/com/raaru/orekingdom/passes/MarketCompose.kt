package com.raaru.orekingdom.passes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import com.raaru.orekingdom.designsystem.components.CommercialLabel
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme

/** Phase driven by the host: Submitting = loading, Failed = error (fixed copy, draft kept). */
enum class MarketComposePhase { Editing, Submitting, Failed, Submitted }

internal object MarketComposeCopy {
    const val HEADER = "COMMERCIAL POST"
    const val PRICE_LABEL = "Commercial post"
    const val PRICE = "$25"
    const val FLAT = "flat"
    const val LABEL_NOTE = "Every commercial post carries the commercial label permanently."
    const val PLACEHOLDER = "Write your commercial post."
    const val PREVIEW = "PREVIEW"
    const val SUBMIT = "Post to market"
    const val SUBMITTING = "Submitting…"
    const val FAILED = "Your post could not be submitted. Your draft is kept — try again."
    const val RETRY = "Try again"
    const val SUBMITTED = "Your commercial post was submitted."
    const val NEW_POST = "Write another"
    const val NOT_ENTITLED = "Commercial posts are not part of your current access."
    const val BODY_MAX = 2000
}

/**
 * #market commercial compose entry: flat display price and the permanent commercial label on the
 * composed post. [canPostCommercial] is `capabilities.commercialPost`; when false the entry is
 * disabled with a note. [onSubmit] is a stub callback (TODO-CONTRACT): no value moves here.
 */
@Composable
fun MarketComposeEntry(
    canPostCommercial: Boolean,
    onSubmit: (body: String) -> Unit,
    modifier: Modifier = Modifier,
    phase: MarketComposePhase = MarketComposePhase.Editing,
    onReset: () -> Unit = {},
) {
    val space = OreTheme.space
    var draft by rememberSaveable { mutableStateOf("") }
    val submitting = phase == MarketComposePhase.Submitting
    val editable = canPostCommercial && !submitting

    Column(
        modifier = modifier.fillMaxWidth().background(OreTheme.color.surface.raised).padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
            OreText(
                text = MarketComposeCopy.HEADER,
                style = OreTheme.type.overline,
                color = OreTheme.color.text.tertiary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            CommercialLabel()
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.sm)) {
            OreText(
                text = MarketComposeCopy.PRICE_LABEL,
                style = OreTheme.type.body,
                color = OreTheme.color.text.secondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            OreText(
                text = "${MarketComposeCopy.PRICE} ${MarketComposeCopy.FLAT}",
                style = OreTheme.type.numeric,
                color = OreTheme.color.text.primary,
                maxLines = 1,
            )
        }
        OreText(text = MarketComposeCopy.LABEL_NOTE, style = OreTheme.type.caption, color = OreTheme.color.text.secondary)
        if (phase == MarketComposePhase.Submitted) {
            OreText(text = MarketComposeCopy.SUBMITTED, style = OreTheme.type.body, color = OreTheme.color.text.primary)
            OreGhostButton(text = MarketComposeCopy.NEW_POST, onClick = { draft = ""; onReset() })
            return@Column
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = OreTheme.size.composerMinHeight * 2)
                .background(OreTheme.color.surface.sunken)
                .padding(space.sm),
        ) {
            if (draft.isEmpty()) {
                OreText(
                    text = MarketComposeCopy.PLACEHOLDER,
                    style = OreTheme.type.body,
                    color = if (canPostCommercial) OreTheme.color.text.tertiary else OreTheme.color.text.disabled,
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { if (it.length <= MarketComposeCopy.BODY_MAX) draft = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = editable,
                textStyle = OreTheme.type.body.copy(color = OreTheme.color.text.primary),
                cursorBrush = SolidColor(OreTheme.color.accent.primary),
            )
        }
        if (draft.isNotBlank()) {
            OreText(text = MarketComposeCopy.PREVIEW, style = OreTheme.type.overline, color = OreTheme.color.text.tertiary, maxLines = 1)
            Column(
                modifier = Modifier.fillMaxWidth().background(OreTheme.color.message.bubble).padding(space.sm),
                verticalArrangement = Arrangement.spacedBy(space.xs),
            ) {
                CommercialLabel()
                OreText(text = draft.trim(), style = OreTheme.type.body, color = OreTheme.color.text.primary)
            }
        }
        when {
            submitting -> OreText(text = MarketComposeCopy.SUBMITTING, style = OreTheme.type.caption, color = OreTheme.color.text.secondary)
            phase == MarketComposePhase.Failed ->
                OreText(text = MarketComposeCopy.FAILED, style = OreTheme.type.caption, color = OreTheme.color.status.error.content)
        }
        OrePrimaryButton(
            text = if (phase == MarketComposePhase.Failed) MarketComposeCopy.RETRY else MarketComposeCopy.SUBMIT,
            onClick = { onSubmit(draft.trim()) },
            modifier = Modifier.fillMaxWidth(),
            enabled = editable && draft.isNotBlank(),
            note = if (canPostCommercial) null else MarketComposeCopy.NOT_ENTITLED,
        )
    }
}
