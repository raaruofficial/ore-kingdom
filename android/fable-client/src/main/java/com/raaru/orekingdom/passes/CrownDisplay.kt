package com.raaru.orekingdom.passes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.CrownBadge
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme

enum class CrownStatus { Loading, Ready, Error }

/**
 * Crown display state. [holderName] and [month] are host-supplied display strings, rendered
 * verbatim (TODO-CONTRACT: the contract carries no Crown read). Null holder = no current holder.
 * The Crown has no price and no action: this state carries neither.
 */
data class CrownDisplayUi(
    val status: CrownStatus = CrownStatus.Loading,
    val holderName: String? = null,
    val month: String? = null,
)

private object CrownCopy {
    const val HEADER = "THE CROWN"
    const val HOLDER = "Current holder"
    const val MONTH = "Month"
    const val HONOUR = "A monthly status honour."
    const val EMPTY = "No Crown holder to show right now."
    const val ERROR = "The Crown could not be loaded."
    const val RETRY = "Retry"
}

/**
 * Crown profile display: the current holder and the month held. Display only — no price,
 * no purchase surface, no action control. The only control is the reload on the error state.
 */
@Composable
fun CrownDisplay(
    state: CrownDisplayUi,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    val space = OreTheme.space
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.xs),
    ) {
        OreText(text = CrownCopy.HEADER, style = OreTheme.type.overline, color = OreTheme.color.text.tertiary, maxLines = 1)
        when {
            state.status == CrownStatus.Loading -> {
                Box(Modifier.fillMaxWidth(0.5f).height(space.md).background(OreTheme.color.surface.highlight))
                Box(Modifier.fillMaxWidth(0.3f).height(space.md).background(OreTheme.color.surface.highlight))
            }
            state.status == CrownStatus.Error -> {
                OreText(text = CrownCopy.ERROR, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
                OreGhostButton(text = CrownCopy.RETRY, onClick = onRetry)
            }
            state.holderName == null -> {
                OreText(text = CrownCopy.EMPTY, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
                OreText(text = CrownCopy.HONOUR, style = OreTheme.type.caption, color = OreTheme.color.text.tertiary)
            }
            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OreTheme.color.label.crown.container)
                        .padding(space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(space.sm),
                ) {
                    CrownBadge()
                    OreText(
                        text = state.holderName,
                        style = OreTheme.type.bodyStrong,
                        color = OreTheme.color.label.crown.content,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                }
                CrownLine(CrownCopy.HOLDER, state.holderName)
                if (state.month != null) CrownLine(CrownCopy.MONTH, state.month)
                OreText(text = CrownCopy.HONOUR, style = OreTheme.type.caption, color = OreTheme.color.text.tertiary)
            }
        }
    }
}

@Composable
private fun CrownLine(label: String, value: String) {
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
