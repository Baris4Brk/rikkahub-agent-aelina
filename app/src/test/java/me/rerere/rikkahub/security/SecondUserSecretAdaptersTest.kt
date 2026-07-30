package me.rerere.rikkahub.security

import me.rerere.ai.provider.ProviderSetting
import me.rerere.asr.ASRProviderSetting
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecondUserSecretAdaptersTest {
    @Test
    fun `provider migration preserves configuration but clears only legacy key`() {
        val provider = ProviderSetting.OpenAI(
            name = "Private provider",
            apiKey = "secret",
            baseUrl = "https://example.invalid/v1",
        )

        assertEquals("secret", provider.legacyApiKeyOrNull())
        val migrated = provider.clearLegacyApiKey() as ProviderSetting.OpenAI
        assertEquals("", migrated.apiKey)
        assertEquals(provider.id, migrated.id)
        assertEquals(provider.baseUrl, migrated.baseUrl)
    }

    @Test
    fun `tts and asr migration adapters have no system tts secret path`() {
        val tts = TTSProviderSetting.Gemini(apiKey = "tts-secret")
        assertEquals("tts-secret", tts.legacyApiKeyOrNull())
        assertEquals("", (tts.clearLegacyApiKey() as TTSProviderSetting.Gemini).apiKey)
        assertNull(TTSProviderSetting.SystemTTS().legacyApiKeyOrNull())

        val asr = ASRProviderSetting.OpenAIRealtime(apiKey = "asr-secret")
        assertEquals("asr-secret", asr.legacyApiKeyOrNull())
        assertEquals("", (asr.clearLegacyApiKey() as ASRProviderSetting.OpenAIRealtime).apiKey)
    }
}
