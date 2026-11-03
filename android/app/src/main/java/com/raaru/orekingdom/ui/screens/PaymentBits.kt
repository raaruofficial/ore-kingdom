package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.raaru.orekingdom.ui.theme.OreAmber
import com.raaru.orekingdom.ui.theme.OreEdge
import com.raaru.orekingdom.ui.theme.OreSurfaceRaised
import com.raaru.orekingdom.ui.theme.labelCaps
import com.raaru.orekingdom.ui.theme.tnum
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

/** The engraved plaque (FLAGSHIP_DESIGN §7): rung-2 ground, the
 *  ore-seam top edge, hairline, 20dp radius. The dress of every
 *  quote and review surface in the money family — what is said
 *  inside it is law (§10) and never changes with the dress. */
@Composable
fun OrePlaque(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = OreSurfaceRaised),
        border = BorderStroke(1.dp, OreEdge),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        OreSeamEdge(topRadius = 20.dp)
        Column(modifier = Modifier.padding(20.dp), content = content)
    }
}

/**
 * The R5 pre-signing disclosures. Payment finality, the expired-
 * session rule with the O1 forward-apply, and the public permanent
 * on-chain record — presented BEFORE the sign action on the join,
 * prestige, thread-fee, commercial, tip and pin surfaces alike.
 * [forPost] adds the paid-post retry fact (a post that fails after
 * payment retries free inside the session's consumption window).
 *
 * Dress (FLAGSHIP_DESIGN §7): the engraved plaque — content, order,
 * and placement above the sign action are law (§10); only the
 * plaque's dress changed.
 */
@Composable
fun PaymentDisclosures(forPost: Boolean = false) {
    OrePlaque {
        Text(
            "BEFORE YOU PAY",
            style = MaterialTheme.typography.labelCaps,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
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

@Composable
private fun DisclosureLine(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .rotate(45f)
                .background(OreAmber)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** The payment asset menu: chips over the assets the surface's host
 *  action can actually accept (the full SKR/ORE/SOL/USDC menu for the
 *  wrapper-native sessions; SOL/ORE for the bundle checkouts).
 *  Dress (FLAGSHIP_DESIGN §5/§7): the asset code in the caps voice
 *  inside a full-round chip — selected is the amber fill, unselected
 *  the hairline outline. */
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
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = OreAmber,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = enabled,
                    selected = asset.code == selected.code,
                    borderColor = OreEdge,
                    selectedBorderColor = OreAmber
                ),
                label = {
                    Text(asset.code, style = MaterialTheme.typography.labelCaps)
                }
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** One label/value row of a quote or review sheet — a ledger row
 *  (FLAGSHIP_DESIGN §7): label muted left, value tabular SemiBold
 *  right, hairline between rows. */
@Composable
fun QuoteRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.tnum(),
            fontWeight = FontWeight.SemiBold
        )
    }
    HorizontalDivider(color = OreEdge)
}

/** Epoch millis (the host's session windows) -> a readable local
 *  date + time for quote surfaces. */
fun formatEpochMs(ms: Long?): String {
    if (ms == null || ms <= 0) return "—"
    return SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(ms))
}
