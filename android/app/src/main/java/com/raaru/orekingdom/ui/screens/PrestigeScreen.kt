package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.raaru.orekingdom.data.PaySession
import com.raaru.orekingdom.data.shortWallet
import com.raaru.orekingdom.ui.ForumViewModel
import com.raaru.orekingdom.util.Assets
import com.raaru.orekingdom.util.formatAssetAmount
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/**
 * Prestige (the overhaul's only paid status): 10,000 SKR one-time,
 * at most 100 of the kingdom's 1,000 members — badges, permanent
 * thread-fee exemption, and the VIP board. The host serves the
 * caller's holder flag and refuses the 101st claim by name; it does
 * not serve a claimed-count, so this surface shows the cap without a
 * count and never invents one. The purchase is the same session
 * discipline as the join: quote, one transfer, completion by
 * reference through the pending loop.
 */
@Composable
fun PrestigeScreen(navController: NavController, vm: ForumViewModel, sender: ActivityResultSender) {
    val holder by vm.isPrestigeHolder.collectAsState()
    val paying by vm.paying.collectAsState()
    var session by remember { mutableStateOf<PaySession?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var claimed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.loadPrestigeStatus(sender) { statusError = it }
    }

    Scaffold(topBar = { OreTopBar("Prestige", navController, showBack = true) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (holder == true || claimed) {
                Text(
                    "👑 You hold Prestige.",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                PrestigePerks()
                return@Column
            }

            Text(
                "Prestige — 10,000 SKR, once.",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            PrestigePerks()
            Spacer(Modifier.height(8.dp))
            Text(
                "At most 100 of the kingdom's 1,000 members can hold it — when the " +
                    "100th place is claimed, prestige closes. The host does not publish " +
                    "a live count, so none is shown here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            val quote = session
            if (quote == null) {
                Button(
                    onClick = {
                        error = null
                        vm.preparePrestige(
                            sender,
                            onReady = { session = it },
                            onError = { error = it }
                        )
                    },
                    enabled = !paying,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (paying) "Asking the host…" else "See the prestige quote") }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Your prestige quote",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        QuoteRow(
                            "Price",
                            formatAssetAmount(quote.amountBase, quote.asset ?: Assets.SKR)
                        )
                        QuoteRow("Due", "Once — no subscription")
                        QuoteRow(
                            "To",
                            "The Relay, the kingdom treasury (${shortWallet(quote.destination)})"
                        )
                        QuoteRow("Pay by", formatEpochMs(quote.payExpiresAt))
                    }
                }
                Spacer(Modifier.height(12.dp))
                PaymentDisclosures()
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        error = null
                        progress = null
                        vm.executePrestige(
                            sender, quote,
                            onProgress = { progress = it },
                            onDone = { err ->
                                progress = null
                                if (err == null) {
                                    claimed = true
                                } else {
                                    error = err
                                    session = null
                                }
                            }
                        )
                    },
                    enabled = !paying,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (paying) "Working…" else "Pay 10,000 SKR & claim prestige") }
            }

            statusError?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
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

@Composable
private fun PrestigePerks() {
    Text(
        "Prestige carries the prestige badge on everything you post, a permanent " +
            "exemption from the thread fee, and the VIP board — readable and " +
            "postable by holders only.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
