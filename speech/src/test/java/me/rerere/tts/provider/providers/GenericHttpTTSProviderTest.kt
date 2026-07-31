package me.rerere.tts.provider.providers

import me.rerere.tts.provider.GenericHttpBodyEncoding
import me.rerere.tts.provider.GenericHttpHeader
import me.rerere.tts.provider.TTSProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GenericHttpTTSProviderTest {
    private val provider = GenericHttpTTSProvider()

    @Test
    fun `json and form templates escape user text`() {
        val base = TTSProviderSetting.GenericHttp(
            endpoint = "https://example.com/tts",
            bodyTemplate = "{\"text\":\"{{text}}\",\"voice\":\"{{voice}}\"}",
            voice = "a&b",
        )
        assertEquals(
            "{\"text\":\"line\\n\\\"quoted\\\"\",\"voice\":\"a&b\"}",
            provider.renderBody(base, "line\n\"quoted\""),
        )
        assertEquals(
            "text=a%2Bb&voice=a%26b",
            provider.renderBody(
                base.copy(bodyEncoding = GenericHttpBodyEncoding.FORM, bodyTemplate = "text={{text}}&voice={{voice}}"),
                "a+b",
            ),
        )
    }

    @Test
    fun `operit style single brace templates are supported without rescanning inserted text`() {
        val base = TTSProviderSetting.GenericHttp(
            endpoint = "https://example.com/tts",
            bodyTemplate = "{\"input\":\"{text}\",\"voice\":\"{voice}\",\"language\":\"{language}\"}",
            voice = "a&b",
            language = "zh-CN",
        )
        assertEquals(
            "{\"input\":\"literal {text}\",\"voice\":\"a&b\",\"language\":\"zh-CN\"}",
            provider.renderBody(base, "literal {text}"),
        )
        assertEquals(
            "input=a%2Bb&voice=a%26b&language=zh-CN",
            provider.renderBody(
                base.copy(
                    bodyEncoding = GenericHttpBodyEncoding.FORM,
                    bodyTemplate = "input={text}&voice={voice}&language={language}",
                ),
                "a+b",
            ),
        )
    }

    @Test
    fun `plaintext secret in body and unsafe endpoints are rejected`() {
        assertThrows(IllegalStateException::class.java) {
            provider.validateSetting(
                TTSProviderSetting.GenericHttp(
                    endpoint = "https://example.com/tts",
                    bodyTemplate = "{{secret}}",
                ),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            provider.validateSetting(TTSProviderSetting.GenericHttp(endpoint = "http://127.0.0.1:9000/tts"))
        }
    }

    @Test
    fun `vault placeholder is allowed only in bounded non-reserved headers`() {
        provider.validateSetting(
            TTSProviderSetting.GenericHttp(
                endpoint = "https://example.com/tts",
                headers = listOf(GenericHttpHeader("Authorization", "Bearer {{secret}}")),
            ),
        )
        assertThrows(IllegalStateException::class.java) {
            provider.validateSetting(
                TTSProviderSetting.GenericHttp(
                    endpoint = "https://example.com/tts",
                    headers = listOf(GenericHttpHeader("Host", "{{secret}}")),
                ),
            )
        }
    }
}
