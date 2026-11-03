package com.raaru.orekingdom.data

import com.raaru.orekingdom.util.Base58
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * The one Solana RPC read the client ever makes: a recent blockhash to
 * build a payment transaction. The public mainnet endpoint is the
 * estate's named failover leg (packet §12 posture); all verification —
 * including whether a payment actually landed — is the host's job, so
 * this client deliberately exposes nothing else.
 */
class SolanaRpc(private val endpoint: String = "https://api.mainnet-beta.solana.com") {

    suspend fun getLatestBlockhash(): ApiResult<ByteArray> = withContext(Dispatchers.IO) {
        val conn = try {
            (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        } catch (e: Exception) {
            return@withContext ApiResult.Unreachable
        }
        try {
            val body = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 1)
                .put("method", "getLatestBlockhash")
                .put(
                    "params",
                    org.json.JSONArray().put(JSONObject().put("commitment", "finalized"))
                )
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return@withContext ApiResult.Unreachable
            val text = BufferedReader(
                InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)
            ).readText()
            val hashB58 = JSONObject(text)
                .optJSONObject("result")
                ?.optJSONObject("value")
                ?.optString("blockhash")
                .orEmpty()
            if (hashB58.isBlank()) return@withContext ApiResult.Unreachable
            val bytes = try {
                Base58.decode(hashB58)
            } catch (e: Exception) {
                return@withContext ApiResult.Unreachable
            }
            if (bytes.size != 32) ApiResult.Unreachable else ApiResult.Ok(bytes)
        } catch (e: Exception) {
            ApiResult.Unreachable
        } finally {
            conn.disconnect()
        }
    }
}
