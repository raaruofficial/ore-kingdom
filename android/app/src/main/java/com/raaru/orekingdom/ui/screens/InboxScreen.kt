package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Poll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.raaru.orekingdom.data.InboxPayload
import com.raaru.orekingdom.data.NotificationItem
import com.raaru.orekingdom.data.shortDate
import com.raaru.orekingdom.ui.ForumViewModel
import com.raaru.orekingdom.ui.theme.OnOreFaint
import com.raaru.orekingdom.ui.theme.OreAmber
import com.raaru.orekingdom.ui.theme.OreEdge
import com.raaru.orekingdom.ui.theme.labelCaps
import com.raaru.orekingdom.ui.theme.tnum

/**
 * The Inbox (R10): replies to the member's threads, mentions, and
 * tips received — each with server-held read state (the host's copy
 * is the record; it survives reinstall). Opening an entry marks it
 * read and follows it to its thread when it names one. Nothing
 * addressed to a member depends on them scrolling past it in the
 * river: this list is the whole of what the host holds for them.
 */
@Composable
fun InboxScreen(navController: NavController, vm: ForumViewModel) {
    val inboxState by vm.inboxState.collectAsState()
    val member by vm.member.collectAsState()

    LaunchedEffect(Unit) { vm.loadInbox() }

    Scaffold(
        topBar = {
            OreTopBar("Inbox", navController, showBack = true) {
                TextButton(onClick = { vm.markAllNotificationsRead() }) {
                    Text("Mark all read")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (member == null) {
                Text(
                    "Sign in to see your inbox.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(16.dp)
                )
                return@Column
            }
            when (val state = inboxState) {
                ForumViewModel.LoadState.Loading -> LoadingBox("Loading your inbox…")
                ForumViewModel.LoadState.Unreachable ->
                    HostUnreachable(onRetry = { vm.loadInbox() })
                is ForumViewModel.LoadState.Ready<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val payload = state.value as InboxPayload
                    if (payload.items.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            OreCrownMark(48.dp)
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Nothing here yet. Replies to your threads, " +
                                    "mentions and tips you receive will land here.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(payload.items, key = { it.id }) { item ->
                                InboxRow(item) {
                                    vm.markNotificationRead(item)
                                    item.threadId?.let {
                                        navController.navigate("thread/$it")
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(24.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InboxRow(item: NotificationItem, onClick: () -> Unit) {
    Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (item.read) Modifier else Modifier.background(MaterialTheme.colorScheme.surfaceVariant))
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = inboxTypeIcon(item.type),
                    contentDescription = null,
                    tint = if (item.read) MaterialTheme.colorScheme.onSurfaceVariant else OreAmber,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!item.read) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(OreAmber, CircleShape)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            inboxTypeLabel(item.type),
                            style = MaterialTheme.typography.labelCaps,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            shortDate(item.createdAt),
                            style = MaterialTheme.typography.bodySmall
                                .copy(color = OnOreFaint)
                                .tnum()
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (item.read) FontWeight.Normal else FontWeight.Bold
                    )
                    if (item.detail.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = OreEdge)
    }
}

/** The bundle's notification type enum, in the member's words. */
private fun inboxTypeLabel(type: String): String = when (type) {
    "thread_reply" -> "REPLY"
    "reply_reply" -> "REPLY"
    "tip" -> "TIP"
    "mention" -> "MENTION"
    "founder_poll" -> "POLL"
    "founding_milestone" -> "MILESTONE"
    else -> "NOTICE"
}

private fun inboxTypeIcon(type: String): ImageVector = when (inboxTypeLabel(type)) {
    "REPLY" -> Icons.AutoMirrored.Rounded.Reply
    "TIP" -> Icons.Rounded.Diamond
    "MENTION" -> Icons.Rounded.AlternateEmail
    "POLL" -> Icons.Rounded.Poll
    "MILESTONE" -> Icons.Rounded.EmojiEvents
    else -> Icons.Rounded.Info
}
