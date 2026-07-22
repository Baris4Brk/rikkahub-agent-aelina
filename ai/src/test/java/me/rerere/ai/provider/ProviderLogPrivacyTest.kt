package me.rerere.ai.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLogPrivacyTest {
    @Test
    fun `parse failure diagnostics never include exception message or event content`() {
        val secret = "private reasoning and token sk-secret"
        val diagnostic = ProviderLogPrivacy.parseFailure(
            eventChars = 512,
            eventType = "message.delta",
            error = IllegalArgumentException("JSON input: $secret"),
        )

        assertTrue(diagnostic.contains("chars=512"))
        assertTrue(diagnostic.contains("type=message.delta"))
        assertTrue(diagnostic.contains("error=IllegalArgumentException"))
        assertFalse(diagnostic.contains(secret))
        assertFalse(diagnostic.contains("JSON input"))
    }

    @Test
    fun `untrusted event type is reduced to a bounded safe token`() {
        val diagnostic = ProviderLogPrivacy.parseFailure(
            eventChars = 1,
            eventType = "message\nprivate reasoning",
            error = RuntimeException("private response"),
        )

        assertFalse(diagnostic.contains("private reasoning"))
        assertFalse(diagnostic.contains('\n'))
        assertTrue(diagnostic.length < 160)
    }

    @Test
    fun `error body parse diagnostics reveal only size and exception class`() {
        val diagnostic = ProviderLogPrivacy.errorBodyParseFailure(
            bodyChars = 2048,
            error = IllegalStateException("response echoed private prompt"),
        )

        assertTrue(diagnostic.contains("chars=2048"))
        assertTrue(diagnostic.contains("error=IllegalStateException"))
        assertFalse(diagnostic.contains("private prompt"))
    }

    @Test
    fun `sanitized parse exception cannot leak content through an upstream logger`() {
        val sanitized = ProviderLogPrivacy.parseException(
            eventChars = 99,
            eventType = "response.delta",
            error = IllegalArgumentException("JSON input: private reasoning"),
        )

        assertFalse(sanitized.message.orEmpty().contains("private reasoning"))
        assertTrue(sanitized.message.orEmpty().contains("error=IllegalArgumentException"))
        assertTrue(sanitized.cause == null)
    }

    @Test
    fun `encoding failure omits source and exception message`() {
        val secret = "file:///private/photo.jpg?token=secret"
        val diagnostic = ProviderLogPrivacy.encodingFailure(
            contentKind = "image",
            error = IllegalArgumentException(secret),
        )

        assertTrue(diagnostic.contains("kind=image"))
        assertTrue(diagnostic.contains("error=IllegalArgumentException"))
        assertFalse(diagnostic.contains(secret))
        assertFalse(diagnostic.contains("token="))
    }
}
