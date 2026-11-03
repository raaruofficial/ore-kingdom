package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.raaru.orekingdom.data.ForumPayload
import com.raaru.orekingdom.data.ServedPinSlot
import com.raaru.orekingdom.data.pinSlots
import com.raaru.orekingdom.data.shortDate
import com.raaru.orekingdom.data.vipCategory
import com.raaru.orekingdom.ui.ForumViewModel
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/**
 * The River (R7): one main feed for the kingdom, in the host's
 * last-activity order (a reply bumps its thread; reactions never do —
 * the ordering is the host's, maintained wrapper-side). Boards are
 * filter views over the one payload (All / Commercial / per-board);
 * the VIP board is the gated surface among them. Above the feed: the
 * parked-payments card (R4, when any exist), the pin spotlight in
 * rotation (state from the getPinSlots read; vacant slots as the
 * house ad, whose action opens the pin purchase) and the Unanswered
 * Rail (R8). On open, a signed-in member with news is taken to the
 * Catch-Up (R9) exactly once per open-with-news.
 */
@Composable
fun ThreadListScreen(
    navController: NavController,
    vm: ForumViewModel,
    sender: ActivityResultSender
) {
    val forumState by vm.forumState.collectAsState()
    val railState by vm.railState.collectAsState()
    val member by vm.member.collectAsState()
    val riverView by vm.riverView.collectAsState()
    val unreadCount by vm.unreadCount.collectAsState()
    val catchUpState by vm.catchUpState.collectAsState()
    val servedPinSlots by vm.pinSlots.collectAsState()
    val parkedFlows by vm.parkedFlows.collectAsState()
    val paying by vm.paying.collectAsState()
    var resumeProgress by remember { mutableStateOf<String?>(null) }
    var resumeMessage by remember { mutableStateOf<String?>(null) }
    var catchUpPresented by remember { mutableStateOf(false) }

    // R9: on open with a signed-in member, ask the host what's new.
    LaunchedEffect(member?.wallet) {
        if (member != null) vm.checkCatchUp(sender)
    }
    // News present -> present the Catch-Up once; if nothing is new,
    // no screen appears and the member lands here on the River.
    LaunchedEffect(catchUpState) {
        if (catchUpState is ForumViewModel.CatchUpState.News && !catchUpPresented) {
            catchUpPresented = true
            navController.navigate("catchup")
        }
        if (catchUpState is ForumViewModel.CatchUpState.NoNews) {
            catchUpPresented = false
        }
    }

    Scaffold(
        topBar = {
            OreTopBar("Ore Kingdom", navController) {
                IconButton(onClick = { vm.loadForum() }) {
                    Text("↻", style = MaterialTheme.typography.titleLarge)
                }
                if (member != null) {
                    TextButton(onClick = { navController.navigate("inbox") }) {
                        Text(
                            if (unreadCount > 0) "Inbox ($unreadCount)" else "Inbox"
                        )
                    }
                }
                TextButton(
                    onClick = { navController.navigate("signin") }
                ) {
                    Text(member?.skrName ?: "Sign in")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = forumState) {
                ForumViewModel.LoadState.Loading -> LoadingBox()
                ForumViewModel.LoadState.Unreachable ->
                    HostUnreachable(onRetry = { vm.loadForum() })
                is ForumViewModel.LoadState.Ready<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val payload = state.value as ForumPayload
                    if (state.stale) {
                        StaleBanner(onRetry = { vm.loadForum() })
                    }
                    val vip = vipCategory(payload)
                    // Filter views over the one payload.
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        FilterChip(
                            selected = riverView is ForumViewModel.RiverView.All,
                            onClick = { vm.selectView(ForumViewModel.RiverView.All) },
                            label = { Text("All") }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = riverView is ForumViewModel.RiverView.Commercial,
                            onClick = { vm.selectView(ForumViewModel.RiverView.Commercial) },
                            label = { Text("Commercial") }
                        )
                        payload.categories.forEach { category ->
                            Spacer(Modifier.width(8.dp))
                            FilterChip(
                                selected =
                                    (riverView as? ForumViewModel.RiverView.Board)?.categoryId == category.id,
                                onClick = {
                                    vm.selectView(ForumViewModel.RiverView.Board(category.id))
                                },
                                label = {
                                    Text(
                                        (if (category.id == vip?.id) "🔒 " else "") +
                                            "${category.name} (${category.count})"
                                    )
                                }
                            )
                        }
                    }
                    if (member != null) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Button(
                                onClick = {
                                    val preselect =
                                        (riverView as? ForumViewModel.RiverView.Board)?.categoryId ?: 0
                                    navController.navigate("compose/$preselect")
                                }
                            ) { Text("New thread") }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    // R4: parked payments (started earlier, not yet
                    // settled with the host) are offered for
                    // completion above the river. Finishing one never
                    // pays again — it completes what was already paid.
                    if (member != null && parkedFlows.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    "Payments to finish",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Started earlier and not yet settled with the host. " +
                                        "Finishing one completes what you already paid " +
                                        "for — it never charges again.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                parkedFlows.forEach { flow ->
                                    Row(
                                        verticalAlignment =
                                            androidx.compose.ui.Alignment.CenterVertically
                                    ) {
                                        Text(
                                            flow.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = {
                                                resumeMessage = null
                                                resumeProgress = null
                                                vm.resumeParked(
                                                    sender, flow,
                                                    onProgress = { resumeProgress = it },
                                                    onDone = { err ->
                                                        resumeProgress = null
                                                        resumeMessage = err
                                                            ?: "Done — that payment is settled."
                                                    }
                                                )
                                            },
                                            enabled = !paying
                                        ) { Text("Finish") }
                                        TextButton(
                                            onClick = { vm.dismissParked(flow) },
                                            enabled = !paying
                                        ) { Text("Dismiss") }
                                    }
                                }
                                resumeProgress?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                                resumeMessage?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text(it, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    // The pin spotlight renders above the river,
                    // whatever view is selected. Its state comes from
                    // the getPinSlots read (the slot-state source of
                    // record since the M2 host fixes); while that read
                    // has not answered, the payload derivation fills in.
                    val spotlightSlots: List<PinSlotUi> = servedPinSlots?.let { served ->
                        served.slots.mapNotNull { slot ->
                            when (slot) {
                                is ServedPinSlot.Filled -> slot.threadId.toIntOrNull()?.let { id ->
                                    PinSlotUi.Filled(
                                        threadId = id,
                                        title = slot.threadTitle,
                                        endsLabel = formatEpochMs(slot.endsAt)
                                    )
                                }
                                ServedPinSlot.Vacant -> PinSlotUi.Vacant
                            }
                        }
                    } ?: pinSlots(payload).map { slot ->
                        when (slot) {
                            is com.raaru.orekingdom.data.PinSlot.Filled -> PinSlotUi.Filled(
                                threadId = slot.thread.id,
                                title = slot.thread.title,
                                endsLabel = shortDate(slot.thread.paidPinEndsAt)
                            )
                            com.raaru.orekingdom.data.PinSlot.Vacant -> PinSlotUi.Vacant
                        }
                    }
                    PinSpotlight(
                        slots = spotlightSlots,
                        onOpenThread = { navController.navigate("thread/$it") },
                        onTakeSpot = {
                            if (member != null) {
                                navController.navigate("pinbuy")
                            } else {
                                navController.navigate("signin")
                            }
                        }
                    )
                    val visibleThreads = when (val view = riverView) {
                        ForumViewModel.RiverView.All -> payload.threads
                        ForumViewModel.RiverView.Commercial ->
                            payload.threads.filter { it.isCommercial }
                        is ForumViewModel.RiverView.Board ->
                            payload.threads.filter { it.categoryId == view.categoryId }
                    }
                    val boardView = riverView as? ForumViewModel.RiverView.Board
                    val onVipBoard = boardView != null && vip != null &&
                        boardView.categoryId == vip.id
                    if (onVipBoard && visibleThreads.isEmpty()) {
                        VipLockedCard(
                            boardName = vip!!.name,
                            signedIn = member != null,
                            onVerify = {
                                vm.refreshWithProof(sender) { /* errors surface on retry */ }
                            },
                            onSignIn = { navController.navigate("signin") }
                        )
                    } else if (visibleThreads.isEmpty()) {
                        Text(
                            when (riverView) {
                                ForumViewModel.RiverView.Commercial ->
                                    "No commercial threads right now."
                                else -> "No threads here yet."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            if (riverView is ForumViewModel.RiverView.All) {
                                val rail = railState
                                if (rail is ForumViewModel.LoadState.Ready<*>) {
                                    @Suppress("UNCHECKED_CAST")
                                    val railThreads =
                                        rail.value as List<com.raaru.orekingdom.data.ForumThread>
                                    if (railThreads.isNotEmpty()) {
                                        item(key = "rail-header") {
                                            Text(
                                                "Unanswered — waiting longest first",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(
                                                    start = 16.dp, top = 12.dp, bottom = 4.dp
                                                )
                                            )
                                        }
                                        item(key = "rail-row") {
                                            LazyRow(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 4.dp)
                                            ) {
                                                items(railThreads, key = { it.id }) { thread ->
                                                    Card(
                                                        colors = CardDefaults.cardColors(
                                                            containerColor =
                                                                MaterialTheme.colorScheme.surfaceVariant
                                                        ),
                                                        modifier = Modifier
                                                            .width(240.dp)
                                                            .padding(start = 16.dp, end = 4.dp)
                                                            .clickable {
                                                                navController.navigate(
                                                                    "thread/${thread.id}"
                                                                )
                                                            }
                                                    ) {
                                                        Column(Modifier.padding(12.dp)) {
                                                            Text(
                                                                thread.title,
                                                                style = MaterialTheme
                                                                    .typography.titleSmall,
                                                                fontWeight = FontWeight.Bold,
                                                                maxLines = 2,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            Spacer(Modifier.height(4.dp))
                                                            Text(
                                                                "${thread.authorName} · " +
                                                                    "waiting since " +
                                                                    shortDate(thread.createdAt),
                                                                style = MaterialTheme
                                                                    .typography.bodySmall,
                                                                color = MaterialTheme.colorScheme
                                                                    .onSurfaceVariant
                                                            )
                                                            Spacer(Modifier.height(4.dp))
                                                            Text(
                                                                "Be the first reply",
                                                                style = MaterialTheme
                                                                    .typography.bodySmall,
                                                                color = MaterialTheme.colorScheme
                                                                    .primary,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            items(visibleThreads, key = { it.id }) { thread ->
                                ThreadRow(thread) {
                                    navController.navigate("thread/${thread.id}")
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

/** The VIP board as non-holders see it: listed, named, locked — the
 *  board's existence is public, its content is not (the host withholds
 *  the threads; this card is the honest rendering of that answer). */
@Composable
private fun VipLockedCard(
    boardName: String,
    signedIn: Boolean,
    onVerify: () -> Unit,
    onSignIn: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🔒 $boardName",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "This board is for prestige holders. Its threads are " +
                    "withheld until the host verifies a holder's sign-in — " +
                    "there is nothing to scroll past here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (signedIn) {
                Button(onClick = onVerify) { Text("Verify with sign-in proof") }
            } else {
                Button(onClick = onSignIn) { Text("Sign in") }
            }
        }
    }
}
