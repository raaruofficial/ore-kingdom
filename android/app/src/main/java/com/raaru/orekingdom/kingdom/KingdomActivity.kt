package com.raaru.orekingdom.kingdom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.raaru.orekingdom.BuildConfig
import com.raaru.orekingdom.OreKingdomApp

/**
 * Host activity for the Discord-framework client (:fable-client),
 * wired per INTEGRATION_MAP §2.2/§5. MainActivity (the forum client)
 * stays the launcher and is untouched; this activity is the kingdom
 * shell's entry point. The session credential is the app's existing
 * session token; the wallet header carries the signed-in wallet.
 */
class KingdomActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as OreKingdomApp
        val transport = KingdomTransport(
            baseUrl = BuildConfig.KINGDOM_API_URL,
            sessionToken = { app.sessionStore.authToken },
            walletAddress = { app.sessionStore.wallet },
        )
        // lifecycleScope is main-confined, as EntitlementStore requires.
        val session = KingdomSession(transport, lifecycleScope, applicationContext)
        session.start()
        setContent { KingdomRoot(session) }
    }
}
