package com.raaru.orekingdom.designsystem.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import com.raaru.orekingdom.designsystem.tokens.OreTheme

/*
 * Both wrappers own the full state treatment, so the inner bubble is given the neutral
 * delivery value to avoid a duplicated indicator. Neither can render without its state mark.
 */

/** Own message awaiting its ack: dimmed bubble plus a pulsing sending indicator. */
@Composable
fun SendingMessage(
    authorName: String,
    authorRole: OreRole,
    body: String,
    timestamp: String,
    modifier: Modifier = Modifier,
    founding: Boolean = false,
    moderator: Boolean = false,
    crown: Boolean = false,
) {
    val indicator = OreTheme.color.message.sending
    val transition = rememberInfiniteTransition(label = "oreSendingPulse")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = OreTheme.alpha.sending,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = OreTheme.motion.duration.pulse,
                easing = OreTheme.motion.easing.standard,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "oreSendingPulseAlpha",
    )
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) {
        MessageBubble(
            authorName = authorName,
            authorRole = authorRole,
            body = body,
            timestamp = timestamp,
            modifier = Modifier.alpha(OreTheme.alpha.sending),
            own = true,
            founding = founding,
            moderator = moderator,
            crown = crown,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(OreTheme.space.sm)
                    .alpha(pulse)
                    .clip(OreTheme.shape.pill)
                    .background(indicator),
            )
            OreText(
                text = OreComponentCopy.Sending,
                style = OreTheme.type.caption,
                color = indicator,
                maxLines = 1,
            )
        }
    }
}

/** Own message held in the offline outbox: queued-tone frame plus the queued note. */
@Composable
fun QueuedMessage(
    authorName: String,
    authorRole: OreRole,
    body: String,
    timestamp: String,
    modifier: Modifier = Modifier,
    founding: Boolean = false,
    moderator: Boolean = false,
    crown: Boolean = false,
) {
    val tone = OreTheme.color.message.queued
    val shape = OreTheme.shape.md
    Column(
        modifier = modifier
            .clip(shape)
            .background(tone.container)
            .border(OreTheme.size.strokeHairline, tone.content, shape)
            .padding(OreTheme.space.sm),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) {
        MessageBubble(
            authorName = authorName,
            authorRole = authorRole,
            body = body,
            timestamp = timestamp,
            own = true,
            founding = founding,
            moderator = moderator,
            crown = crown,
        )
        OreChip(
            text = OreComponentCopy.Queued,
            content = tone.content,
            container = tone.container,
            borderColor = tone.content,
        )
    }
}
