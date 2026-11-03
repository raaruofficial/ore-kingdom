package com.raaru.orekingdom

import android.app.Application
import android.content.Intent
import android.util.Log
import com.raaru.orekingdom.data.CacheStore
import com.raaru.orekingdom.data.HostApi
import com.raaru.orekingdom.data.SessionStore
import com.raaru.orekingdom.data.SolanaRpc
import com.raaru.orekingdom.wallet.WalletManager
import java.io.File

/**
 * Application object: owns the single wallet manager, the host client,
 * and the on-device stores. Also carries the native error boundary: an
 * uncaught exception writes a recovery marker and relaunches into a
 * dismissible recovery card (the read cache is files; it survives). The
 * marker is consumed on display, so a recovery can never loop — the
 * native form of the estate's error-boundary rule (KFC-002's lesson:
 * a takeover that cannot be left is the fault, not the error).
 * Adapted from the Seeker Citadel shell's CitadelApp.
 */
class OreKingdomApp : Application() {

    lateinit var walletManager: WalletManager
        private set
    lateinit var hostApi: HostApi
        private set
    lateinit var solanaRpc: SolanaRpc
        private set
    lateinit var sessionStore: SessionStore
        private set
    lateinit var cacheStore: CacheStore
        private set

    override fun onCreate() {
        super.onCreate()
        walletManager = WalletManager()
        hostApi = HostApi()
        solanaRpc = SolanaRpc()
        sessionStore = SessionStore(this)
        cacheStore = CacheStore(this)
        // Restore the MWA auth token for quiet reauthorization; the
        // wallet identity itself is re-proven by SIWS at sign-in (B2).
        walletManager.persistedAuthToken = sessionStore.authToken

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                Log.e("OreKingdomApp", "uncaught exception, entering recovery", error)
                File(filesDir, RECOVERY_MARKER).writeText(error.javaClass.simpleName ?: "error")
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(EXTRA_RECOVERED, true)
                }
                startActivity(intent)
                android.os.Process.killProcess(android.os.Process.myPid())
            } catch (e: Exception) {
                defaultHandler?.uncaughtException(thread, error)
            }
        }
    }

    /** True once, after a crash-recovery relaunch; consuming it disarms
     *  the recovery card for the rest of the session. */
    fun consumeRecoveryMarker(): Boolean {
        val f = File(filesDir, RECOVERY_MARKER)
        if (!f.exists()) return false
        f.delete()
        return true
    }

    companion object {
        const val EXTRA_RECOVERED = "recovered"
        private const val RECOVERY_MARKER = "recovery.marker"
    }
}
