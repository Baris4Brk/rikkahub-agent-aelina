package me.rerere.rikkahub.diagnostics

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RequestBreakdownDiagnosticsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `breakdown attributes messages and schemas without retaining content`() {
        val secretUserText = "private-user-message-7231"
        val secretMemoryText = "private-memory-8842"
        val secretToolDescription = "private-tool-description-9953"
        val messages = listOf(
            UIMessage.system("system instructions"),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(secretUserText))),
        )
        val tools = listOf(
            Tool(
                name = "list_active_notifications",
                description = secretToolDescription,
                parameters = {
                    InputSchema.Obj(
                        properties = buildJsonObject {
                            put("limit", buildJsonObject { put("type", "integer") })
                        },
                    )
                },
                execute = { emptyList() },
            ),
        )

        val breakdown = RequestBreakdownDiagnostic.create(
            generationId = "conversation-id-that-must-be-hashed",
            providerCallIndex = 1,
            modelId = "test-model",
            providerType = "test-provider",
            requestMode = "normal:stream",
            finalMessages = messages,
            tools = tools,
            assistantPrompt = "system instructions",
            userIdentityPrompt = "user identity",
            toolSystemPrompts = emptyList(),
            memoryPrompt = secretMemoryText,
            recentChatsPrompt = "recent-chat-title",
            dynamicSystemAddendum = "surface-state",
            memoryCount = 1,
            enabledSkillNames = listOf("agent-core"),
        )
        RequestBreakdownDiagnosticsStore.write(temporaryFolder.root, breakdown)

        val output = RequestBreakdownDiagnosticsStore.outputFile(temporaryFolder.root).readText()
        assertFalse(output.contains(secretUserText))
        assertFalse(output.contains(secretMemoryText))
        assertFalse(output.contains(secretToolDescription))
        assertFalse(output.contains("conversation-id-that-must-be-hashed"))
        assertTrue(output.contains("list_active_notifications"))
        assertTrue(output.contains("agent-core"))
        assertTrue(output.contains("estimated_request_tokens"))
        assertEquals(
            breakdown.estimatedMessageTokens + breakdown.estimatedToolSchemaTokens,
            breakdown.estimatedRequestTokens,
        )
    }

    @Test
    fun `provider usage is added without changing attribution`() {
        val base = RequestBreakdownDiagnostic.create(
            generationId = "generation",
            providerCallIndex = 2,
            modelId = "model",
            providerType = "provider",
            requestMode = "normal:stream",
            finalMessages = listOf(UIMessage.user("hello")),
            tools = emptyList(),
            assistantPrompt = "",
            userIdentityPrompt = "",
            toolSystemPrompts = emptyList(),
            memoryPrompt = "",
            recentChatsPrompt = "",
            dynamicSystemAddendum = null,
            memoryCount = 0,
            enabledSkillNames = emptyList(),
        )

        val updated = base.withProviderUsage(
            promptTokens = 40_123,
            cachedTokens = 32_000,
            completionTokens = 18,
        )

        assertEquals(40_123, updated.providerPromptTokens)
        assertEquals(32_000, updated.providerCachedTokens)
        assertEquals(18, updated.providerCompletionTokens)
        assertEquals(base.wireSections, updated.wireSections)
    }
}
