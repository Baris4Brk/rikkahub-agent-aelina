package me.rerere.rikkahub.subagent

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentExecutionProfileRegistryTest {
    @Test
    fun `profiles are isolated by conversation and cleaned only by their owning run`() {
        val registry = SubAgentExecutionProfileRegistry()
        val firstConversation = Uuid.random()
        val secondConversation = Uuid.random()
        val first = profile("run-1")
        val second = profile("run-2")

        assertTrue(registry.register(firstConversation, first))
        assertTrue(registry.register(secondConversation, second))
        assertEquals(first, registry.get(firstConversation))
        assertEquals(second, registry.get(secondConversation))

        registry.remove(firstConversation, expectedRunId = "run-2")
        assertEquals(first, registry.get(firstConversation))

        registry.remove(firstConversation, expectedRunId = "run-1")
        assertNull(registry.get(firstConversation))
        assertEquals(second, registry.get(secondConversation))
    }

    private fun profile(runId: String) = SubAgentExecutionProfile(
        runId = runId,
        effectiveModelId = Uuid.random(),
        promptSource = SubAgentPromptSource.DEFAULT,
        effectiveSystemPrompt = SubAgentDefaults.DEFAULT_SYSTEM_PROMPT,
        effectiveToolNames = setOf("search_web"),
        maxToolTrips = 2,
    )
}
