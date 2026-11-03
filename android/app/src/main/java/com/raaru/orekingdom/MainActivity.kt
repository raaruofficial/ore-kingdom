package com.raaru.orekingdom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.raaru.orekingdom.ui.ForumViewModel
import com.raaru.orekingdom.ui.screens.SignInScreen
import com.raaru.orekingdom.ui.screens.ThreadListScreen
import com.raaru.orekingdom.ui.screens.ThreadScreen
import com.raaru.orekingdom.ui.theme.OreKingdomTheme
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

/**
 * Single-activity app; all navigation is native Compose Navigation.
 * There is no WebView anywhere in this app — the native route replaces
 * the v0.1.3 webshell outright (NATIVE_CLIENT_PLAN).
 */
class MainActivity : ComponentActivity() {

    /**
     * The single MWA activity-result sender (KFC-001): constructed exactly
     * once, here in onCreate before setContent — never during composition.
     * The shell of record's cure, inherited by construction.
     */
    lateinit var walletSender: ActivityResultSender
        private set

    override fun onResume() {
        super.onResume()
        // KFC-008 foreground reconciliation (scoped, v0.1.19 shape).
        (application as OreKingdomApp).walletManager.onReturnedToForeground()
    }

    override fun onStop() {
        // KFC-008: snapshot the generation pending at backgrounding; the
        // reconciler may resolve only that one.
        (application as OreKingdomApp).walletManager.onAppBackgrounded()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        walletSender = ActivityResultSender(this)
        enableEdgeToEdge()
        val recovered = intent?.getBooleanExtra(OreKingdomApp.EXTRA_RECOVERED, false) == true ||
            (application as OreKingdomApp).consumeRecoveryMarker()
        setContent {
            OreKingdomTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val vm: ForumViewModel = viewModel()
                    var showRecovery by remember { mutableStateOf(recovered) }
                    if (showRecovery) {
                        AlertDialog(
                            onDismissRequest = { showRecovery = false },
                            title = { Text("Ore Kingdom hit a snag") },
                            text = {
                                Text(
                                    "Something went wrong and the app restarted. " +
                                        "Your saved reading is intact. " +
                                        "Nothing was posted on your behalf."
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { showRecovery = false }) {
                                    Text("Continue")
                                }
                            }
                        )
                    }
                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") { ThreadListScreen(navController, vm) }
                        composable(
                            "thread/{threadId}",
                            arguments = listOf(navArgument("threadId") { type = NavType.IntType })
                        ) { entry ->
                            ThreadScreen(
                                navController, vm,
                                entry.arguments?.getInt("threadId") ?: 0
                            )
                        }
                        composable("signin") { SignInScreen(navController, vm, walletSender) }
                    }
                }
            }
        }
    }
}
