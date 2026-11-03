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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.raaru.orekingdom.data.PaySession
import com.raaru.orekingdom.data.shortWallet
import com.raaru.orekingdom.ui.ForumViewModel
import com.raaru.orekingdom.ui.theme.OreAmber
import com.raaru.orekingdom.ui.theme.labelCaps
import com.raaru.orekingdom.ui.theme.tnum
import com.raaru.orekingdom.util.Assets
import com.raaru.orekingdom.util.formatAssetAmount
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/**
 * The join gate (R1): the one priced door into membership. A signed-in
 * wallet with no host member record is offered the join here — reading
 * stays free and open either way. The quote is the host's own session:
 * 5,000 SKR (SKR is the price of record; the host serves no dollar
 * rate for the join, so none is shown), the Relay as destination, the
 * session reference riding the transfer, the pay window. Payment is
 * one SKR transfer; completion verifies by reference through the
 * pending loop. A wallet that already holds a membership is refused
 * by the host at quote time and never sees a charge.
 */
@Composable
fun JoinScreen(navController: NavController, vm: ForumViewModel, sender: ActivityResultSender) {
    val member by vm.member.collectAsState()
    val paying by vm.paying.collectAsState()
    var session by remember { mutableStateOf<PaySession?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var joined by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Scaffold(topBar = { OreTopBar("Join the kingdom", navController, showBack = true) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (joined) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    OreCrownMark(48.dp)
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "You're a member of the kingdom.",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Posting, replying, tipping and pinning are open to this wallet now. " +
                        "Welcome in.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Back") }
                return@Column
            }

            Text(
                "Membership is one payment: 5,000 SKR, once. No subscription.",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Reading the kingdom is free forever, member or not. Joining is what " +
                    "opens posting, tipping, pins and the rest. The kingdom takes at " +
                    "most 1,000 members — when the 1,000th joins, sign-ups close for good.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            val quote = session
            if (quote == null) {
                Button(
                    onClick = {
                        error = null
                        vm.prepareJoin(
                            sender,
                            displayName = member?.displayName,
                            skrName = member?.skrName,
                            onReady = { session = it },
                            onError = { error = it }
                        )
                    },
                    enabled = !paying,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (paying) "Asking the host…" else "See the join quote") }
            } else {
                OrePlaque {
                    Text(
                        "YOUR JOIN QUOTE",
                        style = MaterialTheme.typography.labelCaps,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        formatAssetAmount(quote.amountBase, quote.asset ?: Assets.SKR),
                        style = MaterialTheme.typography.titleLarge.tnum(),
                        color = OreAmber
                    )
                    Spacer(Modifier.height(8.dp))
                    QuoteRow(
                        "Join price",
                        formatAssetAmount(quote.amountBase, quote.asset ?: Assets.SKR)
                    )
                    QuoteRow("Due", "Once — no subscription")
                    QuoteRow(
                        "To",
                        "The Relay, the kingdom treasury (${shortWallet(quote.destination)})"
                    )
                    QuoteRow("Pay by", formatEpochMs(quote.payExpiresAt))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "SKR is the price of record for the join. The host does not " +
                            "quote a dollar rate for it, so no dollar figure is shown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                PaymentDisclosures()
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        error = null
                        progress = null
                        vm.executeJoin(
                            sender, quote,
                            onProgress = { progress = it },
                            onDone = { err ->
                                progress = null
                                if (err == null) {
                                    joined = true
                                } else {
                                    // A finished attempt leaves the quote
                                    // behind: the next try re-quotes fresh
                                    // (R6), it never re-prices this one.
                                    error = err
                                    session = null
                                }
                            }
                        )
                    },
                    enabled = !paying,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (paying) "Working…" else "Pay 5,000 SKR & join") }
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
