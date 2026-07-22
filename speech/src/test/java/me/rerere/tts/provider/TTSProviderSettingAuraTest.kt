package me.rerere.tts.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSProviderSettingAuraTest {
    @Test
    fun aura_defaults_are_expected() {
        val setting = TTSProviderSetting.Aura()

        assertEquals("Aura TTS", setting.name)
        assertEquals("https://tts.aurastd.com/api/v1", setting.baseUrl)
        assertEquals("speech-2.8-turbo", setting.model)
        assertEquals("female-shaonv", setting.voiceId)
        assertEquals("neutral", setting.emotion)
        assertEquals(1.0f, setting.speed)
        assertEquals("", setting.apiKey)
    }

    @Test
    fun aura_is_registered_in_provider_types() {
        assertTrue(TTSProviderSetting.Types.contains(TTSProviderSetting.Aura::class))
    }
}
