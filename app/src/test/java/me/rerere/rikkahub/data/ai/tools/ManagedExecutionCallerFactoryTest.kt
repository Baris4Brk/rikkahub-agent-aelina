package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.execution.ManagedExecutionCoordinator
import me.rerere.rikkahub.execution.ManagedExecutionRequest
import me.rerere.rikkahub.execution.ManagedExecutionResult
import me.rerere.rikkahub.execution.ManagedExecutionRuntime
import me.rerere.rikkahub.execution.ManagedExecutionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedExecutionCallerFactoryTest {
    @Test
    fun `managed execution requires a complete invocation identity`() {
        val incomplete = ToolInvocationContext(
            callerAssistantId = "assistant-a",
            callerConversationId = "conversation-a",
            callOrigin = ToolCallOrigin.LocalChat,
        )

        assertNull(incomplete.toManagedExecutionCaller(emptyList()))
    }

    @Test
    fun `managed execution exposes only the caller enabled runtimes`() {
        val context = ToolInvocationContext(
            callerAssistantId = "assistant-a",
            callerConversationId = "conversation-a",
            callerRunId = "run-a",
            callOrigin = ToolCallOrigin.LocalChat,
            callerWorkspaceId = "workspace-a",
        )

        val caller = context.toManagedExecutionCaller(
            listOf(LocalToolOption.Termux, LocalToolOption.Ssh),
        )

        assertEquals(
            setOf(
                ManagedExecutionRuntime.WORKSPACE,
                ManagedExecutionRuntime.TERMUX,
                ManagedExecutionRuntime.SSH,
            ),
            caller?.allowedRuntimes,
        )
        assertEquals("workspace-a", caller?.workspaceId)
    }

    @Test
    fun `management tools stay hidden without ownership and expose the complete safe surface`() {
        val complete = ToolInvocationContext(
            callerAssistantId = "assistant-a",
            callerConversationId = "conversation-a",
            callerRunId = "run-a",
            callOrigin = ToolCallOrigin.LocalChat,
        )
        val incomplete = complete.copy(callerRunId = null)

        assertEquals(
            emptyList<String>(),
            managedExecutionToolsForInvocation(
                coordinator = noOpCoordinator,
                options = listOf(LocalToolOption.Termux),
                invocationContext = incomplete,
            ).map { it.name },
        )
        assertEquals(
            listOf("execution_list", "execution_status", "execution_logs", "execution_stop"),
            managedExecutionToolsForInvocation(
                coordinator = noOpCoordinator,
                options = listOf(LocalToolOption.Termux),
                invocationContext = complete,
            ).map { it.name },
        )
    }

    private val noOpCoordinator = object : ManagedExecutionCoordinator {
        override val state = MutableStateFlow(ManagedExecutionState())

        override suspend fun dispatch(request: ManagedExecutionRequest): ManagedExecutionResult =
            error("The tool factory must not dispatch during registration.")
    }
}
