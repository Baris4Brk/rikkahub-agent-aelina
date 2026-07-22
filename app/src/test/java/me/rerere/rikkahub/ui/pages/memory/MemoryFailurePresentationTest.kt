package me.rerere.rikkahub.ui.pages.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFailurePresentationTest {
    @Test
    fun `latest capture failure is concise and does not expose a bearer credential`() {
        assertEquals(
            "memory_extraction_provider_error: HTTP 400: balance is required; Bearer [redacted]",
            formatMemoryFailureDetail(
                code = "memory_extraction_provider_error",
                message = "  HTTP 400: balance is required;\nBearer sk-real-token-123456  ",
            ),
        )
    }

    @Test
    fun `provider diagnostic redacts query headers jwt and credential fields`() {
        val googleKey = "AIza" + "a".repeat(35)
        val jwt = "eyJheader12345.payload12345.signature12345"
        val cookie = "session=opaque-cookie-value"
        val proxy = "Basic opaque-proxy-credential"
        val credential = "opaque-credential-value"
        val detail = listOf(
            "Retry https://example.test/?key=$googleKey",
            "Cookie: $cookie",
            "Set-Cookie: $cookie",
            "Proxy-Authorization: $proxy",
            "jwt=$jwt",
            "credential=$credential",
        ).joinToString("; ")

        val presentation = formatMemoryFailureDetail(
            code = "memory_extraction_provider_error",
            message = detail,
        ).orEmpty()

        listOf(googleKey, cookie, "opaque-proxy-credential", jwt, credential).forEach { secret ->
            assertFalse("Memory diagnostic leaked $secret", presentation.contains(secret))
        }
        assertTrue(presentation.contains("Retry https://example.test/"))
    }

    @Test
    fun `cookie diagnostics redact every value in a multi value header`() {
        val firstCookie = "first-cookie-secret"
        val secondCookie = "second-cookie-secret"

        val presentation = formatMemoryFailureDetail(
            code = "memory_extraction_provider_error",
            message = "Cookie: session=$firstCookie; preference=$secondCookie",
        ).orEmpty()

        assertFalse(presentation.contains(firstCookie))
        assertFalse(presentation.contains(secondCookie))
    }
}
