package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GenerationSerializationCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `legacy reasoning defaults to native and well formed`() {
        val encoded = json.encodeToJsonElement<UIMessagePart>(
            UIMessagePart.Reasoning(
                reasoning = "legacy",
                source = ReasoningSource.THINK_TAG,
                malformed = true,
            ),
        ).jsonObject
        val legacy = JsonObject(encoded - "source" - "malformed")

        val restored = json.decodeFromJsonElement<UIMessagePart>(legacy)
            as UIMessagePart.Reasoning

        assertEquals(ReasoningSource.PROVIDER_NATIVE, restored.source)
        assertFalse(restored.malformed)
    }

    @Test
    fun `legacy recovery marker defaults to first attempt`() {
        val encoded = json.encodeToJsonElement<UIMessageAnnotation>(
            UIMessageAnnotation.FinalAnswerRecovery(
                commandId = "legacy-command",
                reason = "stop",
                status = FinalAnswerRecoveryStatus.STARTED,
                attempt = 7,
            ),
        ).jsonObject
        val legacy = JsonObject(encoded - "attempt")

        val restored = json.decodeFromJsonElement<UIMessageAnnotation>(legacy)
            as UIMessageAnnotation.FinalAnswerRecovery

        assertEquals(1, restored.attempt)
    }

    @Test
    fun `legacy message chunk without terminal remains decodable`() {
        val encoded = json.encodeToJsonElement(
            MessageChunk(
                id = "legacy",
                model = "model",
                choices = emptyList(),
                terminal = GenerationTerminal.fromProviderReason("stop"),
            ),
        ).jsonObject
        val legacy = JsonObject(encoded - "terminal")

        val restored = json.decodeFromJsonElement<MessageChunk>(legacy)

        assertNull(restored.terminal)
    }
}
