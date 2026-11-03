package com.raaru.orekingdom.ui.screens

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.raaru.orekingdom.R
import com.raaru.orekingdom.data.ForumThread
import com.raaru.orekingdom.data.shortDate
import com.raaru.orekingdom.ui.theme.OreAmber
import com.raaru.orekingdom.ui.theme.OreBackground
import com.raaru.orekingdom.ui.theme.OreBronze
import com.raaru.orekingdom.ui.theme.OreEdge
import com.raaru.orekingdom.ui.theme.OreAmberBright
import com.raaru.orekingdom.ui.theme.OreGem
import com.raaru.orekingdom.ui.theme.OnOreFaint
import com.raaru.orekingdom.ui.theme.labelCaps
import com.raaru.orekingdom.ui.theme.tnum
import kotlinx.coroutines.delay

/** Listing disambiguation (rebuild census item 15): independent
 *  community forum, not the protocol's. Stands in the sign-in footer. */
const val DISCLAIMER =
    "Ore Kingdom is an independent community forum for ORE miners. " +
        "It is not affiliated with the ORE protocol or Regolith Labs."

/** The brand mark: the concept-4 pixel crown, shipped as a drawable.
 *  Pixel and untouched — never tinted, never rotated, never redrawn
 *  as vector (FLAGSHIP_DESIGN §5). Exactly three sizes in the app:
 *  28dp top-bar hallmark, 72dp sign-in plinth, 48dp empty states. */
@Composable
fun OreCrownMark(size: Dp = 48.dp) {
    Image(
        painter = painterResource(R.drawable.ore_crown),
        contentDescription = null,
        modifier = Modifier.size(size)
    )
}

/** True when the system asks for reduced motion (animator scale 0):
 *  transitions become cuts and the spotlight stops rotating
 *  (FLAGSHIP_DESIGN §6). Read once per composition. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    }
}

/** The ore seam (FLAGSHIP_DESIGN §2): the app's one gradient — a
 *  bronze → amber → bright hairline across the top edge of a hero
 *  or plaque. Three homes: the pin spotlight, the join/prestige
 *  hero cards, the sign-in crown plinth. Nowhere else. */
@Composable
fun OreSeamEdge(topRadius: Dp = 18.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(
                Brush.horizontalGradient(listOf(OreBronze, OreAmber, OreAmberBright)),
                RoundedCornerShape(topStart = topRadius, topEnd = topRadius)
            )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OreTopBar(
    title: String,
    navController: NavController,
    showBack: Boolean = false,
    brand: Boolean = false,
    actions: @Composable () -> Unit = {}
) {
    TopAppBar(
        title = {
            if (brand) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OreCrownMark(28.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }
            } else {
                Text(title, fontWeight = FontWeight.Bold)
            }
        },
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = "Back")
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = OreBackground)
    )
}

/** The state family (FLAGSHIP_DESIGN §7/§9): one card language for
 *  unreachable / stale / loading — vector icon, hairline, the same
 *  retry affordance. The wording of every state is frozen; only the
 *  layout is the overhaul's. */
@Composable
fun HostUnreachable(onRetry: () -> Unit, refusal: String? = null) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, OreEdge),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    refusal ?: "Ore Kingdom could not load — the forum did not answer.",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        SkeletonBar(1f)
        Spacer(Modifier.height(10.dp))
        SkeletonBar(0.85f)
        Spacer(Modifier.height(10.dp))
        SkeletonBar(0.6f)
    }
}

/** A static skeleton bar (no shimmer loop, FLAGSHIP_DESIGN §6). */
@Composable
private fun SkeletonBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction)
            .height(14.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(7.dp)
            )
    )
}

@Composable
fun StaleBanner(onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, OreEdge),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(36.dp)
                    .background(OreAmber, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.width(12.dp))
            Icon(
                Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
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
 *  a number the payload didn't serve. Dress (FLAGSHIP_DESIGN §7):
 *  gem diamond + the caps eyebrow voice, in the gem colour. */
@Composable
fun FoundingMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Rounded.Diamond,
            contentDescription = null,
            tint = OreGem,
            modifier = Modifier.size(11.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "FOUNDING MEMBER",
            style = MaterialTheme.typography.labelCaps,
            color = OreGem
        )
    }
}

