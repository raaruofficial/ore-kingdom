package com.raaru.orekingdom.forum

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayInputStream

/**
 * The forum screen: the whole app. The bundled Ore Kingdom client renders in
 * a WebView; everything around it (asset serving, the actions bridge to the
 * backend, wallet intents, error surface, lifecycle) is native.
 *
 * Cold-start behaviour closed by construction at this layer:
 *  - the client is bundled, so first paint never waits on a network fetch of
 *    the app itself;
 *  - a main-frame load failure shows a native error state with a retry
 *    action — never a blank or endlessly-loading WebView;
 *  - a renderer crash reloads the entry document instead of leaving a
 *    dead view.
 * The client's own in-page error handling is the client rebuild's layer and
 * is tracked separately in the build record.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ForumScreen() {
    val context = LocalContext.current
    val webView = remember { mutableStateOf<WebView?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    BackHandler {
        val wv = webView.value
        if (wv != null && wv.canGoBack()) wv.goBack()
        else (context as? android.app.Activity)?.finish()
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webView.value?.onPause()
                Lifecycle.Event.ON_RESUME -> webView.value?.onResume()
                Lifecycle.Event.ON_DESTROY -> webView.value?.destroy()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    if (loadError != null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Ore Kingdom couldn't load.")
            Text(loadError ?: "")
            Button(onClick = {
                loadError = null
                webView.value?.loadUrl(ForumConfig.ENTRY_URL)
            }) { Text("Retry") }
        }
        return
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                addJavascriptInterface(ActionsBridge(), ForumConfig.ACTIONS_BRIDGE_NAME)
                webViewClient = object : WebViewClient() {

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        // The client bundle loads as a deferred module script,
                        // so this shim lands before any app code runs.
                        view?.evaluateJavascript(FETCH_SHIM, null)
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val uri = request?.url ?: return null
                        if (request.method == "GET" && isAssetOrigin(uri)) {
                            return serveAsset(uri.path ?: "/")
                        }
                        return null
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return false
                        val scheme = uri.scheme ?: return false
                        return when {
                            // Wallet handoff (Mobile Wallet Adapter association).
                            scheme == "solana" || scheme == "intent" -> {
                                fireExternal(Intent(Intent.ACTION_VIEW, uri))
                                true
                            }
                            isAssetOrigin(uri) -> false // bundled client stays in the WebView
                            scheme == "http" || scheme == "https" -> {
                                // External links (explorers, trusted domains) open
                                // in the browser, never inside the forum view.
                                fireExternal(Intent(Intent.ACTION_VIEW, uri))
                                true
                            }
                            else -> true
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            loadError = error?.description?.toString() ?: "Load failed."
                        }
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?
                    ): Boolean {
                        webView.value?.loadUrl(ForumConfig.ENTRY_URL)
                        return true
                    }

                    private fun isAssetOrigin(uri: Uri): Boolean =
                        uri.scheme == "https" && uri.host == "appassets.androidplatform.net"

                    private fun fireExternal(intent: Intent) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // No handler (e.g. no wallet installed): stay in the
                            // forum instead of crashing the activity.
                        }
                    }

                    private fun serveAsset(path: String): WebResourceResponse {
                        val rel = path.trimStart('/').ifEmpty { "index.html" }
                        val candidates = listOf(rel, "index.html")
                        for (name in candidates) {
                            try {
                                val data = context.assets.open(name).use { it.readBytes() }
                                return WebResourceResponse(mimeFor(name), "utf-8", ByteArrayInputStream(data))
                            } catch (e: Exception) {
                                // try the SPA fallback next
                            }
                        }
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    private fun mimeFor(name: String): String = when {
                        name.endsWith(".html") -> "text/html"
                        name.endsWith(".js") -> "text/javascript"
                        name.endsWith(".css") -> "text/css"
                        name.endsWith(".png") -> "image/png"
                        name.endsWith(".webp") -> "image/webp"
                        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
                        name.endsWith(".svg") -> "image/svg+xml"
                        name.endsWith(".json") -> "application/json"
                        name.endsWith(".woff2") -> "font/woff2"
                        name.endsWith(".ico") -> "image/x-icon"
                        else -> "application/octet-stream"
                    }
                }
                loadUrl(ForumConfig.ENTRY_URL)
                webView.value = this
            }
        }
    )
}

/**
 * Fetch shim injected at page start. The client's backend calls are POSTs of
 * {action, args} to ./actions; those are routed through the native
 * [ActionsBridge] to [ForumConfig.API_BASE_URL]. Every other fetch passes
 * through untouched (Solana RPC, fonts).
 */
private const val FETCH_SHIM = """
(() => {
  if (window.__oreKingdomShim) return;
  window.__oreKingdomShim = true;
  const origFetch = window.fetch.bind(window);
  window.fetch = function(input, init) {
    const url = (typeof input === 'string') ? input : ((input && input.url) || '');
    if (/\/actions(\?|$)/.test(url) && window.OreKingdomActions) {
      const method = (init && init.method) || ((typeof input !== 'string' && input && input.method) || 'POST');
      const body = (init && init.body) || '';
      try {
        const res = JSON.parse(window.OreKingdomActions.request(String(method), String(url), String(body)));
        return Promise.resolve(new Response(res.body, {
          status: res.status || 200,
          headers: { 'Content-Type': 'application/json' }
        }));
      } catch (e) {
        return Promise.reject(new TypeError('actions bridge unavailable'));
      }
    }
    return origFetch(input, init);
  };
})();
"""
