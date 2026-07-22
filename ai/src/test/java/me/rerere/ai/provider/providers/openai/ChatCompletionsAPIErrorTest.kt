package me.rerere.ai.provider.providers.openai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionsAPIErrorTest {
    @Test
    fun `errors array exposes an actionable provider detail`() {
        val message = providerHttpErrorMessage(
            responseCode = 429,
            rawBody = """{
                "errors": [
                    { "message": "Choose a supported model before retrying." }
                ]
            }""".trimIndent(),
        )

        assertTrue(message.contains("Choose a supported model before retrying."))
    }

    @Test
    fun `non-success response exposes a short actionable error without credentials`() {
        val detail = "You need positive balance to do inference. " +
            "Bearer sk-live-very-secret api_key=api-secret-value " +
            "token: jwt-secret-value ${"x".repeat(900)}"
        val message = providerHttpErrorMessage(
            responseCode = 400,
            rawBody = """{"error":{"message":"$detail"}}""",
        )

        assertTrue(message.contains("Failed to get response: 400"))
        assertTrue(message.contains("You need positive balance to do inference."))
        assertFalse(message.contains("sk-live-very-secret"))
        assertFalse(message.contains("api-secret-value"))
        assertFalse(message.contains("jwt-secret-value"))
        assertTrue("HTTP error detail must stay bounded", message.length <= 512)
    }

    @Test
    fun `provider error redacts query headers and opaque credential fields`() {
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
        val message = providerHttpErrorMessage(
            responseCode = 401,
            rawBody = """{"error":{"message":"$detail"}}""",
        )

        listOf(googleKey, cookie, "opaque-proxy-credential", jwt, credential).forEach { secret ->
            assertFalse("Provider error leaked $secret", message.contains(secret))
        }
        assertTrue(message.contains("Retry https://example.test/"))
    }

    @Test
    fun `provider error redacts every value in a multi value cookie header`() {
        val firstCookie = "first-cookie-secret"
        val secondCookie = "second-cookie-secret"
        val message = providerHttpErrorMessage(
            responseCode = 400,
            rawBody = """{
                "error": {
                    "message": "Cookie: session=$firstCookie; preference=$secondCookie"
                }
            }""".trimIndent(),
        )

        assertFalse(message.contains(firstCookie))
        assertFalse(message.contains(secondCookie))
    }
}
