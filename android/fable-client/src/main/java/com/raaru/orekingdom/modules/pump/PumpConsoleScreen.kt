package com.raaru.orekingdom.modules.pump

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.components.StaleBadge
import com.raaru.orekingdom.designsystem.tokens.OreTheme
import com.raaru.orekingdom.shell.ModuleFlag

enum class PumpStatus { Loading, Ready, Error }

/** One linked wallet, exactly as the module read model supplies it. Nothing is computed here. */
data class PumpWalletUi(
    val addressShort: String,
    val balanceText: String,
    val tankStateText: String,
)

/** Console display state. The module renders state; it never moves value. */
data class PumpConsoleUi(
    val status: PumpStatus = PumpStatus.Loading,
    val wallets: List<PumpWalletUi> = emptyList(),
    val stale: Boolean = false,
)

internal object PumpConsoleCopy {
    const val TITLE = "THE PUMP"
    const val FLEET_HEADER = "FLEET"
    const val MAX_WALLETS = 10
    const val PREVIEW_NOTE = "Preview — actions are not available yet."
    const val PREVIEW_TAG = "Preview"
    const val LINK_WALLET = "Link a wallet"
    const val LINK_NO_CHARGE = "Linking wallets: no charge, up to 10 wallets."
    const val FLEET_FULL = "Fleet is full: 10 of 10 wallets linked."
    const val EMPTY_TITLE = "No wallets linked"
    const val EMPTY_BODY = "Link a wallet to see its balance and tank state here."
    const val ERROR = "THE PUMP could not be loaded."
    const val RETRY = "Retry"
    const val BALANCE = "Balance"
    const val TANK = "Tank"
    const val SKELETON_CARDS = 3
}

/**
 * THE PUMP console host + fleet view. [flag] Off composes nothing (the shell handles absence);
 * Preview renders every action control disabled with the preview note; On renders the full surface.
 * [panels] is the slot for the day-bank and ATM panels; it receives whether actions are enabled.
 * [onLinkWallet] is a stub callback (TODO-CONTRACT): no linking, signing or value logic lives here.
 */
@Composable
fun PumpConsoleScreen(
    state: PumpConsoleUi,
    flag: ModuleFlag,
    modifier: Modifier = Modifier,
    onLinkWallet: () -> Unit = {},
    onRetry: () -> Unit = {},
    panels: @Composable (actionsEnabled: Boolean) -> Unit = {},
) {
    if (flag == ModuleFlag.Off) return
    val space = OreTheme.space
    val actionsEnabled = flag == ModuleFlag.On
    val wallets = state.wallets.take(PumpConsoleCopy.MAX_WALLETS)

    Column(modifier = modifier.fillMaxSize().background(OreTheme.color.surface.base)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OreTheme.color.surface.raised)
                .padding(horizontal = space.md, vertical = space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            OreText(
                text = PumpConsoleCopy.TITLE,
                style = OreTheme.type.titleSmall,
                color = OreTheme.color.text.primary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (state.stale) StaleBadge()
            if (!actionsEnabled) {
                OreText(
                    text = PumpConsoleCopy.PREVIEW_TAG,
                    style = OreTheme.type.overline,
                    color = OreTheme.color.status.info.content,
                    maxLines = 1,
                )
            }
        }
        when {
            state.status == PumpStatus.Loading && wallets.isEmpty() -> PumpSkeleton()
            state.status == PumpStatus.Error && wallets.isEmpty() -> PumpCard(Modifier.padding(space.md)) {
                OreText(text = PumpConsoleCopy.ERROR, style = OreTheme.type.body, color = OreTheme.color.text.secondary)
                OreGhostButton(text = PumpConsoleCopy.RETRY, onClick = onRetry)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(space.md),
                verticalArrangement = Arrangement.spacedBy(space.sm),
            ) {
                if (!actionsEnabled) {
                    item(key = "preview_note") {
                        OreText(
                            text = PumpConsoleCopy.PREVIEW_NOTE,
                            style = OreTheme.type.caption,
                            color = OreTheme.color.status.info.content,
                        )
                    }
                }
                item(key = "panels") {
                    Column(verticalArrangement = Arrangement.spacedBy(space.sm)) { panels(actionsEnabled) }
                }
                item(key = "fleet_header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OreText(
                            text = PumpConsoleCopy.FLEET_HEADER,
                            style = OreTheme.type.overline,
                            color = OreTheme.color.text.tertiary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        OreText(
                            text = "${wallets.size} / ${PumpConsoleCopy.MAX_WALLETS}",
                            style = OreTheme.type.numeric,
                            color = OreTheme.color.text.secondary,
                            maxLines = 1,
                        )
                    }
                }
                if (wallets.isEmpty()) {
                    item(key = "fleet_empty") {
                        PumpCard {
                            OreText(
                                text = PumpConsoleCopy.EMPTY_TITLE,
                                style = OreTheme.type.bodyStrong,
                                color = OreTheme.color.text.primary,
                            )
                            OreText(
                                text = PumpConsoleCopy.EMPTY_BODY,
                                style = OreTheme.type.body,
                                color = OreTheme.color.text.secondary,
                            )
                        }
                    }
                } else {
                    itemsIndexed(wallets, key = { index, _ -> "wallet_$index" }) { _, wallet -> PumpWalletCard(wallet) }
                }
                item(key = "link_wallet") {
                    Column(verticalArrangement = Arrangement.spacedBy(space.xs)) {
                        if (wallets.size >= PumpConsoleCopy.MAX_WALLETS) {
                            OreText(
                                text = PumpConsoleCopy.FLEET_FULL,
                                style = OreTheme.type.caption,
                                color = OreTheme.color.text.secondary,
                            )
                        } else {
                            OreText(
                                text = PumpConsoleCopy.LINK_NO_CHARGE,
                                style = OreTheme.type.caption,
                                color = OreTheme.color.text.secondary,
                            )
                            OrePrimaryButton(
                                text = PumpConsoleCopy.LINK_WALLET,
                                onClick = onLinkWallet,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = actionsEnabled,
                                note = if (actionsEnabled) null else PumpConsoleCopy.PREVIEW_NOTE,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PumpWalletCard(wallet: PumpWalletUi) {
    PumpCard {
        OreText(
            text = wallet.addressShort,
            style = OreTheme.type.bodyStrong,
            color = OreTheme.color.text.primary,
            maxLines = 1,
        )
        PumpWalletLine(label = PumpConsoleCopy.BALANCE, value = wallet.balanceText)
        PumpWalletLine(label = PumpConsoleCopy.TANK, value = wallet.tankStateText)
    }
}

@Composable
private fun PumpWalletLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm)) {
        OreText(
            text = label,
            style = OreTheme.type.caption,
            color = OreTheme.color.text.tertiary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        OreText(text = value, style = OreTheme.type.numeric, color = OreTheme.color.text.primary, maxLines = 1)
    }
}

@Composable
private fun PumpCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(OreTheme.color.surface.raised)
            .padding(OreTheme.space.md),
        verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs),
    ) { content() }
}

@Composable
private fun PumpSkeleton() {
    val space = OreTheme.space
    Column(
        modifier = Modifier.fillMaxSize().padding(space.md),
        verticalArrangement = Arrangement.spacedBy(space.sm),
    ) {
        repeat(PumpConsoleCopy.SKELETON_CARDS) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(OreTheme.size.touchTarget * 2)
                    .background(OreTheme.color.surface.highlight),
            )
        }
    }
}
