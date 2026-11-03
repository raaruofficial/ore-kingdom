package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.raaru.orekingdom.data.shortDate
import com.raaru.orekingdom.ui.ForumViewModel
import com.raaru.orekingdom.ui.theme.OreEdge
import com.raaru.orekingdom.ui.theme.OreGem
import com.raaru.orekingdom.ui.theme.OreSurfaceRaised
import com.raaru.orekingdom.ui.theme.labelCaps
import com.raaru.orekingdom.ui.theme.tnum
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
 *
 * Dress (FLAGSHIP_DESIGN §8.6): the OP is a hero block, replies a
 * hairline stream, the reply composer a bottom block on the raised
 * rung so "where do I write" is never in doubt. Content, order,
 * gates, and words are unchanged.
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
                                    thread.categoryName.uppercase(),
                                    style = MaterialTheme.typography.labelCaps,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    thread.title,
                                    style = MaterialTheme.typography.headlineSmall
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        thread.authorName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (thread.authorIsFoundingMember) {
                                        Spacer(Modifier.width(8.dp))
                                        FoundingMark()
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "${shortDate(thread.createdAt)} · ${thread.likes} likes",
                                        style = MaterialTheme.typography.bodySmall.tnum(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(thread.body, style = MaterialTheme.typography.bodyLarge)
                                if (member != null && thread.canTip) {
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedButton(
                                        onClick = {
                                            navController.navigate("tip/${thread.id}/-1")
                                        }
                                    ) {
                                        Icon(
                                            Icons.Rounded.Diamond,
                                            contentDescription = null,
                                            tint = OreGem,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Tip ${thread.authorName}")
                                    }
                                }
                                if (thread.isLocked) {
                                    Spacer(Modifier.height(16.dp))
                                    Card(
                                        colors = CardDefaults.cardColors(
                                            containerColor =
                                                MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                        border = BorderStroke(1.dp, OreEdge),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement =
                                                androidx.compose.foundation.layout
                                                    .Arrangement.Center
                                        ) {
                                            Icon(
                                                Icons.Rounded.Lock,
                                                contentDescription = null,
                                                tint =
                                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "This thread is locked.",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(20.dp))
                                Text(
                                    "Replies (${detail.replies.size})",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                        items(detail.replies, key = { it.id }) { reply ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            reply.authorName,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (reply.authorIsFoundingMember) {
                                            Spacer(Modifier.width(8.dp))
                                            FoundingMark()
                                        }
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            shortDate(reply.createdAt),
                                            style = MaterialTheme.typography.bodySmall.tnum(),
                                            color =
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
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
                                        ) {
                                            Icon(
                                                Icons.Rounded.Diamond,
                                                contentDescription = null,
                                                tint = OreGem,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text("Tip ${reply.authorName}")
                                        }
                                    }
                                }
                                HorizontalDivider(color = OreEdge)
                            }
                        }
                        item {
                            if (!thread.isLocked) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    if (member != null) {
                                        Card(
                                            colors = CardDefaults.cardColors(
                                                containerColor = OreSurfaceRaised
                                            ),
                                            border = BorderStroke(1.dp, OreEdge),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(16.dp)) {
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
                                                        style =
                                                            MaterialTheme.typography.bodyMedium
                                                    )
                                                }
                                                if (replyError != null) {
                                                    Spacer(Modifier.height(8.dp))
                                                    Text(
                                                        replyError!!,
                                                        style =
                                                            MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                                Spacer(Modifier.height(12.dp))
                                                Button(
                                                    onClick = {
                                                        replyError = null
                                                        replyProgress = null
                                                        vm.postReply(
                                                            sender, threadId, replyBody,
                                                            replyAsset,
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
                                                    Text(
                                                        if (paying) "Posting…" else "Post reply"
                                                    )
                                                }
                                            }
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
