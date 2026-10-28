package com.raaru.orekingdom.forum

import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Native half of the actions bridge.
 *
 * The bundled forum client talks to its backend by POSTing {action, args} to
 * the relative path ./actions. Because the client is served from the
 * app-assets origin (not from the backend), the shell injects a small fetch
 * shim (see ForumScreen) that routes those calls here; this bridge performs
 * the real HTTP request against [ForumConfig.API_BASE_URL] and returns the
 * backend's status + body synchronously as a JSON string:
 * {"status": <int>, "body": "<raw response body>"}.
 *
 * JavascriptInterface methods run on a background thread, so the blocking
 * call is safe. Any transport failure is returned as status 0 with an
 * {error} body, which the client's own error handling surfaces — the bridge
 * never fabricates a success.
 */
class ActionsBridge {

    @JavascriptInterface
    fun request(method: String, url: String, body: String): String {
        val path = extractPath(url)
        val target = ForumConfig.API_BASE_URL.trimEnd('/') + path
        return try {
            val conn = URL(target).openConnection() as HttpURLConnection
            conn.requestMethod = method.uppercase()
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("content-type", "application/json")
            if (method.uppercase() != "GET" && body.isNotEmpty()) {
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            JSONObject().put("status", status).put("body", text).toString()
        } catch (e: Exception) {
            JSONObject()
                .put("status", 0)
                .put("body", JSONObject().put("error", "backend unreachable: ${e.message}").toString())
                .toString()
        }
    }

    /** Reduce an absolute or relative actions URL to its path ("/actions"). */
    private fun extractPath(url: String): String {
        val noQuery = url.substringBefore('?')
        val idx = noQuery.indexOf("/actions")
        return if (idx >= 0) noQuery.substring(idx) else "/actions"
    }
}
