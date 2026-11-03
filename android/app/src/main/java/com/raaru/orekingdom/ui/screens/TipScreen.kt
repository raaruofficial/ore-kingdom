package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.raaru.orekingdom.data.TipCheckout
import com.raaru.orekingdom.data.shortWallet
import com.raaru.orekingdom.data.tipConfirmArmed
import com.raaru.orekingdom.ui.ForumViewModel
import com.raaru.orekingdom.util.AssetSpec
import com.raaru.orekingdom.util.Assets
import com.raaru.orekingdom.util.TIP_MAX_USD_CENTS
import com.raaru.orekingdom.util.formatAssetAmount
import com.raaru.orekingdom.util.formatGrams
import com.raaru.orekingdom.util.formatUsdCents
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/**
 * Tipping (the tip-safety ruling's flow): compose in dollars, then a
 * REVIEW SHEET, then an explicit confirm — the double opt-in. The
 * sheet shows the recipient's .skr name, the tip in the chosen asset
 * (ORE also in grams — the unit-confusion case is designed out), its
 * dollar value, the $1 platform fee as its own line, and the total
 * debit. The confirm arms only when the sheet's total equals the
 * checkout's stored total and the fee leg is fully served (its own
 * reference key included); one transaction then carries both legs —
 * tip to the author, fee to the Relay — under one signature (A7).
 *
 * Tips are denominated in dollars by the host's own contract
 * (amountUsdCents); the asset amounts on the sheet are the host's
 * served quote for that dollar figure.
 *
 * [replyId] -1 = the thread itself is tipped; otherwise the reply.
 */
@Composable
fun TipScreen(
    navController: NavController,
    vm: ForumViewModel,
    sender: ActivityResultSender,
    threadId: Int,
    replyId: Int
) {
    val paying by vm.paying.collectAsState()
    var amountText by remember { mutableStateOf("") }
    var asset by remember { mutableStateOf<AssetSpec>(Assets.SOL) }
    var checkout by remember { mutableStateOf<TipCheckout?>(null) }
    var attempted by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sent by remember { mutableStateOf(false) }

    Scaffold(topBar = { OreTopBar("Tip", navController, showBack = true) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (sent) {
                Text(
                    "Tip sent — thank you.",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Back to the thread") }
                return@Column
            }

            val quote = checkout
            if (quote == null) {
                Text(
                    if (replyId >= 0) "Tip this reply's author" else "Tip this thread's author",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tips run from ${formatUsdCents(500)} to ${formatUsdCents(TIP_MAX_USD_CENTS)}. " +
                        "A flat $1.00 platform fee rides on top, in the same asset, in the " +
                        "same transaction — you will see both legs on the review sheet " +
                        "before anything is signed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it; error = null },
                    label = { Text("Tip amount (USD)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Pay in", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                AssetPicker(assets = Assets.BUNDLE_MENU, selected = asset, enabled = !paying) {
                    asset = it
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        error = null
                        val cents = parseUsdToCents(amountText)
                        if (cents == null) {
                            error = "Enter the tip in dollars — for example 12.50."
                        } else {
                            vm.prepareTip(
                                sender,
                                threadId = if (replyId >= 0) null else threadId,
                                replyId = if (replyId >= 0) replyId else null,
                                asset = asset,
                                amountUsdCents = cents,
                                onReady = { checkout = it; attempted = false },
                                onError = { error = it }
                            )
                        }
                    },
                    enabled = !paying && amountText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (paying) "Asking the host…" else "Review tip") }
            } else {
                // ---------- the review sheet (opt-in #1 is the
                // compose above; the confirm below is opt-in #2) ----------
                Text(
                    "Review your tip",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        QuoteRow(
                            "To",
                            quote.recipientSkrName ?: shortWallet(quote.recipientWallet)
                        )
                        QuoteRow("Tip", formatAssetAmount(quote.tipAmountBase, quote.asset))
                        if (quote.asset == Assets.ORE) {
                            QuoteRow("Tip in grams", formatGrams(quote.tipAmountBase))
                        }
                        QuoteRow("Tip value", formatUsdCents(quote.amountUsdCents))
                        val fee = quote.fee
                        if (fee != null) {
                            QuoteRow(
                                "Platform fee",
                                "${formatAssetAmount(fee.amountBase, fee.asset ?: quote.asset)} " +
                                    "(${formatUsdCents(fee.usdCents)})"
                            )
                        }
                        val total = quote.reviewTotalBase()
                        if (total != null) {
                            QuoteRow("Total debit", formatAssetAmount(total, quote.asset))
                            if (quote.asset == Assets.ORE) {
                                QuoteRow("Total in grams", formatGrams(total))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                val fee = quote.fee
                val total = quote.reviewTotalBase()
                val armed = !attempted && total != null && tipConfirmArmed(quote, total)
                when {
                    fee == null -> Text(
                        "The host did not serve the platform fee for this checkout, so the " +
                            "tip can't be completed from here yet. Nothing was sent.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    fee?.reference.isNullOrEmpty() -> Text(
                        "The host did not serve the platform fee's payment reference for " +
                            "this checkout, so the fee leg can't be built in a form the " +
                            "host can verify. Nothing was sent and nothing was charged — " +
                            "this surface arms itself the moment the host serves it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    attempted -> Text(
                        "This checkout was already attempted. If the payment landed, it " +
                            "finishes from “Payments to finish” on the River — it is never " +
                            "paid twice.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> {
                        PaymentDisclosures()
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                error = null
                                progress = null
                                attempted = true
                                vm.executeTip(
                                    sender, quote,
                                    threadId = threadId,
                                    onProgress = { progress = it },
                                    onDone = { err ->
                                        progress = null
                                        if (err == null) {
                                            sent = true
                                        } else {
                                            error = err
                                        }
                                    }
                                )
                            },
                            enabled = armed && !paying,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (paying) "Working…" else "Confirm & sign tip") }
                    }
                }

                // The A7 fallback, offered only in the state it
                // settles: a previous attempt's fee leg outstanding,
                // with the leg's reference served to build from.
                val lastError = error
                if (fee != null && !fee.reference.isNullOrEmpty() &&
                    lastError != null && lastError.contains("fee", ignoreCase = true)
                ) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            error = null
                            progress = null
                            vm.settleOutstandingTipFee(
                                sender, quote,
                                onProgress = { progress = it },
                                onDone = { err ->
                                    progress = null
                                    if (err == null) {
                                        error = "Fee settled — the tip can be finished from “Payments to finish” on the River."
                                    } else {
                                        error = err
                                    }
                                }
                            )
                        },
                        enabled = !paying,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Settle the $1 platform fee on its own") }
                }

                if (!attempted) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { checkout = null; error = null },
                        enabled = !paying,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Back — change the tip") }
                }
            }

            progress?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** "12.50" -> 1250; null when the text is not a clean dollar figure. */
private fun parseUsdToCents(text: String): Int? {
    val cleaned = text.trim().removePrefix("\$").trim()
    if (!Regex("^\\d+(\\.\\d{1,2})?$").matches(cleaned)) return null
    val parts = cleaned.split(".")
    val whole = parts[0].toIntOrNull() ?: return null
    val frac = if (parts.size > 1) parts[1].padEnd(2, '0').toInt() else 0
    val cents = whole * 100 + frac
    return if (cents > 0) cents else null
}
