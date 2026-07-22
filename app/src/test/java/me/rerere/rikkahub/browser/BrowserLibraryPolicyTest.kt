package me.rerere.rikkahub.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLibraryPolicyTest {
    @Test fun `normalization removes fragments and default ports`() {
        assertEquals(
            "https://example.com/path?a=1",
            BrowserLibraryPolicy.normalize("HTTPS://Example.COM:443/path?a=1#section"),
        )
    }

    @Test fun `stored URL removes oauth codes tokens and signatures`() {
        val stored = BrowserLibraryPolicy.sanitizeForStorage(
            "https://example.com/callback?code=secret&safe=yes&access_token=abc&X-Amz-Signature=xyz",
        )
        assertEquals("https://example.com/callback?safe=yes", stored)
    }

    @Test fun `only successful public web navigations enter user history`() {
        assertTrue(BrowserLibraryPolicy.shouldRecordHistory("https://example.com", mainFrameSuccess = true))
        assertFalse(BrowserLibraryPolicy.shouldRecordHistory("about:blank", mainFrameSuccess = true))
        assertFalse(BrowserLibraryPolicy.shouldRecordHistory("file:///private/page", mainFrameSuccess = true))
        assertFalse(BrowserLibraryPolicy.shouldRecordHistory("https://example.com", mainFrameSuccess = false))
    }
}
