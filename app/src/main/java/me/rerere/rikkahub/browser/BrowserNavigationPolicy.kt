package me.rerere.rikkahub.browser

enum class BrowserInteractionMode {
    SILENT_AI,
    FOREGROUND_USER,
}

sealed interface BrowserNavigationDecision {
    data object AllowInWebView : BrowserNavigationDecision
    data object Block : BrowserNavigationDecision
    data class AskForegroundUser(
        val url: String,
        val scheme: String,
    ) : BrowserNavigationDecision
}

/** Decides page-initiated main-frame navigation without exposing Android View types. */
object BrowserNavigationPolicy {
    /**
     * Keep explicit browser/app schemes intact so [decide] can block or confirm them before
     * BrowserTabManager calls WebView.loadUrl(). Host-and-port input such as localhost:8080 is
     * still treated as a web address by the existing address normalizer.
     */
    fun resolveAddressInput(raw: String): String {
        val input = raw.trim()
        val scheme = schemeOf(input)
        val hasSchemeSeparator = input.substringAfter(':', missingDelimiterValue = "")
            .startsWith("//")
        return if (hasSchemeSeparator || scheme in SCHEMES_WITHOUT_SLASHES) {
            input
        } else {
            normalizeBrowserQuery(input)
        }
    }

    fun isSafeWebFallback(url: String): Boolean {
        return schemeOf(url) in SAFE_WEB_SCHEMES
    }

    fun decide(
        currentUrl: String?,
        targetUrl: String,
        mode: BrowserInteractionMode,
    ): BrowserNavigationDecision {
        val scheme = schemeOf(targetUrl)
            ?: return BrowserNavigationDecision.Block
        return when (scheme) {
            "http", "https", "about" -> BrowserNavigationDecision.AllowInWebView
            "file" -> if (currentUrl?.startsWith("file:", ignoreCase = true) == true) {
                BrowserNavigationDecision.AllowInWebView
            } else {
                BrowserNavigationDecision.Block
            }
            "javascript", "data", "blob", "content" -> BrowserNavigationDecision.Block
            else -> if (mode == BrowserInteractionMode.FOREGROUND_USER) {
                BrowserNavigationDecision.AskForegroundUser(targetUrl, scheme)
            } else {
                BrowserNavigationDecision.Block
            }
        }
    }

    private val SCHEMES_WITHOUT_SLASHES = setOf(
        "about",
        "blob",
        "content",
        "data",
        "geo",
        "intent",
        "javascript",
        "mailto",
        "market",
        "sms",
        "tel",
    )

    private val SAFE_WEB_SCHEMES = setOf("http", "https")
    private val SCHEME_PREFIX = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    private fun schemeOf(url: String): String? =
        SCHEME_PREFIX.find(url.trim())?.groupValues?.get(1)?.lowercase()
}
