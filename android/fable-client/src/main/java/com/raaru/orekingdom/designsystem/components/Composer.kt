package com.raaru.orekingdom.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import com.raaru.orekingdom.designsystem.tokens.OreTheme
import kotlinx.coroutines.delay

private const val ComposerPlaceholder = "Message"
private const val ComposerDisabledNote = "This channel is read-only"
private const val ComposerUpsellNote = "Posting needs a pass. Reading is free forever."
private const val ComposerUpsellAction = "View passes"
private const val ComposerSlowmodePrefix = "Slowmode · send in "
private const val ComposerMaxLines = 5

/** Composer states; chosen by the caller from the entitlement read and channel config. */
@Immutable
sealed interface ComposerState {
    object Enabled : ComposerState
    data class Disabled(val note: String = ComposerDisabledNote) : ComposerState
    data class Upsell(
        val note: String = ComposerUpsellNote,
        val actionLabel: String = ComposerUpsellAction,
    ) : ComposerState
    data class Slowmode(val remainingSeconds: Int) : ComposerState
}

/**
 * Message composer. [onUpsellClick] is a navigation callback only; no transaction lives here.
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    state: ComposerState,
    modifier: Modifier = Modifier,
    placeholder: String = ComposerPlaceholder,
    onUpsellClick: () -> Unit = {},
    onSlowmodeElapsed: () -> Unit = {},
) {
    val colors = OreTheme.color
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface.raised)
            .padding(horizontal = OreTheme.space.md, vertical = OreTheme.space.xs),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) {
        when (state) {
            is ComposerState.Upsell -> ComposerNoteRow(note = state.note) {
                OrePrimaryButton(text = state.actionLabel, onClick = onUpsellClick)
            }
            is ComposerState.Disabled -> ComposerNoteRow(note = state.note, trailing = null)
            is ComposerState.Slowmode -> {
                val remaining = rememberCountdown(state.remainingSeconds, onSlowmodeElapsed)
                if (remaining > 0) {
                    OreText(
                        text = ComposerSlowmodePrefix + formatCountdown(remaining),
                        style = OreTheme.type.numeric,
                        color = colors.status.warning.content,
                        maxLines = 1,
                    )
                }
                ComposerInputRow(
                    value = value,
                    onValueChange = onValueChange,
                    onSend = onSend,
                    placeholder = placeholder,
                    canSend = remaining == 0 && value.isNotBlank(),
                )
            }
            ComposerState.Enabled -> ComposerInputRow(
                value = value,
                onValueChange = onValueChange,
                onSend = onSend,
                placeholder = placeholder,
                canSend = value.isNotBlank(),
            )
        }
    }
}

@Composable
private fun ComposerInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    placeholder: String,
    canSend: Boolean,
) {
    val colors = OreTheme.color
    val shape = OreTheme.shape.md
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = OreTheme.size.composerMinHeight),
        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .background(colors.surface.sunken)
                .border(
                    OreTheme.size.strokeHairline,
                    if (focused) colors.border.focus else colors.border.subtle,
                    shape,
                )
                .padding(horizontal = OreTheme.space.md, vertical = OreTheme.space.sm),
            textStyle = OreTheme.type.body.copy(color = colors.text.primary),
            cursorBrush = SolidColor(colors.accent.primary),
            maxLines = ComposerMaxLines,
            interactionSource = interaction,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        OreText(
                            text = placeholder,
                            style = OreTheme.type.body,
                            color = colors.text.tertiary,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
        OrePrimaryButton(
            text = OreComponentCopy.Send,
            onClick = onSend,
            enabled = canSend,
        )
    }
}

@Composable
private fun ComposerNoteRow(
    note: String,
    trailing: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = OreTheme.size.composerMinHeight),
        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OreText(
            text = note,
            style = OreTheme.type.caption,
            color = if (trailing != null) OreTheme.color.text.secondary else OreTheme.color.text.disabled,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        trailing?.invoke()
    }
}

@Composable
private fun rememberCountdown(seconds: Int, onElapsed: () -> Unit): Int {
    val tickMillis = OreTheme.motion.duration.countdownTick.toLong()
    var remaining by remember(seconds) { mutableStateOf(seconds.coerceAtLeast(0)) }
    val elapsed by rememberUpdatedState(onElapsed)
    LaunchedEffect(seconds, tickMillis) {
        while (remaining > 0) {
            delay(tickMillis)
            remaining -= 1
        }
        elapsed()
    }
    return remaining
}

internal fun formatCountdown(seconds: Int): String =
    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
