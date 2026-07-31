package me.rerere.rikkahub.ui.components.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAssetRequestPolicyTest {
    private val policy = LocalAssetRequestPolicy(setOf(MERMAID_ASSET_PATH))

    @Test
    fun `allows only pinned local mermaid asset`() {
        assertTrue(policy.allows(request(path = MERMAID_ASSET_PATH)))
        assertFalse(policy.allows(request(path = "/assets/mermaid/other.js")))
        assertFalse(policy.allows(request(path = "/assets/mermaid/LICENSE")))
    }

    @Test
    fun `rejects external network and insecure schemes`() {
        assertFalse(policy.allows(request(host = "cdn.jsdelivr.net", path = MERMAID_ASSET_PATH)))
        assertFalse(policy.allows(request(scheme = "http", path = MERMAID_ASSET_PATH)))
        assertFalse(policy.allows(request(scheme = "file", host = null, path = MERMAID_ASSET_PATH)))
    }

    @Test
    fun `rejects path traversal queries and non get methods`() {
        assertFalse(policy.allows(request(path = "/assets/mermaid/%2e%2e/browser/readability.js")))
        assertFalse(policy.allows(request(path = MERMAID_ASSET_PATH, query = "version=other")))
        assertFalse(policy.allows(request(method = "POST", path = MERMAID_ASSET_PATH)))
    }

    private fun request(
        method: String = "GET",
        scheme: String = "https",
        host: String? = "rikkahub.local",
        path: String,
        query: String? = null,
    ) = LocalAssetRequest(
        method = method,
        scheme = scheme,
        host = host,
        encodedPath = path,
        encodedQuery = query,
    )
}
