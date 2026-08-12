package me.rerere.rikkahub.memory.dreaming.synthesis

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDreamSynthesizerAdmissionTest {
    @Test
    fun `utf8 overflow rejects without invoking provider`() = runBlocking {
        var providerCalls = 0

        val result = withDreamProviderAdmission(
            inputUtf8Bytes = 128_001L,
            estimatedInputTokens = 1,
            requestedOutputTokens = 1,
            enforcedWindowTokens = 8_192,
        ) {
            providerCalls++
            "must-not-run"
        }

        assertTrue(result is DreamProviderAdmissionResult.Rejected)
        assertEquals(0, providerCalls)
    }

    @Test
    fun `context overflow rejects without invoking provider`() = runBlocking {
        var providerCalls = 0

        val result = withDreamProviderAdmission(
            inputUtf8Bytes = 4_096L,
            estimatedInputTokens = 3_900,
            requestedOutputTokens = 256,
            enforcedWindowTokens = 4_096,
        ) {
            providerCalls++
            "must-not-run"
        }

        assertTrue(result is DreamProviderAdmissionResult.Rejected)
        assertEquals(0, providerCalls)
    }

    @Test
    fun `admitted request invokes provider exactly once`() = runBlocking {
        var providerCalls = 0

        val result = withDreamProviderAdmission(
            inputUtf8Bytes = 4_096L,
            estimatedInputTokens = 1_000,
            requestedOutputTokens = 256,
            enforcedWindowTokens = 4_096,
        ) {
            providerCalls++
            "ok"
        }

        assertTrue(result is DreamProviderAdmissionResult.Admitted<*>)
        assertEquals("ok", (result as DreamProviderAdmissionResult.Admitted<*>).value)
        assertEquals(1, providerCalls)
    }
}
