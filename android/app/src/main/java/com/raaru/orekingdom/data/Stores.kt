package com.raaru.orekingdom.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.File

/**
 * On-device session storage. This host issues no session token — identity
 * is the SIWS proof, re-made per sensitive action — so what persists is
 * the member's wallet + .skr name (for read calls and display) in
 * Keystore-backed EncryptedSharedPreferences. If the encrypted store
 * cannot be opened (corrupt keystore entry), we fall back to plain
 * app-private prefs and log it, as the shell of record does: a hard
 * failure here would lock the member out of their own forum for a
 * storage fault, which is the worse outcome. Adapted from Citadel's
 * SessionStore; the parked-signature discipline carries over keyed by
 * wrapper session id for the M3 payment flows.
 */
class SessionStore(context: Context) {
    private val prefs = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "orekingdom_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w("OreKingdomStores", "encrypted prefs unavailable, falling back", e)
        context.getSharedPreferences("orekingdom_session_plain", Context.MODE_PRIVATE)
    }

    var wallet: String?
        get() = prefs.getString("wallet", null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("wallet") else putString("wallet", value)
            }.apply()
        }

    var skrName: String?
        get() = prefs.getString("skrName", null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("skrName") else putString("skrName", value)
            }.apply()
        }

    var memberJson: String?
        get() = prefs.getString("member", null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("member") else putString("member", value)
            }.apply()
        }

    /** The MWA auth token, so a reconnect can reauthorize quietly. */
    var authToken: String?
        get() = prefs.getString("authToken", null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove("authToken") else putString("authToken", value)
            }.apply()
        }

    // ---------- parked payment signatures (R3/R4 discipline) ----------
    // After signAndSend returns a signature, the send is ambiguous by
    // nature: the payment may land even if every later check fails.
    // The (sessionId -> signature) pair is persisted so a retry, a
    // relaunch, or a resume RE-READS that exact session instead of ever
    // building a second payment for the same session. (Shell-of-record
    // pattern, Citadel SessionStore; consumed by the M3 flows.)

    private fun parkedPayments(): JSONObject = try {
        JSONObject(prefs.getString("parkedPayments", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    fun parkedSignature(sessionId: String): String? =
        parkedPayments().optString(sessionId).takeIf { it.isNotBlank() }

    fun parkSignature(sessionId: String, signature: String) {
        val all = parkedPayments().put(sessionId, signature)
        prefs.edit().putString("parkedPayments", all.toString()).apply()
    }

    fun clearParked(sessionId: String) {
        val all = parkedPayments().apply { remove(sessionId) }
        prefs.edit().putString("parkedPayments", all.toString()).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

/**
 * Read cache for offline reading (shell of record, Brief §3): the last
 * good host response per key, as raw JSON in app-private files. Reads
 * are corruption-tolerant — a torn or invalid file is a cache MISS,
 * never a crash. Writes are whole-file replaces. This is what lets a
 * cold open on a dead connection show saved reading instead of a blank
 * screen, with the stale marker the screens render honestly.
 */
class CacheStore(context: Context) {
    private val dir = File(context.filesDir, "readcache").apply { mkdirs() }

    fun put(key: String, json: JSONObject) {
        try {
            File(dir, key).writeText(json.toString())
        } catch (e: Exception) {
            Log.w("OreKingdomStores", "cache write failed for $key", e)
        }
    }

    fun get(key: String): JSONObject? {
        val f = File(dir, key)
        if (!f.exists()) return null
        return try {
            JSONObject(f.readText())
        } catch (e: Exception) {
            // Corrupt-cache recovery: drop the entry, report a miss.
            Log.w("OreKingdomStores", "corrupt cache entry dropped: $key", e)
            f.delete()
            null
        }
    }
}
