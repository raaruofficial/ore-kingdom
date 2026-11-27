package com.raaru.orekingdom.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.raaru.orekingdom.designsystem.tokens.OreTheme
import com.raaru.orekingdom.designsystem.tokens.OreTone

private const val AvailabilityOpen = "OPEN"
private const val AvailabilityLimited = "LIMITED"
private const val AvailabilityClosed = "CLOSED"

/** Availability state shown on an [AvailabilityCard]. */
enum class OreAvailability { Open, Limited, Closed }

/** Informational card in the info status tone. */
@Composable
fun InfoCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val tone = OreTheme.color.status.info
    OreCardFrame(container = tone.container, borderColor = null, modifier = modifier) {
        OreCardText(title = title, body = body, titleColor = tone.content)
        action?.invoke()
    }
}

/** Availability card; the status chip and tone follow [availability]. */
@Composable
fun AvailabilityCard(
    title: String,
    body: String,
    availability: OreAvailability,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val tone: OreTone = when (availability) {
        OreAvailability.Open -> OreTheme.color.status.info
        OreAvailability.Limited -> OreTheme.color.status.warning
        OreAvailability.Closed -> OreTheme.color.status.error
    }
    val label = when (availability) {
        OreAvailability.Open -> AvailabilityOpen
        OreAvailability.Limited -> AvailabilityLimited
        OreAvailability.Closed -> AvailabilityClosed
    }
    OreCardFrame(
        container = OreTheme.color.surface.raised,
        borderColor = tone.content,
        modifier = modifier,
    ) {
        OreChip(text = label, content = tone.content, container = tone.container)
        OreCardText(title = title, body = body, titleColor = OreTheme.color.text.primary)
        action?.invoke()
    }
}

/**
 * Sponsored card. The [SponsoredLabel] is always rendered; no parameter can remove it.
 * Uses the sponsored tone only — never the native-pin treatment.
 */
@Composable
fun SponsoredCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    OreCardFrame(
        container = OreTheme.color.label.sponsored.container,
        borderColor = OreTheme.color.border.strong,
        modifier = modifier,
    ) {
        SponsoredLabel()
        OreCardText(title = title, body = body, titleColor = OreTheme.color.text.primary)
    }
}

@Composable
private fun OreCardFrame(
    container: Color,
    borderColor: Color?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = OreTheme.shape.lg
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .then(
                if (borderColor != null) {
                    Modifier.border(OreTheme.size.strokeHairline, borderColor, shape)
                } else {
                    Modifier
                },
            )
            .padding(OreTheme.space.lg),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
        content = content,
    )
}

@Composable
private fun OreCardText(title: String, body: String, titleColor: Color) {
    OreText(
        text = title,
        style = OreTheme.type.titleSmall,
        color = titleColor,
        maxLines = 2,
    )
    OreText(
        text = body,
        style = OreTheme.type.body,
        color = OreTheme.color.text.secondary,
    )
}
