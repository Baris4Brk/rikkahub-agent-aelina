package me.rerere.rikkahub.context

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.service.chat.CommandOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContextDiagnosticsStoreTest {
    @Test
    fun `diagnostics retain counts and provider but never observed text`() {
        val store = ContextDiagnosticsStore(maxEntries = 2)
        val request = ContextRequest(
            commandOrigin = CommandOrigin.APP_UI,
            toolCallOrigin = ToolCallOrigin.LocalChat,
            invocationSurface = ContextInvocationSurface.LOCAL_CHAT,
            assistantId = "assistant",
            conversationId = "conversation",
            runId = "sensitive-run-id",
            commandId = "command",
            isHeadless = false,
            isSubAgent = false,
            targetDisplaySessionId = null,
            settings = AssistantContextSettings(enabled = true),
            allowedSources = setOf(ContextSource.OCR_FALLBACK),
        )
        val snapshot = ContextSnapshot(
            runId = request.runId,
            fragments = listOf(
                ContextFragment(
                    source = ContextSource.OCR_FALLBACK,
                    text = "private screen words",
                    provider = "vision-provider",
                ),
            ),
            omissions = emptyList(),
            collectedAtMs = 42L,
        )

        store.record(request, snapshot)

        val diagnostic = store.entries.value.single()
        assertEquals(20, diagnostic.totalCharacters)
        assertEquals("vision-provider", diagnostic.sources.single().provider)
        assertFalse(diagnostic.toString().contains("private screen words"))
        assertFalse(diagnostic.opaqueRunId.contains(request.runId))
    }
}
