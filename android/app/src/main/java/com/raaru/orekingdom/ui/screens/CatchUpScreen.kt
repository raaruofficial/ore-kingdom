package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.raaru.orekingdom.data.shortDate
import com.raaru.orekingdom.ui.ForumViewModel

/**
 * The Catch-Up (R9): one screen, on open, when anything is new since
 * the member's last visit — new threads, replies to their threads,
 * tips received, pin changes. The watermark discipline is the host
 * contract (M2_HOST_REPORT §3): the read never moved it; this screen
 * advances it with the read's `through`, once, after the news has
 * been displayed. Anything that lands while this screen is open is
 * after `through` and stays news for the next open — nothing is
 * silently skipped. If there was no news, this screen never appears.
 */
@Composable
fun CatchUpScreen(navController: NavController, vm: ForumViewModel) {
    val catchUpState by vm.catchUpState.collectAsState()
    val news = (catchUpState as? ForumViewModel.CatchUpState.News)?.catchUp

    // Display first, then advance — the LaunchedEffect runs after the
    // first composition of the news below, and the view model's
    // exactly-once guard makes a recomposition harmless.
    LaunchedEffect(news?.through) {
        if (news != null) vm.advanceCatchUpAfterDisplay()
    }

    Scaffold(topBar = { OreTopBar("Since your last visit", navController, showBack = true) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (news == null) {
                Text(
                    "You're all caught up — nothing new since your last visit.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
                return@Column
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (news.newThreads.isNotEmpty()) {
                    item {
                        SectionHeader("New threads (${news.newThreads.size})")
                    }
                    items(news.newThreads.size) { i ->
                        val thread = news.newThreads[i]
                        CatchUpCard(
                            title = thread.title,
                            meta = "${thread.authorName} · ${thread.categoryName} · " +
                                shortDate(thread.createdAt),
                            onClick = { navController.navigate("thread/${thread.id}") }
                        )
                    }
                }
                if (news.repliesToMine.isNotEmpty()) {
                    item {
                        SectionHeader("Replies to your threads (${news.repliesToMine.size})")
                    }
                    items(news.repliesToMine.size) { i ->
                        val reply = news.repliesToMine[i]
                        CatchUpCard(
                            title = reply.threadTitle,
                            meta = "${reply.authorName} replied · ${shortDate(reply.createdAt)}",
                            onClick = { navController.navigate("thread/${reply.threadId}") }
                        )
                    }
                }
                if (news.tipsReceived.isNotEmpty()) {
                    item {
                        SectionHeader("Tips received (${news.tipsReceived.size})")
                    }
                    items(news.tipsReceived.size) { i ->
                        val tip = news.tipsReceived[i]
                        CatchUpCard(
                            title = "${tip.amountBaseUnits} ${tip.asset} from ${tip.senderName}",
                            meta = "Tip · ${shortDate(tip.createdAt)}",
                            onClick = tip.threadId?.let { id ->
                                { navController.navigate("thread/$id") }
                            }
                        )
                    }
                }
                if (news.pinChanges.isNotEmpty()) {
                    item {
                        SectionHeader("Pin changes (${news.pinChanges.size})")
                    }
                    items(news.pinChanges.size) { i ->
                        val change = news.pinChanges[i]
                        CatchUpCard(
                            title = (change.threadTitle ?: "A thread") +
                                if (change.type == "activated") " was pinned" else " — pin ended",
                            meta = "Pin ${change.type} · " +
                                shortDate(
                                    if (change.type == "activated") change.startsAt
                                    else change.endsAt
                                ),
                            onClick = { navController.navigate("thread/${change.threadId}") }
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { navController.popBackStack() },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) { Text("To the River") }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun CatchUpCard(title: String, meta: String, onClick: (() -> Unit)?) {
    val clickModifier = onClick?.let { handler -> Modifier.clickable { handler() } } ?: Modifier
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .then(clickModifier)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
