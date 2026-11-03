package com.raaru.orekingdom.ui.screens

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
import androidx.compose.material3.OutlinedTextField
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
import com.raaru.orekingdom.data.shortDate
import com.raaru.orekingdom.ui.ForumViewModel
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/**
 * One thread and its replies (getThread). M2 adds the reply composer:
 * a signed-in member's reply rides createReply through the wrapper's
 * gate with their SIWS proof (payment-session wiring is M3; the
 * wrapper's own refusals render verbatim). A locked thread says so
 * and takes no reply. Host refusals render verbatim; a dead
 * connection renders the unreachable state (or saved reading,
 * marked stale) — never blank.
 */
@Composable
fun ThreadScreen(
    navController: NavController,
    vm: ForumViewModel,
    sender: ActivityResultSender,
    threadId: Int
) {
    val threadState by vm.threadState.collectAsState()
    val threadRefusal by vm.threadRefusal.collectAsState()
    val member by vm.member.collectAsState()
    val posting by vm.posting.collectAsState()
    var replyBody by remember { mutableStateOf("") }
    var replyError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(threadId) { vm.loadThread(threadId) }

    Scaffold(topBar = { OreTopBar("Thread", navController, showBack = true) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = threadState) {
                ForumViewModel.LoadState.Loading -> LoadingBox("Loading thread…")
                ForumViewModel.LoadState.Unreachable ->
                    HostUnreachable(onRetry = { vm.loadThread(threadId) }, refusal = threadRefusal)
                is ForumViewModel.LoadState.Ready<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val detail = state.value as com.raaru.orekingdom.data.ThreadDetail
                    val thread = detail.thread
                    if (state.stale) {
                        StaleBanner(onRetry = { vm.loadThread(threadId) })
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    thread.categoryName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    thread.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "${thread.authorName} · ${shortDate(thread.createdAt)} · " +
                                        "${thread.likes} likes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (thread.authorIsFoundingMember) {
                                    Spacer(Modifier.height(4.dp))
                                    FoundingMark()
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(thread.body, style = MaterialTheme.typography.bodyLarge)
                                if (thread.isLocked) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        "This thread is locked.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                Spacer(Modifier.height(20.dp))
                                Text(
                                    "Replies (${detail.replies.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        items(detail.replies, key = { it.id }) { reply ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        "${reply.authorName} · ${shortDate(reply.createdAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (reply.authorIsFoundingMember) {
                                        Spacer(Modifier.height(2.dp))
                                        FoundingMark()
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(reply.body, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        item {
                            if (!thread.isLocked) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    if (member != null) {
                                        OutlinedTextField(
                                            value = replyBody,
                                            onValueChange = { replyBody = it },
                                            label = { Text("Reply") },
                                            minLines = 3,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        if (replyError != null) {
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                replyError!!,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                        Spacer(Modifier.height(12.dp))
                                        Button(
                                            onClick = {
                                                replyError = null
                                                vm.postReply(sender, threadId, replyBody) { err ->
                                                    if (err == null) {
                                                        replyBody = ""
                                                    } else {
                                                        replyError = err
                                                    }
                                                }
                                            },
                                            enabled = !posting && replyBody.isNotBlank(),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(if (posting) "Posting…" else "Post reply")
                                        }
                                    } else {
                                        Text(
                                            "Sign in to reply.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}
