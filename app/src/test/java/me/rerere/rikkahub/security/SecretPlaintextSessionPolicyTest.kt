package me.rerere.rikkahub.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretPlaintextSessionPolicyTest {
    @Test
    fun `strong biometric proof has a short non future authorization window`() {
        assertTrue(secretPlaintextAuthorizationIsFresh(nowMs = 120_000L, issuedAtMs = 0L))
        assertFalse(secretPlaintextAuthorizationIsFresh(nowMs = 120_001L, issuedAtMs = 0L))
        assertFalse(secretPlaintextAuthorizationIsFresh(nowMs = 99L, issuedAtMs = 100L))
    }

    @Test
    fun `plaintext session expires exactly at its deadline`() {
        assertFalse(secretPlaintextSessionIsExpired(nowMs = 1_999L, expiresAtMs = 2_000L))
        assertTrue(secretPlaintextSessionIsExpired(nowMs = 2_000L, expiresAtMs = 2_000L))
        assertTrue(secretPlaintextSessionIsExpired(nowMs = 2_001L, expiresAtMs = 2_000L))
    }
}
