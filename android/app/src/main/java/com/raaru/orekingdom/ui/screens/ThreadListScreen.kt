package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.raaru.orekingdom.data.ForumPayload
import com.raaru.orekingdom.data.ServedPinSlot
import com.raaru.orekingdom.data.shortDate
import com.raaru.orekingdom.ui.ForumViewModel
import com.raaru.orekingdom.ui.theme.OreAmber
import com.raaru.orekingdom.ui.theme.OreEdge
import com.raaru.orekingdom.ui.theme.OreGem
import com.raaru.orekingdom.ui.theme.OreGemDim
import com.raaru.orekingdom.ui.theme.OreSurfaceRaised
import com.raaru.orekingdom.ui.theme.OreVein
import com.raaru.orekingdom.ui.theme.labelCaps
import com.raaru.orekingdom.ui.theme.tnum
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
            OreTopBar("Ore Kingdom", navController, brand = true) {
                IconButton(onClick = { vm.loadForum() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                }
                Spacer(Modifier.width(4.dp))
                if (member != null) {
                    BadgedBox(
                        badge = {
                            if (unreadCount > 0) {
                                Badge(
                                    containerColor = OreAmber,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ) {
                                    Text(
                                        "$unreadCount",
                                        style = MaterialTheme.typography.bodySmall.tnum()
                                    )
                                }
                            }
                        }
                    ) {
                        IconButton(onClick = { navController.navigate("inbox") }) {
                            Icon(Icons.Rounded.Notifications, contentDescription = "Inbox")
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, OreEdge),
                    modifier = Modifier.clickable { navController.navigate("signin") }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        if (member?.isFoundingMember == true) {
                            Icon(
                                Icons.Rounded.Diamond,
                                contentDescription = null,
                                modifier = Modifier.size(10.dp),
                                tint = OreGem
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            member?.skrName ?: "Sign in",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
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
                    val boardView = riverView as? ForumViewModel.RiverView.Board
                    val selectedBoardCategory = boardView?.let { view ->
                        payload.categories.firstOrNull { it.id == view.categoryId }
                    }
                    // Filter views over the one payload.
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        FilterChip(
                            selected = riverView is ForumViewModel.RiverView.All,
                            onClick = { vm.selectView(ForumViewModel.RiverView.All) },
                            label = { Text("All") },
                            shape = RoundedCornerShape(50),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = OreAmber,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (riverView is ForumViewModel.RiverView.All) OreAmber else OreEdge
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = riverView is ForumViewModel.RiverView.Commercial,
                            onClick = { vm.selectView(ForumViewModel.RiverView.Commercial) },
                            label = { Text("Commercial") },
                            shape = RoundedCornerShape(50),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = OreAmber,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (riverView is ForumViewModel.RiverView.Commercial) OreAmber else OreEdge
                            )
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
                                    if (category.isVip) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                Icons.Rounded.Lock,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "${category.name} (${category.count})",
                                                style = MaterialTheme.typography.bodyMedium.tnum()
                                            )
                                        }
                                    } else {
                                        Text(
                                            "${category.name} (${category.count})",
                                            style = MaterialTheme.typography.bodyMedium.tnum()
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(50),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = OreAmber,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    if ((riverView as? ForumViewModel.RiverView.Board)?.categoryId == category.id) OreAmber else OreEdge
                                )
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
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = OreAmber,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
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
                                containerColor = OreSurfaceRaised
                            ),
                            border = BorderStroke(1.dp, OreEdge),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(44.dp)
                                        .background(OreAmber, RoundedCornerShape(2.dp))
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
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
                                            verticalAlignment = Alignment.CenterVertically
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
                    }
                    // The pin spotlight renders above the river,
                    // whatever view is selected. Its state comes only
                    // from the getPinSlots read; while that read has
                    // not answered, no strip renders.
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
                    } ?: emptyList()
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
                    val onVipBoard = selectedBoardCategory?.isVip == true
                    if (onVipBoard && visibleThreads.isEmpty()) {
                        VipLockedCard(
                            boardName = selectedBoardCategory!!.name,
                            signedIn = member != null,
                            onVerify = {
                                vm.refreshWithProof(sender) { /* errors surface on retry */ }
                            },
                            onSignIn = { navController.navigate("signin") }
                        )
                    } else if (visibleThreads.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            OreCrownMark(size = 48.dp)
                            Spacer(Modifier.height(16.dp))
                            Text(
                                when (riverView) {
                                    ForumViewModel.RiverView.Commercial ->
                                        "No commercial threads right now."
                                    else -> "No threads here yet."
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                                                "UNANSWERED — WAITING LONGEST FIRST",
                                                style = MaterialTheme.typography.labelCaps,
                                                color = OreAmber,
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
                                                        border = BorderStroke(1.dp, OreEdge),
                                                        modifier = Modifier
                                                            .width(248.dp)
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
                                                                "Waiting since " +
                                                                    shortDate(thread.createdAt),
                                                                style = MaterialTheme
                                                                    .typography.bodyMedium
                                                                    .tnum(),
                                                                fontWeight = FontWeight.SemiBold,
                                                                color = OreAmber
                                                            )
                                                            Spacer(Modifier.height(2.dp))
                                                            Text(
                                                                thread.authorName,
                                                                style = MaterialTheme
                                                                    .typography.bodySmall,
                                                                color = MaterialTheme.colorScheme
                                                                    .onSurfaceVariant
                                                            )
                                                            Spacer(Modifier.height(4.dp))
                                                            Row(
                                                                verticalAlignment =
                                                                    Alignment.CenterVertically
                                                            ) {
                                                                Icon(
                                                                    Icons.Rounded.Reply,
                                                                    contentDescription = null,
                                                                    modifier = Modifier.size(13.dp),
                                                                    tint = OreAmber
                                                                )
                                                                Spacer(Modifier.width(4.dp))
                                                                Text(
                                                                    "Be the first reply",
                                                                    style = MaterialTheme
                                                                        .typography.bodySmall,
                                                                    color = OreAmber,
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
            containerColor = OreVein
        ),
        border = BorderStroke(1.dp, OreGemDim),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .border(1.dp, OreGem, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = OreGem
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    boardName,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
            }
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
                OutlinedButton(
                    onClick = onVerify,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, OreGem),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OreGem)
                ) { Text("Verify with sign-in proof") }
            } else {
                Button(
                    onClick = onSignIn,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OreAmber,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) { Text("Sign in") }
            }
        }
    }
}
