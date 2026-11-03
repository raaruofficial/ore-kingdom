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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.raaru.orekingdom.util.AssetSpec
import com.raaru.orekingdom.util.Assets
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/**
 * One thread and its replies (getThread). The reply composer rides
 * createReply through the wrapper's gate with the member's SIWS
 * proof (M3: the paid path opens only on the gate's own paid answer).
 * Tip entry points ride the host's own flags (canTip on the thread
 * and on each reply) into the tip flow's double opt-in. A locked
 * thread says so and takes no reply. Host refusals render verbatim;
 * a dead connection renders the unreachable state (or saved reading,
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
    val paying by vm.paying.collectAsState()
    var replyBody by remember { mutableStateOf("") }
    var replyError by remember { mutableStateOf<String?>(null) }
    var replyProgress by remember { mutableStateOf<String?>(null) }
    var replyAsset by remember { mutableStateOf<AssetSpec>(Assets.SKR) }

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
                                if (member != null && thread.canTip) {
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedButton(
                                        onClick = {
                                            navController.navigate("tip/${thread.id}/-1")
                                        }
                                    ) { Text("Tip ${thread.authorName}") }
                                }
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
                                    if (member != null && reply.canTip) {
                                        TextButton(
                                            onClick = {
                                                navController.navigate(
                                                    "tip/${thread.id}/${reply.id}"
                                                )
                                            }
                                        ) { Text("Tip ${reply.authorName}") }
                                    }
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
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            "If the host charges for this reply, pay in",
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        AssetPicker(
                                            assets = Assets.MENU,
                                            selected = replyAsset,
                                            enabled = !paying
                                        ) { replyAsset = it }
                                        if (replyProgress != null) {
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                replyProgress!!,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
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
                                                replyProgress = null
                                                vm.postReply(
                                                    sender, threadId, replyBody, replyAsset,
                                                    onProgress = { replyProgress = it },
                                                    onDone = { err ->
                                                        replyProgress = null
                                                        if (err == null) {
                                                            replyBody = ""
                                                        } else {
                                                            replyError = err
                                                        }
                                                    }
                                                )
                                            },
                                            enabled = !paying && replyBody.isNotBlank(),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(if (paying) "Posting…" else "Post reply")
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
