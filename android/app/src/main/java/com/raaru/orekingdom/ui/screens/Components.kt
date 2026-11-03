package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.raaru.orekingdom.data.ForumThread
import com.raaru.orekingdom.data.shortDate
import kotlinx.coroutines.delay

/** Listing disambiguation (rebuild census item 15): independent
 *  community forum, not the protocol's. Stands in the sign-in footer. */
const val DISCLAIMER =
    "Ore Kingdom is an independent community forum for ORE miners. " +
        "It is not affiliated with the ORE protocol or Regolith Labs."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OreTopBar(
    title: String,
    navController: NavController,
    showBack: Boolean = false,
    actions: @Composable () -> Unit = {}
) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            if (showBack) {
                Text(
                    "‹ Back",
                    modifier = Modifier
                        .clickable { navController.popBackStack() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        actions = { actions() }
    )
}

/** The host-unreachable state: never a blank screen. Names the failure
 *  the way the sealed build does and offers the one action that helps. */
@Composable
fun HostUnreachable(onRetry: () -> Unit, refusal: String? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                refusal ?: "Ore Kingdom could not load — the forum did not answer.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Check your connection, then try again. Reading you already " +
                    "loaded stays available from saved reading when there is any.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
fun LoadingBox(label: String = "Loading the kingdom…") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun StaleBanner(onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Saved reading — the forum didn't answer just now.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                "Retry",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable { onRetry() }
                    .padding(8.dp)
            )
        }
    }
}

/** The Founding Member mark (founder-kill ruling, 2026-11-02): a
 *  display-only label computed host-side from join order — it carries
 *  no permissions, and the client renders exactly the flag the payload
 *  carries (authorIsFoundingMember / Member.isFoundingMember), never
 *  a number the payload didn't serve. */
@Composable
fun FoundingMark() {
    Text(
        "✦ FOUNDING MEMBER",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
fun ThreadRow(thread: ForumThread, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (thread.isPaidPinned || thread.isPinned) {
                    Text(
                        "PINNED · ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    thread.categoryName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    shortDate(thread.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                thread.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (thread.body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    thread.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${thread.authorName} · ${thread.replies} replies · ${thread.likes} likes" +
                        if (thread.isLocked) " · locked" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (thread.authorIsFoundingMember) {
                    Spacer(Modifier.width(8.dp))
                    FoundingMark()
                }
                if (thread.isCommercial) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "COMMERCIAL",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * The pin spotlight (pin spec): the live pin slots rotate through this
 * one position above the River with equal exposure; a vacant slot is
 * the house availability ad — spot, $250 / 12 hours, and the take-it
 * action, which opens the pin explainer (the purchase itself is wired
 * with the M3 payment flows). The strip never renders an empty frame:
 * the slot list always carries PIN_SLOT_COUNT entries.
 */
@Composable
fun PinSpotlight(
    slots: List<com.raaru.orekingdom.data.PinSlot>,
    onOpenThread: (Int) -> Unit,
    onTakeSpot: () -> Unit
) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(slots.size) {
        index = 0
        while (slots.size > 1) {
            delay(6_000)
            index = (index + 1) % slots.size
        }
    }
    if (slots.isEmpty()) return
    val slot = slots[index.coerceIn(0, slots.lastIndex)]
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        when (slot) {
            is com.raaru.orekingdom.data.PinSlot.Filled -> {
                val thread = slot.thread
                Column(
                    modifier = Modifier
                        .clickable { onOpenThread(thread.id) }
                        .padding(14.dp)
                ) {
                    Text(
                        "📌 PAID PIN · until ${shortDate(thread.paidPinEndsAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        thread.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "by ${thread.authorName} · tap to open",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            com.raaru.orekingdom.data.PinSlot.Vacant -> {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "📌 PIN SPOT AVAILABLE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Pin your thread at the top of the kingdom — $250 for 12 hours.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onTakeSpot) { Text("Take this spot") }
                }
            }
        }
    }
}

/** The pin explainer the vacant slot's take-it action opens. Truthful
 *  about what exists today: the spot, the price of record, the
 *  rotation — and that the purchase flow itself lands with the M3
 *  payment build. Not a dead end and not a fake checkout. */
@Composable
fun PinExplainerDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pin a thread") },
        text = {
            Text(
                "Two pin spots rotate through the top of the River, each " +
                    "shown in turn. A spot costs $250 for 12 hours, payable " +
                    "in SKR, ORE, SOL or USDC; if both spots are taken, a " +
                    "purchase waits and starts when the first spot frees.\n\n" +
                    "Buying a pin arrives with the payments update — the " +
                    "spot, the price and the rotation you see here are " +
                    "already live on the board."
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Got it")
            }
        }
    )
}
