package com.raaru.orekingdom.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.raaru.orekingdom.data.shortWallet
import com.raaru.orekingdom.ui.ForumViewModel
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
 */
@Composable
fun SignInScreen(navController: NavController, vm: ForumViewModel, sender: ActivityResultSender) {
    val walletState by vm.walletState.collectAsState()
    val member by vm.member.collectAsState()
    val signingIn by vm.signingIn.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { OreTopBar("Sign in", navController, showBack = true) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Read this first", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
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
                Text(
                    "You're signed in as ${member?.skrName}.",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Wallet ${shortWallet(member?.wallet)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        vm.signOut(sender)
                        navController.popBackStack()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sign out") }
            } else {
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
