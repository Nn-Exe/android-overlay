package com.nnexe.overlaybrowser

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Lightweight Brave-style network ad & tracker blocker for WebView.
 * Runs on Chromium's background IO thread inside WebViewClient.shouldInterceptRequest.
 */
object AdBlocker {

    private val EMPTY_BYTES = ByteArray(0)

    private val BLOCKED_DOMAINS = hashSetOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.com",
        "2mdn.net",
        "imasdk.googleapis.com",
        "moatads.com",
        "adnxs.com",
        "adsrvr.org",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "casalemedia.com",
        "advertising.com",
        "amazon-adsystem.com",
        "scorecardresearch.com",
        "quantserve.com",
        "zedo.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "exoclick.com",
        "juicyads.com",
        "trafficjunky.net",
        "adsterra.com",
        "revcontent.com",
        "mgid.com",
        "teads.tv",
        "smartadserver.com",
        "bidswitch.net",
        "sharethrough.com",
        "indexexchange.com",
        "3lift.com",
        "sovrn.com",
        "lijit.com",
        "onetag-sys.com",
        "media.net",
        "adform.net",
        "serving-sys.com",
        "flashtalking.com",
        "innovid.com",
        "spotxchange.com",
        "springserve.com",
        "connatix.com",
        "vungle.com",
        "applovin.com",
        "chartboost.com",
        "unityads.unity3d.com",
        "inmobi.com",
    )

    private val YOUTUBE_AD_PATHS = arrayOf(
        "/api/stats/ads",
        "/pagead/",
        "/ptracking",
        "/get_midroll_",
        "/youtubei/v1/player/ad_break",
    )

    fun shouldBlock(request: WebResourceRequest): Boolean {
        val uri = request.url ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (isBlockedHost(host)) return true

        val fullUrl = uri.toString()
        val path = uri.path.orEmpty()

        // YouTube-specific ad endpoints
        if (host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") || host == "youtu.be") {
            for (adPath in YOUTUBE_AD_PATHS) {
                if (path.startsWith(adPath) || fullUrl.contains(adPath)) return true
            }
            if (path.startsWith("/api/stats/qoe") && fullUrl.contains("adformat=")) return true
            if (fullUrl.contains("&ad_type=") || fullUrl.contains("?ad_type=")) return true
        }

        // Common ad script / banner path patterns on general sites
        if (path.contains("/ads/") || path.contains("/adserver") || path.contains("/pagead/")) {
            if (!request.isForMainFrame) return true
        }

        return false
    }

    private fun isBlockedHost(host: String): Boolean {
        if (host.startsWith("adservice.google.")) return true
        var current = host
        while (true) {
            if (BLOCKED_DOMAINS.contains(current)) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
        }
    }

    fun emptyResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        204,
        "No Content",
        mapOf("Access-Control-Allow-Origin" to "*"),
        ByteArrayInputStream(EMPTY_BYTES),
    )
}

