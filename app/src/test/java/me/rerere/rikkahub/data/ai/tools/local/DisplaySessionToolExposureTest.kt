package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.display.DisplayAutomationRuntime
import me.rerere.rikkahub.display.DisplayRequest
import me.rerere.rikkahub.display.DisplayResult
import me.rerere.rikkahub.display.DisplayRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplaySessionToolExposureTest {
    @Test
    fun `display session tools require the feature screen automation and a local owned caller`() {
        val localCaller = ToolInvocationContext(
            callerAssistantId = "assistant-a",
            callerConversationId = "conversation-a",
            callerRunId = "run-a",
            callOrigin = ToolCallOrigin.LocalChat,
        )

        assertEquals(
            emptyList<String>(),
            displaySessionToolsForInvocation(
                runtime = noOpRuntime,
                featureEnabled = false,
                options = listOf(LocalToolOption.ScreenAutomation),
                invocationContext = localCaller,
            ).map { it.name },
        )
        assertEquals(
            emptyList<String>(),
            displaySessionToolsForInvocation(
                runtime = noOpRuntime,
                featureEnabled = true,
                options = emptyList(),
                invocationContext = localCaller,
            ).map { it.name },
        )
        assertEquals(
            emptyList<String>(),
            displaySessionToolsForInvocation(
                runtime = noOpRuntime,
                featureEnabled = true,
                options = listOf(LocalToolOption.ScreenAutomation),
                invocationContext = localCaller.copy(callOrigin = ToolCallOrigin.Telegram),
            ).map { it.name },
        )
        assertEquals(
            listOf(
                "display_session_create",
                "display_session_list",
                "display_session_status",
                "display_session_close",
            ),
            displaySessionToolsForInvocation(
                runtime = noOpRuntime,
                featureEnabled = true,
                options = listOf(LocalToolOption.ScreenAutomation),
                invocationContext = localCaller,
            ).map { it.name },
        )
    }

    private val noOpRuntime = object : DisplayAutomationRuntime {
        override val state = MutableStateFlow(DisplayRuntimeState())

        override suspend fun dispatch(request: DisplayRequest): DisplayResult =
            error("The tool factory must not dispatch during registration.")
    }
}
