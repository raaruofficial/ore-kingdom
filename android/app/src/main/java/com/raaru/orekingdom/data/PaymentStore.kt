package com.raaru.orekingdom.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

/**
 * Parked payment flows (R4 resume-after-abandon): the durable record
 * of every payment the member started — session/checkout id, the
 * draft it pays for, and the signature once one exists (mirrored in
 * SessionStore's parked signatures). Stored as one JSON array in the
 * same Keystore-backed encrypted-prefs discipline as SessionStore,
 * with the same plain-prefs fallback: a storage fault must never
 * strand a payment record the member already paid for.
 *
 * A flow is parked BEFORE its completion call is attempted and
 * unparked only when the host's answer is terminal (consumed,
 * completed, expired, forward-applied, or refused). Anything still
 * parked on relaunch is re-read against the host and offered for
 * completion — the member never re-pays for the same session.
 */
class PaymentStore(context: Context) {
    private val prefs = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "orekingdom_payments",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w("OreKingdomStores", "encrypted payment prefs unavailable, falling back", e)
        context.getSharedPreferences("orekingdom_payments_plain", Context.MODE_PRIVATE)
    }

    fun parkedFlows(): List<ParkedFlow> {
        val raw = prefs.getString("parkedFlows", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> ParkedFlow.fromJson(o) } }
        } catch (e: Exception) {
            Log.w("OreKingdomStores", "parked flows unreadable, treated as none", e)
            emptyList()
        }
    }

    fun park(flow: ParkedFlow) {
        val all = parkedFlows().filterNot { it.kind == flow.kind && it.id == flow.id } + flow
        save(all)
    }

    fun unpark(kind: String, id: String) {
        save(parkedFlows().filterNot { it.kind == kind && it.id == id })
    }

    fun flow(kind: String, id: String): ParkedFlow? =
        parkedFlows().firstOrNull { it.kind == kind && it.id == id }

    private fun save(all: List<ParkedFlow>) {
        val arr = JSONArray()
        all.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("parkedFlows", arr.toString()).apply()
    }
}
