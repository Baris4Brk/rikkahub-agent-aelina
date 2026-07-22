package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.display.DisplayAutomationRuntime
import me.rerere.rikkahub.display.DisplayCapability
import me.rerere.rikkahub.display.DisplayRequest
import me.rerere.rikkahub.display.DisplayResult
import me.rerere.rikkahub.display.DisplayRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayTargetResolverTest {
    @Test
    fun `nonzero legacy display id cannot bypass session ownership`() = runBlocking {
        val runtime = RecordingRuntime(DisplayResult.Resolved("owned", 4))
        val resolver = DisplayTargetResolver(runtime)

        val result = resolver.resolve(
            input = buildJsonObject { put("display_id", 4) },
            invocationContext = context,
            requiredCapability = DisplayCapability.SCREENSHOT,
            legacyDisplayIdKey = "display_id",
        )

        assertEquals("display_session_required", (result as DisplayTargetResolution.Error).code)
        assertTrue(runtime.requests.isEmpty())
    }

    @Test
    fun `invalid owned session remains an error and never becomes primary`() = runBlocking {
        val runtime = RecordingRuntime(DisplayResult.Error("display_session_owner_mismatch"))
        val resolver = DisplayTargetResolver(runtime)

        val result = resolver.resolve(
            input = buildJsonObject { put("display_session_id", "other-session") },
            invocationContext = context,
            requiredCapability = DisplayCapability.GESTURE,
        )

        assertEquals("display_session_owner_mismatch", (result as DisplayTargetResolution.Error).code)
        assertEquals(1, runtime.requests.size)
    }

    @Test
    fun `session resolving to the primary display is rejected instead of falling back`() = runBlocking {
        val runtime = RecordingRuntime(DisplayResult.Resolved("owned", 0))
        val resolver = DisplayTargetResolver(runtime)

        val result = resolver.resolve(
            input = buildJsonObject { put("display_session_id", "owned") },
            invocationContext = context,
            requiredCapability = DisplayCapability.TREE,
        )

        assertEquals("display_primary_forbidden", (result as DisplayTargetResolution.Error).code)
        assertEquals(1, runtime.requests.size)
    }

    @Test
    fun `missing session keeps legacy primary display behavior`() = runBlocking {
        val resolver = DisplayTargetResolver(RecordingRuntime(DisplayResult.Error("unused")))

        val result = resolver.resolve(
            input = buildJsonObject {},
            invocationContext = ToolInvocationContext.EMPTY,
            requiredCapability = DisplayCapability.TREE,
        ) as DisplayTargetResolution.Resolved

        assertEquals(0, result.target.displayId)
        assertEquals(null, result.target.sessionId)
    }

    private class RecordingRuntime(
        private val result: DisplayResult,
    ) : DisplayAutomationRuntime {
        override val state: StateFlow<DisplayRuntimeState> = MutableStateFlow(DisplayRuntimeState())
        val requests = mutableListOf<DisplayRequest>()
        override suspend fun dispatch(request: DisplayRequest): DisplayResult {
            requests += request
            return result
        }
    }

    private companion object {
        val context = ToolInvocationContext(
            callerAssistantId = "assistant",
            callerConversationId = "conversation",
            callerRunId = "run",
            callOrigin = ToolCallOrigin.LocalChat,
        )
    }
}
