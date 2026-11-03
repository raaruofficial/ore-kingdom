package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raaru.orekingdom.util.AssetSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared pieces of the M3 payment surfaces: the R5 disclosures (the
 * same facts, in the same words, on every surface before the wallet
 * is asked to sign), the asset picker, and quote rows. One home for
 * the disclosure wording so no surface paraphrases it weaker.
 */

/**
 * The R5 pre-signing disclosures. Payment finality, the expired-
 * session rule with the O1 forward-apply, and the public permanent
 * on-chain record — presented BEFORE the sign action on the join,
 * prestige, thread-fee, commercial, tip and pin surfaces alike.
 * [forPost] adds the paid-post retry fact (a post that fails after
 * payment retries free inside the session's consumption window).
 */
@Composable
fun PaymentDisclosures(forPost: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Before you pay",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            DisclosureLine("Payment is final — all sales are final, and there is no refund path.")
            DisclosureLine(
                "If this payment window expires, a late transfer can't revive it. " +
                    "Money that lands but is refused is credited forward to your next " +
                    "paid action — never pocketed, never refunded in cash."
            )
            DisclosureLine(
                "This payment creates a public, permanent record on the Solana " +
                    "blockchain — the amount, the time and your wallet — readable by anyone."
            )
            if (forPost) {
                DisclosureLine(
                    "If a paid post fails after payment, posting it again is free " +
                        "inside the session's 24-hour window."
                )
            }
        }
    }
}

@Composable
private fun DisclosureLine(text: String) {
    Text(
        "• $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

/** The payment asset menu: chips over the assets the surface's host
 *  action can actually accept (the full SKR/ORE/SOL/USDC menu for the
 *  wrapper-native sessions; SOL/ORE for the bundle checkouts). */
@Composable
fun AssetPicker(
    assets: List<AssetSpec>,
    selected: AssetSpec,
    enabled: Boolean = true,
    onSelect: (AssetSpec) -> Unit
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .fillMaxWidth()
    ) {
        assets.forEach { asset ->
            FilterChip(
                selected = asset.code == selected.code,
                onClick = { onSelect(asset) },
                enabled = enabled,
                label = { Text(asset.code) }
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** One label/value row of a quote or review sheet. */
@Composable
fun QuoteRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Epoch millis (the host's session windows) -> a readable local
 *  date + time for quote surfaces. */
fun formatEpochMs(ms: Long?): String {
    if (ms == null || ms <= 0) return "—"
    return SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(ms))
}
