package me.rerere.rikkahub.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryContentGuardTest {
    @Test
    fun `extraction input removes credentials verification codes and large base64 blobs`() {
        val base64 = "A".repeat(320)
        val input = """
            I prefer concise answers.
            api_key = sk-secret-value-123456
            verification code: 482913
            attachment: $base64
            <tool_result tool="shell">private command output</tool_result>
            <thinking>internal chain that must not become memory</thinking>
        """.trimIndent()

        val result = MemoryContentGuard().redact(input)

        assertTrue(result.text.contains("I prefer concise answers."))
        assertFalse(result.text.contains("sk-secret-value-123456"))
        assertFalse(result.text.contains("482913"))
        assertFalse(result.text.contains(base64))
        assertFalse(result.text.contains("private command output"))
        assertFalse(result.text.contains("internal chain that must not become memory"))
        assertTrue(result.text.contains("<redacted>"))
    }
}
