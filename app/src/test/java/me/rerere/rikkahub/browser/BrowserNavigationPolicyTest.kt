package me.rerere.rikkahub.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserNavigationPolicyTest {
    @Test
    fun `external app links ask the foreground user but are blocked while silent`() {
        val target = "intent://search/#Intent;scheme=example;end"

        assertEquals(
            BrowserNavigationDecision.AskForegroundUser(target, "intent"),
            BrowserNavigationPolicy.decide(
                currentUrl = "https://example.com/results",
                targetUrl = target,
                mode = BrowserInteractionMode.FOREGROUND_USER,
            ),
        )
        assertEquals(
            BrowserNavigationDecision.Block,
            BrowserNavigationPolicy.decide(
                currentUrl = "https://example.com/results",
                targetUrl = target,
                mode = BrowserInteractionMode.SILENT_AI,
            ),
        )
    }

    @Test
    fun `web links remain in WebView while private file hops are blocked`() {
        assertEquals(
            BrowserNavigationDecision.AllowInWebView,
            BrowserNavigationPolicy.decide(
                currentUrl = "https://example.com",
                targetUrl = "https://example.com/next",
                mode = BrowserInteractionMode.FOREGROUND_USER,
            ),
        )
        assertEquals(
            BrowserNavigationDecision.Block,
            BrowserNavigationPolicy.decide(
                currentUrl = "https://example.com",
                targetUrl = "file:///data/user/0/private.txt",
                mode = BrowserInteractionMode.FOREGROUND_USER,
            ),
        )
    }

    @Test
    fun `script and content schemes are never offered as external apps`() {
        listOf("javascript:alert(1)", "content://private/item", "data:text/html,test").forEach { url ->
            assertEquals(
                BrowserNavigationDecision.Block,
                BrowserNavigationPolicy.decide(
                    currentUrl = "https://example.com",
                    targetUrl = url,
                    mode = BrowserInteractionMode.FOREGROUND_USER,
                ),
            )
        }
    }

    @Test
    fun `address bar preserves external schemes for confirmation instead of loading them as web urls`() {
        val target = BrowserNavigationPolicy.resolveAddressInput(
            "intent://scan/#Intent;scheme=zxing;end",
        )

        assertEquals("intent://scan/#Intent;scheme=zxing;end", target)
        val compactIntent = BrowserNavigationPolicy.resolveAddressInput(
            "intent:#Intent;scheme=zxing;end",
        )
        assertEquals("intent:#Intent;scheme=zxing;end", compactIntent)
        assertEquals(
            BrowserNavigationDecision.AskForegroundUser(compactIntent, "intent"),
            BrowserNavigationPolicy.decide(
                currentUrl = "https://example.com",
                targetUrl = compactIntent,
                mode = BrowserInteractionMode.FOREGROUND_USER,
            ),
        )
        assertEquals(
            BrowserNavigationDecision.AskForegroundUser(target, "intent"),
            BrowserNavigationPolicy.decide(
                currentUrl = "https://example.com",
                targetUrl = target,
                mode = BrowserInteractionMode.FOREGROUND_USER,
            ),
        )
        assertEquals(
            "https://localhost:8080",
            BrowserNavigationPolicy.resolveAddressInput("localhost:8080"),
        )
    }

    @Test
    fun `only http and https are accepted as intent fallbacks`() {
        assertEquals(true, BrowserNavigationPolicy.isSafeWebFallback("https://example.com/fallback"))
        assertEquals(true, BrowserNavigationPolicy.isSafeWebFallback("http://example.com/fallback"))
        assertEquals(false, BrowserNavigationPolicy.isSafeWebFallback("about:blank"))
        assertEquals(false, BrowserNavigationPolicy.isSafeWebFallback("market://details?id=example"))
    }
}
