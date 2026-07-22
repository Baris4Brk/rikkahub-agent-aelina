package me.rerere.rikkahub.browser

import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.security.MessageDigest

enum class BrowserProfileResult { APPLIED, DEFAULT_PROFILE, ISOLATION_UNAVAILABLE }

/** Keeps local shared cookies separate from remote/ephemeral browser work when WebView supports it. */
object BrowserProfileManager {
    fun supportsMultipleProfiles(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    fun apply(webView: WebView, profileClass: BrowserProfileClass, key: String): BrowserProfileResult {
        if (profileClass == BrowserProfileClass.LOCAL_SHARED) {
            if (!supportsMultipleProfiles()) return BrowserProfileResult.DEFAULT_PROFILE
            return runCatching {
                WebViewCompat.setProfile(webView, "rikka-local-shared")
                BrowserProfileResult.APPLIED
            }.getOrElse { BrowserProfileResult.DEFAULT_PROFILE }
        }
        if (!supportsMultipleProfiles()) return BrowserProfileResult.ISOLATION_UNAVAILABLE
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(key.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(32)
            WebViewCompat.setProfile(webView, "rikka-${profileClass.name.lowercase()}-$digest")
            BrowserProfileResult.APPLIED
        }.getOrElse { BrowserProfileResult.ISOLATION_UNAVAILABLE }
    }
}
