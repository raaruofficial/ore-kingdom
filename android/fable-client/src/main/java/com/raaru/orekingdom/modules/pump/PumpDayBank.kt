package com.raaru.orekingdom.modules.pump

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.tokens.OreTheme
import kotlinx.coroutines.delay

/**
 * Day-bank display state, as supplied by the module read model / entitlement read.
 * [activeRemainingSeconds] non-null = a day is running; the panel only ticks the display down.
 * [unlimited] comes from the entitlement capability (Pump Days included while the role is active).
 */
data class PumpDayBankUi(
    val status: PumpStatus = PumpStatus.Loading,
    val bankedDays: Int = 0,
    val activeRemainingSeconds: Long? = null,
    val firstDayFree: Boolean = false,
    val unlimited: Boolean = false,
)

enum class PumpAtmState { Locked, Unlocked, Included }

data class PumpAtmUi(
    val status: PumpStatus = PumpStatus.Loading,
    val state: PumpAtmState = PumpAtmState.Locked,
)

internal object PumpDayBankCopy {
    const val DAY_BANK_HEADER = "DAY BANK"
    const val ATM_HEADER = "ATM"
    const val BANKED_LABEL = "Banked Pump Days"
    const val UNLIMITED = "Unlimited"
    const val UNLIMITED_NOTE = "Pump Days are unlimited while your role is active."
    const val STANDING = "Days are banked, never expire, and a day is spent only when the user pumps."
    const val ACTIVE_LABEL = "Active day"
    const val ACTIVE_ENDED = "Active day ended"
    const val FIRST_DAY_FREE = "First Pump Day free"
    const val FIRST_DAY_FREE_NOTE = "Your first Pump Day is on the house."
    const val EMPTY = "No Pump Days banked."
    const val PUMP = "Pump"
    const val PUMP_DAY = "Pump Day"
    const val PUMP_DAY_USD = "$10.00"
    const val PUMP_DAY_ORE = "$7.00"
    const val ATM_DAY = "ATM Day unlock"
    const val ATM_DAY_USD = "+$2.50"
    const val ATM_DAY_ORE = "$1.75"
    const val EVERYTHING_DAY = "Everything-day"
    const val EVERYTHING_DAY_USD = "$12.50"
    const val EVERYTHING_DAY_ORE = "$8.75"
    const val ORE_PREFIX = "ORE"
    const val FLAT = "flat"
    const val GET_PUMP_DAY = "Get a Pump Day"
    const val GET_EVERYTHING_DAY = "Get an everything-day"
    const val UNLOCK_ATM = "Unlock ATM for today"
    const val LINKING = "Linking wallets (up to 10)"
    const val NO_CHARGE = "No charge"
    const val ATM_LOCKED = "Locked"
    const val ATM_UNLOCKED = "Unlocked"
    const val ATM_INCLUDED = "Included"
    const val ATM_TODAY = "ATM today"
    const val DAY_BANK_ERROR = "The day bank could not be loaded."
    const val ATM_ERROR = "ATM status could not be loaded."
    const val RETRY = "Retry"
}

/**
 * Day-bank panel. Display only: every control is a stub callback (TODO-CONTRACT) and is disabled
 * with the preview note when [actionsEnabled] is false. No value moves here.
 */
@Composable
fun PumpDayBankPanel(
    state: PumpDayBankUi,
    actionsEnabled: Boolean,
    modifier: Modifier = Modifier,
    onPump: () -> Unit = {},
    onGetPumpDay: () -> Unit = {},
    onGetEverythingDay: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val note = if (actionsEnabled) null else PumpConsoleCopy.PREVIEW_NOTE
    PumpPanel(header = PumpDayBankCopy.DAY_BANK_HEADER, modifier = modifier) {
        when (state.status) {
            PumpStatus.Loading -> PumpPanelSkeleton(rows = 3)
            PumpStatus.Error -> PumpPanelError(PumpDayBankCopy.DAY_BANK_ERROR, onRetry)
            PumpStatus.Ready -> {
                PumpLine(
                    label = PumpDayBankCopy.BANKED_LABEL,
                    value = if (state.unlimited) PumpDayBankCopy.UNLIMITED else state.bankedDays.toString(),
                )
                if (state.unlimited) PumpCaption(PumpDayBankCopy.UNLIMITED_NOTE)
                state.activeRemainingSeconds?.let { ActiveDayCountdown(it) }
                if (state.firstDayFree) {
                    PumpTag(
                        text = PumpDayBankCopy.FIRST_DAY_FREE,
                        content = OreTheme.color.status.info.content,
                        container = OreTheme.color.status.info.container,
                    )
                    PumpCaption(PumpDayBankCopy.FIRST_DAY_FREE_NOTE)
                } else if (!state.unlimited && state.bankedDays <= 0 && state.activeRemainingSeconds == null) {
                    PumpCaption(PumpDayBankCopy.EMPTY)
                }
                PumpCaption(PumpDayBankCopy.STANDING)
                OrePrimaryButton(
                    text = PumpDayBankCopy.PUMP,
                    onClick = onPump,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = actionsEnabled,
                    note = note,
                )
                if (!state.unlimited) {
                    PumpPriceLine(PumpDayBankCopy.PUMP_DAY, "${PumpDayBankCopy.PUMP_DAY_USD} ${PumpDayBankCopy.FLAT}", PumpDayBankCopy.PUMP_DAY_ORE)
                    OreGhostButton(text = PumpDayBankCopy.GET_PUMP_DAY, onClick = onGetPumpDay, enabled = actionsEnabled)
                    PumpPriceLine(PumpDayBankCopy.EVERYTHING_DAY, PumpDayBankCopy.EVERYTHING_DAY_USD, PumpDayBankCopy.EVERYTHING_DAY_ORE)
                    OreGhostButton(text = PumpDayBankCopy.GET_EVERYTHING_DAY, onClick = onGetEverythingDay, enabled = actionsEnabled)
                    if (note != null) PumpCaption(note)
                }
                PumpLine(label = PumpDayBankCopy.LINKING, value = PumpDayBankCopy.NO_CHARGE)
            }
        }
    }
}

