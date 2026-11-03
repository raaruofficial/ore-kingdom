package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.raaru.orekingdom.data.VIP_BOARD_SLUG
import com.raaru.orekingdom.data.ForumPayload
import com.raaru.orekingdom.data.shortWallet
import com.raaru.orekingdom.data.vipCategory
import com.raaru.orekingdom.ui.ForumViewModel
import com.raaru.orekingdom.util.AssetSpec
import com.raaru.orekingdom.util.Assets
import com.raaru.orekingdom.util.formatAssetAmount
import com.raaru.orekingdom.util.formatGrams
import com.raaru.orekingdom.util.formatUsdCents
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/**
 * The thread composer (M3): board, title, body — then the paid path.
 * Continue resolves what the thread costs BEFORE anything is signed:
 * createPostSession answers a priced quote or an exemption (a prestige
 * holder's thread is exempt — the composer shows "Prestige — no thread
 * fee" and posts without a payment); the commercial toggle takes the
 * $50 commercial session instead of the thread fee and the thread is
 * labeled commercial; the VIP board never takes a payment session
 * (holders post session-free; a non-holder is refused before any
 * session exists, so no payment can be stranded against it).
 *
 * The quote card carries the served price of record (USD), the exact
 * asset amount (ORE in ORE and grams), the Relay destination and the
 * pay window, with the R5 disclosures above the Pay button. Editing
 * the draft, the board, or the kind after a quote discards the quote —
 * Continue re-quotes fresh, never a silent re-price (R6).
 *
 * [preselectCategoryId] 0 = no preselection (first board); the River
 * passes the current board view's id when there is one.
 */
