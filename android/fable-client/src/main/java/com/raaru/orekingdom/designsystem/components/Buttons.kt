package com.raaru.orekingdom.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.raaru.orekingdom.designsystem.tokens.OreTheme

/**
 * Primary action. When [enabled] is false and [disabledNote] is supplied, the note renders
 * under the control (disabled-with-note state, e.g. a module preview note).
 */
@Composable
fun OrePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledNote: String? = null,
) {
    val colors = OreTheme.color
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val container by animateColorAsState(
        targetValue = when {
            !enabled -> colors.surface.highlight
            pressed -> colors.accent.primaryPressed
            else -> colors.accent.primary
        },
        animationSpec = tween(
            durationMillis = OreTheme.motion.duration.fast,
            easing = OreTheme.motion.easing.standard,
        ),
        label = "orePrimaryButtonContainer",
    )
    OreButtonFrame(
        text = text,
        onClick = onClick,
        enabled = enabled,
        disabledNote = disabledNote,
        container = container,
        content = if (enabled) colors.accent.onPrimary else colors.text.disabled,
        borderColor = null,
        interaction = interaction,
        modifier = modifier,
    )
}

/** Secondary action. Same disabled-with-note behaviour as [OrePrimaryButton]. */
@Composable
fun OreGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledNote: String? = null,
) {
    val colors = OreTheme.color
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedFraction by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = tween(
            durationMillis = OreTheme.motion.duration.fast,
            easing = OreTheme.motion.easing.standard,
        ),
        label = "oreGhostButtonPressed",
    )
    OreButtonFrame(
        text = text,
        onClick = onClick,
        enabled = enabled,
        disabledNote = disabledNote,
        container = colors.surface.highlight.copy(alpha = pressedFraction),
        content = if (enabled) colors.text.primary else colors.text.disabled,
        borderColor = if (enabled) colors.border.strong else colors.border.subtle,
        interaction = interaction,
        modifier = modifier,
    )
}

@Composable
private fun OreButtonFrame(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    disabledNote: String?,
    container: Color,
    content: Color,
    borderColor: Color?,
    interaction: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    val shape = OreTheme.shape.sm
    val note = if (enabled) null else disabledNote
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(
                    minWidth = OreTheme.size.touchTarget,
                    minHeight = OreTheme.size.buttonHeight,
                )
                .clip(shape)
                .background(container)
                .then(
                    if (borderColor != null) {
                        Modifier.border(OreTheme.size.strokeHairline, borderColor, shape)
                    } else {
                        Modifier
                    },
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics { if (note != null) stateDescription = note }
                .padding(horizontal = OreTheme.space.lg),
            contentAlignment = Alignment.Center,
        ) {
            OreText(
                text = text,
                style = OreTheme.type.label,
                color = content,
                maxLines = 1,
            )
        }
        if (note != null) {
            OreText(
                text = note,
                style = OreTheme.type.caption,
                color = OreTheme.color.text.tertiary,
            )
        }
    }
}
