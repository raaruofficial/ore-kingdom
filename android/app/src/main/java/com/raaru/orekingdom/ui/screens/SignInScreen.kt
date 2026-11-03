package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.raaru.orekingdom.data.shortWallet
import com.raaru.orekingdom.ui.ForumViewModel
import com.raaru.orekingdom.ui.theme.OreEdge
import com.raaru.orekingdom.ui.theme.OreGem
import com.raaru.orekingdom.ui.theme.OreSurfaceRaised
import com.raaru.orekingdom.ui.theme.OnOreFaint
import com.raaru.orekingdom.ui.theme.labelCaps
import com.raaru.orekingdom.ui.theme.tnum
import com.raaru.orekingdom.util.SiwsMessage
import com.raaru.orekingdom.wallet.WalletUiState
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/**
 * Sign-in. The order on this screen is the contract: the wallet-
 * linkability disclosure is read BEFORE any wallet action exists, and
 * the same words are the SIWS message statement the wallet signs
 * (util/SiwsMessage). Sign-in is two explicit steps in one flow —
 * connect, then sign — against the host's single-shot authenticateSiws.
 * Refusals are carried verbatim (the host's own words, e.g. its .skr
 * requirement). Reading never needs any of this.
 *
 * Dress (FLAGSHIP_DESIGN §8.9): the crown plinth over the disclosure
 * plaque, a quiet two-dot step indicator for the two steps, the
 * signed-in state as an account plaque, the join offer as a hero
 * card, the prestige entry gem-outlined. Every word is unchanged.
 */
@Composable
fun SignInScreen(navController: NavController, vm: ForumViewModel, sender: ActivityResultSender) {
    val walletState by vm.walletState.collectAsState()
    val member by vm.member.collectAsState()
    val signingIn by vm.signingIn.collectAsState()
    val hostMember by vm.hostMember.collectAsState()
    val membershipChecked by vm.membershipChecked.collectAsState()
    val prestigeHolder by vm.isPrestigeHolder.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { OreTopBar("Sign in", navController, showBack = true) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // The crown plinth: the brand mark on the ore seam,
            // above the disclosure it introduces.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OreCrownMark(72.dp)
                Spacer(Modifier.height(12.dp))
                OreSeamEdge(topRadius = 2.dp)
            }
            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = OreSurfaceRaised),
                border = BorderStroke(1.dp, OreEdge),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "READ THIS FIRST",
                        style = MaterialTheme.typography.labelCaps,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(SiwsMessage.DISCLOSURE, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Reading is open to everyone — no wallet needed. To take part, " +
                            "sign in with a Solana wallet holding a .skr name; that name " +
                            "becomes your forum name. Signing moves no funds and grants " +
                            "no spending rights — it proves the wallet is yours.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(Modifier.height(20.dp))

            if (member != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = OreSurfaceRaised),
                    border = BorderStroke(1.dp, OreEdge),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "SIGNED IN",
                            style = MaterialTheme.typography.labelCaps,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            member?.skrName ?: "",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Wallet ${shortWallet(member?.wallet)}",
                            style = MaterialTheme.typography.bodyMedium.tnum().copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (member?.isFoundingMember == true) {
                            Spacer(Modifier.height(8.dp))
                            FoundingMark()
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (membershipChecked && hostMember == null) {
                    // R1: signed in, but the host has no member record
                    // for this wallet — the join gate's offer. Reading
                    // stays open either way; taking part needs the join.
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        border = BorderStroke(1.dp, OreEdge),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OreSeamEdge(topRadius = 20.dp)
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                "This wallet hasn't joined the kingdom yet.",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Reading is open to everyone. Posting, tipping and " +
                                    "pinning are for members — membership is one " +
                                    "payment of 5,000 SKR.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { navController.navigate("join") },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Join the kingdom — 5,000 SKR") }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (hostMember != null) {
                    OutlinedButton(
                        onClick = { navController.navigate("prestige") },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, OreGem),
                        colors = androidx.compose.material3.ButtonDefaults
                            .outlinedButtonColors(contentColor = OreGem)
                    ) {
                        Icon(
                            Icons.Rounded.WorkspacePremium,
                            contentDescription = null,
                            tint = OreGem,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (prestigeHolder == true) {
                                "Prestige — you hold it"
                            } else {
                                "Prestige — 10,000 SKR, 100 places"
                            }
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedButton(
                    onClick = {
                        vm.signOut(sender)
                        navController.popBackStack()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sign out") }
            } else {
                // The two explicit steps, as dots: connect, then sign.
                val onSecondStep = walletState is WalletUiState.Connected
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        androidx.compose.foundation.layout.Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StepDot(active = !onSecondStep)
                    Spacer(Modifier.width(8.dp))
                    StepDot(active = onSecondStep)
                }
                Spacer(Modifier.height(16.dp))
                when (val st = walletState) {
                    WalletUiState.Working -> {
                        Text(
                            "Waiting for wallet… approve in your wallet app.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    is WalletUiState.Error -> {
                        Text(st.message, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        SignInButton(vm, sender, signingIn) { error = it }
                    }
                    is WalletUiState.Connected -> {
                        Text(
                            "Wallet connected: ${shortWallet(st.publicKeyBase58)}",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(12.dp))
                        SignInButton(vm, sender, signingIn, label = "Sign the sign-in message") {
                            error = it
                        }
                    }
                    WalletUiState.Disconnected -> {
                        SignInButton(vm, sender, signingIn) { error = it }
                    }
                }
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "No wallet on this device? Reading stays fully open — go back and browse.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
            Text(
                DISCLAIMER,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StepDot(active: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(
                if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    OnOreFaint
                },
                CircleShape
            )
    )
}

@Composable
private fun SignInButton(
    vm: ForumViewModel,
    sender: ActivityResultSender,
    signingIn: Boolean,
    label: String = "Connect wallet & sign in",
    onError: (String?) -> Unit
) {
    Button(
        onClick = {
            onError(null)
            vm.signIn(sender) { err -> onError(err) }
        },
        enabled = !signingIn,
        modifier = Modifier.fillMaxWidth()
    ) { Text(if (signingIn) "Waiting for wallet…" else label) }
}