/** ATM panel: a status badge (locked / unlocked / included-by-role), not a flow. */
@Composable
fun PumpAtmPanel(
    state: PumpAtmUi,
    actionsEnabled: Boolean,
    modifier: Modifier = Modifier,
    onUnlockAtm: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    PumpPanel(header = PumpDayBankCopy.ATM_HEADER, modifier = modifier) {
        when (state.status) {
            PumpStatus.Loading -> PumpPanelSkeleton(rows = 1)
            PumpStatus.Error -> PumpPanelError(PumpDayBankCopy.ATM_ERROR, onRetry)
            PumpStatus.Ready -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
                ) {
                    OreText(
                        text = PumpDayBankCopy.ATM_TODAY,
                        style = OreTheme.type.body,
                        color = OreTheme.color.text.secondary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    when (state.state) {
                        PumpAtmState.Locked -> PumpTag(
                            PumpDayBankCopy.ATM_LOCKED,
                            OreTheme.color.status.warning.content,
                            OreTheme.color.status.warning.container,
                        )
                        PumpAtmState.Unlocked -> PumpTag(
                            PumpDayBankCopy.ATM_UNLOCKED,
                            OreTheme.color.status.info.content,
                            OreTheme.color.status.info.container,
                        )
                        PumpAtmState.Included -> PumpTag(
                            PumpDayBankCopy.ATM_INCLUDED,
                            OreTheme.color.accent.onSubtle,
                            OreTheme.color.accent.subtle,
                        )
                    }
                }
                if (state.state == PumpAtmState.Locked) {
                    PumpPriceLine(PumpDayBankCopy.ATM_DAY, PumpDayBankCopy.ATM_DAY_USD, PumpDayBankCopy.ATM_DAY_ORE)
                    OreGhostButton(text = PumpDayBankCopy.UNLOCK_ATM, onClick = onUnlockAtm, enabled = actionsEnabled)
                    if (!actionsEnabled) PumpCaption(PumpConsoleCopy.PREVIEW_NOTE)
                }
            }
        }
    }
}

/** Ticks the supplied remaining time down for display only; re-seeds whenever the read changes. */
@Composable
private fun ActiveDayCountdown(remainingSeconds: Long) {
    var remaining by remember(remainingSeconds) { mutableStateOf(remainingSeconds.coerceAtLeast(0L)) }
    LaunchedEffect(remainingSeconds) {
        while (remaining > 0L) {
            delay(1_000L)
            remaining -= 1L
        }
    }
    if (remaining > 0L) {
        val h = remaining / 3600
        val m = (remaining % 3600) / 60
        val s = remaining % 60
        PumpLine(
            label = PumpDayBankCopy.ACTIVE_LABEL,
            value = "%02d:%02d:%02d".format(h, m, s),
        )
    } else {
        PumpCaption(PumpDayBankCopy.ACTIVE_ENDED)
    }
}

@Composable
private fun PumpPanel(header: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(OreTheme.space.md),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
    ) {
        OreText(text = header, style = OreTheme.type.overline, color = OreTheme.color.text.tertiary, maxLines = 1)
        content()
    }
}

@Composable
private fun PumpLine(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
    ) {
        OreText(
            text = label,
            style = OreTheme.type.body,
            color = OreTheme.color.text.secondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        OreText(text = value, style = OreTheme.type.numeric, color = OreTheme.color.text.primary, maxLines = 1)
    }
}

/** Priced display: fixed strings of record only. Nothing is computed or converted. */
@Composable
private fun PumpPriceLine(label: String, usd: String, ore: String) {
    Column(verticalArrangement = Arrangement.spacedBy(OreTheme.space.xxs)) {
        PumpLine(label = label, value = usd)
        OreText(
            text = "${PumpDayBankCopy.ORE_PREFIX} $ore",
            style = OreTheme.type.caption,
            color = OreTheme.color.text.tertiary,
            maxLines = 1,
        )
    }
}

@Composable
private fun PumpCaption(text: String) {
    OreText(text = text, style = OreTheme.type.caption, color = OreTheme.color.text.secondary)
}

@Composable
private fun PumpTag(text: String, content: Color, container: Color) {
    Box(
        modifier = Modifier
            .background(container)
            .padding(horizontal = OreTheme.space.sm, vertical = OreTheme.space.xxs),
    ) {
        OreText(text = text, style = OreTheme.type.badge, color = content, maxLines = 1)
    }
}

@Composable
private fun PumpPanelError(text: String, onRetry: () -> Unit) {
    OreText(text = text, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
    OreGhostButton(text = PumpDayBankCopy.RETRY, onClick = onRetry)
}

@Composable
private fun PumpPanelSkeleton(rows: Int) {
    repeat(rows) { index ->
        Box(
            Modifier
                .fillMaxWidth(if (index % 2 == 0) 0.7f else 0.45f)
                .height(OreTheme.space.md)
                .background(OreTheme.color.surface.highlight),
        )
    }
}