@Composable
fun ThreadRow(thread: ForumThread, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, OreEdge),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (thread.isPaidPinned || thread.isPinned) {
                    Box(
                        modifier = Modifier
                            .background(OreAmber, RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.PushPin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "PINNED",
                                style = MaterialTheme.typography.labelCaps,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    thread.categoryName.uppercase(),
                    style = MaterialTheme.typography.labelCaps,
                    color = OnOreFaint
                )
                Spacer(Modifier.weight(1f))
                Text(
                    shortDate(thread.createdAt),
                    style = MaterialTheme.typography.bodySmall.tnum(),
                    color = OnOreFaint
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                thread.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (thread.body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    thread.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    thread.authorName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${thread.replies} replies",
                    style = MaterialTheme.typography.bodySmall.tnum(),
                    color = OnOreFaint
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${thread.likes} likes",
                    style = MaterialTheme.typography.bodySmall.tnum(),
                    color = OnOreFaint
                )
                if (thread.isLocked) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "locked",
                        style = MaterialTheme.typography.bodySmall,
                        color = OnOreFaint
                    )
                }
                if (thread.authorIsFoundingMember) {
                    Spacer(Modifier.width(10.dp))
                    FoundingMark()
                }
                if (thread.isCommercial) {
                    Spacer(Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .border(1.dp, OreAmber, RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            "COMMERCIAL",
                            style = MaterialTheme.typography.labelCaps,
                            color = OreAmber
                        )
                    }
                }
            }
        }
    }
}

/** One spotlight slot as the strip renders it — mapped by the River
 *  from the getPinSlots slot-state read. The strip renders only
 *  what this host read serves. */
sealed interface PinSlotUi {
    data class Filled(val threadId: Int, val title: String, val endsLabel: String) : PinSlotUi
    data object Vacant : PinSlotUi
}

/**
 * The pin spotlight (pin spec): the live pin slots rotate through this
 * one position above the River with equal exposure; a vacant slot is
 * the house availability ad — spot, $250 / 12 hours, and the take-it
 * action, which opens the pin purchase (M3). An empty slot list
 * renders nothing; otherwise the served getPinSlots list carries the
 * ruled capacity.
 *
 * Dress (FLAGSHIP_DESIGN §8.2): the flagship surface of the River —
 * vein ground, the ore-seam top edge, the 6-second rotation kept at
 * its cadence but crossfading instead of cutting. Under reduced
 * motion the rotation stops and the first slot stands (proposal P2:
 * a manual way to reach the other slot is a functional addition,
 * not assumed here).
 */
@Composable
fun PinSpotlight(
    slots: List<PinSlotUi>,
    onOpenThread: (Int) -> Unit,
    onTakeSpot: () -> Unit
) {
    val reducedMotion = rememberReducedMotion()
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(slots.size, reducedMotion) {
        index = 0
        while (slots.size > 1 && !reducedMotion) {
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
        border = BorderStroke(1.dp, OreEdge),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        OreSeamEdge()
        AnimatedContent(
            targetState = slot,
            transitionSpec = {
                val duration = if (reducedMotion) 0 else 200
                fadeIn(tween(duration, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(duration, easing = FastOutSlowInEasing))
            },
            label = "pinSpotlight"
        ) { current ->
            when (current) {
                is PinSlotUi.Filled -> {
                    Column(
                        modifier = Modifier
                            .clickable { onOpenThread(current.threadId) }
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.PushPin,
                                contentDescription = null,
                                tint = OreAmber,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "PAID PIN",
                                style = MaterialTheme.typography.labelCaps,
                                color = OreAmber
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "until ${current.endsLabel}",
                                style = MaterialTheme.typography.bodySmall.tnum(),
                                color = OnOreFaint
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            current.title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap to open the pinned thread",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                PinSlotUi.Vacant -> {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OreCrownMark(28.dp)
                            Spacer(Modifier.width(10.dp))
                            Icon(
                                Icons.Rounded.PushPin,
                                contentDescription = null,
                                tint = OreAmber,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "PIN SPOT AVAILABLE",
                                style = MaterialTheme.typography.labelCaps,
                                color = OreAmber
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Pin your thread at the top of the kingdom —",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                "$250",
                                style = MaterialTheme.typography.titleLarge,
                                color = OreAmber,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "for 12 hours.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onTakeSpot) { Text("Take this spot") }
                    }
                }
            }
        }
    }
}