@Composable
fun ComposerScreen(
    navController: NavController,
    vm: ForumViewModel,
    sender: ActivityResultSender,
    preselectCategoryId: Int
) {
    val forumState by vm.forumState.collectAsState()
    val paying by vm.paying.collectAsState()
    val prestigeHolder by vm.isPrestigeHolder.collectAsState()
    val payload = (forumState as? ForumViewModel.LoadState.Ready<*>)?.let {
        @Suppress("UNCHECKED_CAST")
        it.value as ForumPayload
    }
    val categories = payload?.categories ?: emptyList()
    val vipId = payload?.let { vipCategory(it)?.id }

    var selectedCategoryId by remember(preselectCategoryId, categories) {
        mutableStateOf(
            when {
                preselectCategoryId != 0 &&
                    categories.any { it.id == preselectCategoryId } -> preselectCategoryId
                else -> categories.firstOrNull()?.id ?: 0
            }
        )
    }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var commercial by remember { mutableStateOf(false) }
    var asset by remember { mutableStateOf<AssetSpec>(Assets.SKR) }
    var prep by remember { mutableStateOf<ForumViewModel.ThreadPrep?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId }
    val isVipBoard = selectedCategory != null &&
        (selectedCategory.isVip || selectedCategory.id == vipId ||
            selectedCategory.slug == VIP_BOARD_SLUG)
    // The caller's holder state as last served (payload flag preferred;
    // the prestige read is the fallback). Null = unserved — the VM
    // then attempts session-free and lets the gate decide.
    val callerPrestige = payload?.callerPrestige ?: prestigeHolder
    val draftValid = selectedCategoryId != 0 && title.isNotBlank() && body.isNotBlank()

    fun invalidateQuote() {
        prep = null
    }

    fun execute(prepared: ForumViewModel.ThreadPrep) {
        error = null
        progress = null
        vm.executeThreadPayment(
            sender, selectedCategoryId, title, body, prepared,
            onProgress = { progress = it },
            onDone = { err ->
                progress = null
                if (err == null) {
                    navController.popBackStack()
                } else {
                    // Terminal for this quote (refused, expired, or
                    // parked-and-confirming): the next attempt starts
                    // from a fresh quote, never a re-priced one (R6).
                    error = err
                    prep = null
                }
            }
        )
    }

    Scaffold(topBar = { OreTopBar("New thread", navController, showBack = true) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))
            Text("Board", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .fillMaxWidth()
            ) {
                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategoryId == category.id,
                        onClick = {
                            selectedCategoryId = category.id
                            if (category.id == vipId || category.isVip ||
                                category.slug == VIP_BOARD_SLUG
                            ) {
                                commercial = false
                            }
                            invalidateQuote()
                        },
                        enabled = !paying,
                        label = {
                            Text(
                                (if (category.id == vipId || category.isVip ||
                                    category.slug == VIP_BOARD_SLUG
                                ) {
                                    "🔒 "
                                } else {
                                    ""
                                }) + category.name
                            )
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
            if (isVipBoard) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "The prestige board: holders post here with no payment session " +
                        "and no fee; the host refuses everyone else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!isVipBoard) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Commercial thread", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "$50 flat instead of the thread fee — labeled COMMERCIAL on the board.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = commercial,
                        onCheckedChange = {
                            commercial = it
                            invalidateQuote()
                        },
                        enabled = !paying && prep == null
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = title,
                onValueChange = {
                    title = it
                    invalidateQuote()
                },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = body,
                onValueChange = {
                    body = it
                    invalidateQuote()
                },
                label = { Text("Body") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth()
            )

            val currentPrep = prep
            if (currentPrep == null) {
                if (!isVipBoard) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (commercial) "Pay the commercial fee in" else "Pay the thread fee in",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(4.dp))
                    AssetPicker(assets = Assets.MENU, selected = asset, enabled = !paying) {
                        asset = it
                    }
                    if (callerPrestige == true && !commercial) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Prestige — no thread fee: Continue confirms your exemption " +
                                "with the host; you won't be charged.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        error = null
                        vm.prepareThreadPayment(
                            sender,
                            commercial = commercial,
                            isVipBoard = isVipBoard,
                            callerPrestige = callerPrestige,
                            asset = asset,
                            onReady = { prep = it },
                            onError = { error = it }
                        )
                    },
                    enabled = draftValid && !paying,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (paying) "Asking the host…" else "Continue")
                }
            } else {
                Spacer(Modifier.height(16.dp))
                when (val p = currentPrep) {
                    is ForumViewModel.ThreadPrep.Exempt -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    p.note ?: "No payment is needed for this post.",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { execute(p) },
                            enabled = !paying,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (paying) "Posting…" else "Post thread — no fee") }
                    }
                    ForumViewModel.ThreadPrep.SessionFree -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "VIP board — as a prestige holder you post here with " +
                                        "no payment session and no fee.",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { execute(p) },
                            enabled = !paying,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (paying) "Posting…" else "Post thread") }
                    }
                    is ForumViewModel.ThreadPrep.Quote -> {
                        val s = p.session
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    if (p.commercial) "Your commercial quote" else "Your thread-fee quote",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(8.dp))
                                QuoteRow(
                                    "Price",
                                    s.usdCents?.let { formatUsdCents(it) } ?: "—"
                                )
                                QuoteRow(
                                    "You pay",
                                    s.asset?.let { formatAssetAmount(s.amountBase, it) }
                                        ?: s.amountBase.toString()
                                )
                                if (s.asset == Assets.ORE) {
                                    QuoteRow("In grams", formatGrams(s.amountBase))
                                }
                                QuoteRow(
                                    "To",
                                    "The Relay, the kingdom treasury (${shortWallet(s.destination)})"
                                )
                                QuoteRow("Pay by", formatEpochMs(s.payExpiresAt))
                                if (p.commercial) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "This thread is labeled COMMERCIAL on the board.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        PaymentDisclosures(forPost = true)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { execute(p) },
                            enabled = !paying,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (paying) "Working…" else {
                                    if (p.commercial) "Pay & post commercial thread" else "Pay & post"
                                }
                            )
                        }
                    }
                }
                if (progress != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(progress!!, style = MaterialTheme.typography.bodyMedium)
                }
                if (error != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Back to editing discards this quote — Continue re-quotes fresh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
