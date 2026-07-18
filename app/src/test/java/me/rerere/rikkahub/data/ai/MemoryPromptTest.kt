package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptTest {
    @Test
    fun `empty retrieval adds no prompt and encoded prompt remains valid inside its budget`() {
        assertEquals("", buildMemoryPrompt(emptyList(), maxChars = 200))

        val prompt = buildMemoryPrompt(
            memories = listOf(
                AssistantMemory(1, "quoted \"memory\"\n" + "咖啡".repeat(500)),
                AssistantMemory(2, "must be dropped when the budget is full"),
            ),
            maxChars = 240,
        )

        assertTrue(prompt.isNotBlank())
        assertTrue(prompt.length <= 240)
        Json.parseToJsonElement(prompt.substringAfter('[', missingDelimiterValue = "[]")
            .let { "[$it" }
            .trim())
    }
}
