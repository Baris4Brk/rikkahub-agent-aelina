package me.rerere.rikkahub.ui.components.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompressContextInputPolicyTest {
    @Test
    fun `accepts custom token and retained message counts within bounds`() {
        assertEquals(
            CompressContextInput(targetTokens = 12_345, keepRecentMessages = 87),
            parseCompressContextInput(targetTokens = "12345", keepRecentMessages = "87"),
        )
    }

    @Test
    fun `rejects blank malformed and out of range values`() {
        assertNull(parseCompressContextInput(targetTokens = "", keepRecentMessages = "32"))
        assertNull(parseCompressContextInput(targetTokens = "abc", keepRecentMessages = "32"))
        assertNull(parseCompressContextInput(targetTokens = "99", keepRecentMessages = "32"))
        assertNull(parseCompressContextInput(targetTokens = "32001", keepRecentMessages = "32"))
        assertNull(parseCompressContextInput(targetTokens = "2000", keepRecentMessages = "-1"))
        assertNull(parseCompressContextInput(targetTokens = "2000", keepRecentMessages = "1.5"))
    }
}
