package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import com.raaru.orekingdom.data.ForumPayload
import com.raaru.orekingdom.data.PinCheckout
import com.raaru.orekingdom.data.ServedPinSlot
import com.raaru.orekingdom.data.shortWallet
import com.raaru.orekingdom.ui.ForumViewModel
import com.raaru.orekingdom.util.AssetSpec
import com.raaru.orekingdom.util.Assets
import com.raaru.orekingdom.util.formatAssetAmount
import com.raaru.orekingdom.util.formatGrams
import com.raaru.orekingdom.util.formatUsdCents
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/**
 * Buying a paid pin ($250 for 12 hours, 2 spots in rotation): pick one
 * of your own threads, take the host's checkout, pay it, complete with
 * the signature. The spot state above the quote is the getPinSlots
 * read itself. Two truthful edges are built in, not papered over:
 * the host refuses a new checkout at create when both spots are
 * spoken for (nothing charged — its words surface verbatim), and a
 * completion that lands after both spots filled is DEFERRED by the
 * host — the pin activates when a spot frees, and this screen says
 * exactly that, with the earliest freeing time the read serves.
 */
@Composable
fun PinBuyScreen(navController: NavController, vm: ForumViewModel, sender: ActivityResultSender) {
    val forumState by vm.forumState.collectAsState()
    val servedSlots by vm.pinSlots.collectAsState()
    val paying by vm.paying.collectAsState()
    val payload = (forumState as? ForumViewModel.LoadState.Ready<*>)?.let {
        @Suppress("UNCHECKED_CAST")
        it.value as ForumPayload
    }
    val ownThreads = payload?.threads?.filter { it.canPaidPin } ?: emptyList()

    var selectedThreadId by remember { mutableStateOf(0) }
    // The effective selection: the member's pick while it is still
    // one of their pinnable threads, else the first such thread.
    val effectiveThreadId =
        if (ownThreads.any { it.id == selectedThreadId }) {
            selectedThreadId
        } else {
            ownThreads.firstOrNull()?.id ?: 0
        }
    var asset by remember { mutableStateOf<AssetSpec>(Assets.SOL) }
    var checkout by remember { mutableStateOf<PinCheckout?>(null) }
    var attempted by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var resultNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.loadPinSlots() }

    val selectedThread = ownThreads.firstOrNull { it.id == effectiveThreadId }
    val earliestFree = servedSlots?.slots
        ?.filterIsInstance<ServedPinSlot.Filled>()
        ?.minByOrNull { it.endsAt }

    Scaffold(topBar = { OreTopBar("Pin a thread", navController, showBack = true) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (resultNote != null) {
                Text(
                    resultNote!!,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Back to the River") }
                return@Column
            }

            Text(
                "Two pin spots rotate through the top of the River. A spot is " +
                    "$250 for 12 hours.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // The live spot state, from the getPinSlots read.
            servedSlots?.let { slots ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "The spots right now",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        slots.slots.forEach { slot ->
                            when (slot) {
                                is ServedPinSlot.Filled -> Text(
                                    "Taken — “${slot.threadTitle}” until ${formatEpochMs(slot.endsAt)}",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                ServedPinSlot.Vacant -> Text(
                                    "Open",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (slots.deferredCount > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${slots.deferredCount} paid pin(s) are queued behind the current spots.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            val quote = checkout
            if (quote == null) {
                if (ownThreads.isEmpty()) {
                    Text(
                        "Pins are for your own threads — post a thread first, then pin it from here.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    Text("Your thread", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Column {
                        ownThreads.forEach { thread ->
                            Row {
                                FilterChip(
                                    selected = effectiveThreadId == thread.id,
                                    onClick = { selectedThreadId = thread.id },
                                    enabled = !paying,
                                    label = { Text(thread.title) }
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                    }
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
                            vm.preparePin(
                                sender,
                                threadId = effectiveThreadId,
                                asset = asset,
                                onReady = { checkout = it; attempted = false },
                                onError = { error = it }
                            )
                        },
                        enabled = !paying && effectiveThreadId != 0,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (paying) "Asking the host…" else "See the pin quote") }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Your pin quote",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        QuoteRow("Pin", formatUsdCents(quote.amountUsdCents))
                        QuoteRow(
                            "You pay",
                            formatAssetAmount(quote.amountBase, quote.asset)
                        )
                        if (quote.asset == Assets.ORE) {
                            QuoteRow("In grams", formatGrams(quote.amountBase))
                        }
                        QuoteRow("Runs", "${quote.hours} hours in the spotlight rotation")
                        QuoteRow(
                            "To",
                            "The Relay, the kingdom treasury (${shortWallet(quote.destinationWallet)})"
                        )
                        if (earliestFree != null && servedSlots?.activeCount == servedSlots?.cap) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Both spots are taken right now. If they are still taken " +
                                    "when your payment confirms, your pin is queued and " +
                                    "starts when the first spot frees — the earliest " +
                                    "current pin ends ${formatEpochMs(earliestFree.endsAt)}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (!quote.confirmable) {
                    Text(
                        "The host did not serve this pin checkout's payment reference, so " +
                            "the payment can't be built in a form the host can verify. " +
                            "Nothing was sent and nothing was charged — this surface arms " +
                            "itself the moment the host serves it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (attempted) {
                    Text(
                        "This checkout was already attempted. If the payment landed, it " +
                            "finishes from “Payments to finish” on the River — it is never " +
                            "paid twice.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    PaymentDisclosures()
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            error = null
                            progress = null
                            attempted = true
                            vm.executePin(
                                sender, quote,
                                threadId = effectiveThreadId,
                                threadTitle = selectedThread?.title ?: "your thread",
                                onProgress = { progress = it },
                                onDone = { err, deferred ->
                                    progress = null
                                    when {
                                        err == null && deferred -> resultNote =
                                            "Paid and queued — your pin activates when a slot " +
                                                "frees" +
                                                (earliestFree?.let {
                                                    ", around ${formatEpochMs(it.endsAt)}"
                                                } ?: "") + "."
                                        err == null -> resultNote = "Your pin is live in the spotlight."
                                        else -> error = err
                                    }
                                }
                            )
                        },
                        enabled = !paying,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (paying) "Working…" else "Pay & pin") }
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
