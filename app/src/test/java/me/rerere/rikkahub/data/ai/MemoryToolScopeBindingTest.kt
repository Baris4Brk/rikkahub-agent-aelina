package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryToolScopeBindingTest {
    @Test
    fun newlyCreatedMemoryToolIsBoundAndKeepsProviderMetadata() {
        val tool = UIMessagePart.Tool(
            toolCallId = "new-call",
            toolName = "memory_tool",
            input = "{}",
            metadata = buildJsonObject { put("provider_key", "kept") },
        )

        val bound = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)))
            .bindNewMemoryToolScopes(
                preexistingToolCallIds = emptySet(),
                assistantId = "assistant-a",
                scopeId = "__global__",
            )
            .single()
            .parts
            .filterIsInstance<UIMessagePart.Tool>()
            .single()

        assertNull(memoryToolScopeBindingFailure(bound, "assistant-a", "__global__"))
        assertEquals(JsonPrimitive("kept"), bound.metadata?.get("provider_key"))
    }

    @Test
    fun legacyPendingToolIsNotRetroactivelyBlessed() {
        val legacy = UIMessagePart.Tool(
            toolCallId = "persisted-call",
            toolName = "memory_tool",
            input = "{}",
        )

        val unchanged = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(legacy)))
            .bindNewMemoryToolScopes(
                preexistingToolCallIds = setOf("persisted-call"),
                assistantId = "assistant-a",
                scopeId = "assistant-a",
            )
            .single()
            .parts
            .filterIsInstance<UIMessagePart.Tool>()
            .single()

        assertEquals(
            "memory_scope_binding_missing",
            memoryToolScopeBindingFailure(unchanged, "assistant-a", "assistant-a"),
        )
    }

    @Test
    fun configurationFlipRejectsPersistedMemoryTool() {
        val original = UIMessagePart.Tool(
            toolCallId = "new-call",
            toolName = "memory_query",
            input = "{}",
        )
        val bound = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(original)))
            .bindNewMemoryToolScopes(emptySet(), "assistant-a", "assistant-a")
            .single()
            .parts
            .filterIsInstance<UIMessagePart.Tool>()
            .single()

        assertEquals(
            "memory_scope_changed",
            memoryToolScopeBindingFailure(bound, "assistant-a", "__global__"),
        )
    }

    @Test
    fun unrelatedToolDoesNotRequireMemoryBinding() {
        val tool = UIMessagePart.Tool(
            toolCallId = "shell-call",
            toolName = "workspace_shell",
            input = "{}",
        )

        assertNull(memoryToolScopeBindingFailure(tool, "assistant-a", "assistant-a"))
    }

    @Test
    fun persistedHostCallIsRejectedAfterMemoryCapabilityIsDisabled() {
        val original = UIMessagePart.Tool(
            toolCallId = "new-call",
            toolName = "memory_tool",
            input = "{}",
        )
        val bound = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(original)))
            .bindNewMemoryToolScopes(emptySet(), "assistant-a", "assistant-a")
            .single()
            .parts
            .filterIsInstance<UIMessagePart.Tool>()
            .single()

        assertEquals(
            "memory_capability_disabled",
            memoryToolScopeBindingFailure(
                tool = bound,
                expectedAssistantId = "assistant-a",
                expectedScopeId = "assistant-a",
                memoryCapabilityEnabled = false,
            ),
        )
    }
}
