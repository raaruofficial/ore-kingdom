package com.raaru.orekingdom.forum

import com.raaru.orekingdom.BuildConfig

/**
 * Ore Kingdom shell configuration — the single named config point.
 *
 * The forum client is bundled in the APK and served to the WebView from the
 * app-assets origin below, so no hosted URL is baked into the binary for the
 * client itself. The client's only network dependency is its actions API:
 *
 *   [API_BASE_URL] — base URL of the Ore Kingdom backend service (the Bun
 *   actions host). It reaches the build as the Gradle property / environment
 *   value ORE_KINGDOM_API_BASE and lands here as a BuildConfig field. This is
 *   THE config point: re-pointing the app at another host is a one-value
 *   change at build time, and nothing else in the binary names a host.
 *
 *   Staging default: http://10.0.2.2:8377 — the Android emulator's route to
 *   this workstation's locally staged backend. It is a staging value, not a
 *   published endpoint; the production host value is set when the host seat
 *   is final (owner ruling D1: the estate's DigitalOcean spare droplet).
 */
object ForumConfig {
    /** Origin the bundled client is served from inside the WebView. */
    const val ASSET_ORIGIN: String = "https://appassets.androidplatform.net"

    /** Bundled entry document, as an asset-origin URL. */
    const val ENTRY_URL: String = "$ASSET_ORIGIN/index.html"

    /** Base URL of the backend actions service — the single named config point. */
    val API_BASE_URL: String = BuildConfig.API_BASE_URL

    /** JS bridge name the injected fetch shim calls: `window.OreKingdomActions`. */
    const val ACTIONS_BRIDGE_NAME: String = "OreKingdomActions"
}
