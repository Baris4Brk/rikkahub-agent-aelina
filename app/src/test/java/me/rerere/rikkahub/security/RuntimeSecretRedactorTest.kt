package me.rerere.rikkahub.security

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSecretRedactorTest {
    @Test
    fun `redacts raw base64 url and hex forms then forgets on close`() {
        val redactor = RuntimeSecretRedactor()
        val secret = "synthetic-owner-secret-123".toCharArray()
        redactor.remember(secret)
        val raw = secret.concatToString()
        val base64 = Base64.getEncoder().encodeToString(raw.encodeToByteArray())
        val hex = raw.encodeToByteArray().joinToString("") { "%02x".format(it) }

        val redacted = redactor.redact("raw=$raw b64=$base64 hex=$hex")
        assertFalse(redacted.contains(raw))
        assertFalse(redacted.contains(base64))
        assertFalse(redacted.contains(hex))
        assertTrue(redacted.contains("[SECRET_REDACTED]"))

        redactor.clear()
        assertEquals(raw, redactor.redact(raw))
        secret.fill('\u0000')
    }
}
