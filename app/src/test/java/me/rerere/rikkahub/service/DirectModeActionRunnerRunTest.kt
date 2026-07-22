package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.execution.DefaultToolRuntime
import me.rerere.rikkahub.data.ai.execution.ToolCancellationCapability
import me.rerere.rikkahub.data.ai.execution.ToolConcurrency
import me.rerere.rikkahub.data.ai.execution.ToolDescriptorApproval
import me.rerere.rikkahub.data.ai.execution.ToolDescriptorSource
import me.rerere.rikkahub.data.ai.execution.ToolEffect
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPolicy
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPolicyResolver
import me.rerere.rikkahub.data.ai.execution.ToolPreExecutionDecision
import me.rerere.rikkahub.data.ai.execution.ToolRunPreflight
import me.rerere.rikkahub.data.ai.execution.ToolRuntimeInvocation
import me.rerere.rikkahub.data.ai.execution.ToolSecurityDescriptor
import me.rerere.rikkahub.data.ai.execution.ToolSecurityDescriptorResolver
import me.rerere.rikkahub.data.ai.execution.ToolStartableResolver
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Covers DirectModeActionRunner.run() outcome mapping. The focus is the tool-unavailable
 * path: a direct-mode cron job validates its tool list at creation time, but the assistant's
 * enabled-tools set can change later. When a referenced tool is no longer available at fire
 * time the runner must produce a FAILED outcome whose errorMessage NAMES the missing tool,
 * so the failed run-history row tells the user exactly what to re-enable.
 */
class DirectModeActionRunnerRunTest {

    private val invocation = ToolRuntimeInvocation(
        executionContext = ToolExecutionContext(
            runId = Uuid.random(),
            conversationId = Uuid.random(),
            assistantId = "assistant",
            callOrigin = ToolCallOrigin.TrustedWorkflow,
        ),
    )

    private fun runner(
        preflight: ToolRunPreflight = ToolRunPreflight { _, _, _, _ ->
            ToolPreExecutionDecision.Allow
        },
    ) = DirectModeActionRunner(
        toolRuntime = DefaultToolRuntime(
            policyResolver = ToolExecutionPolicyResolver { _, _, _ -> TEST_POLICY },
            securityDescriptorResolver = ToolSecurityDescriptorResolver { name, _ ->
                ToolSecurityDescriptor(
                    toolName = name,
                    source = ToolDescriptorSource.INTERNAL,
                    approval = ToolDescriptorApproval.CALL_DEFINED,
                    allowsPermanentApproval = false,
                )
            },
        ),
        toolStartableResolver = ToolStartableResolver.NONE,
        preflight = preflight,
    )

    private fun fakeTool(name: String): Tool = Tool(
        name = name,
        description = "fake",
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    private fun action(tool: String): DirectModeActionRunner.Action =
        DirectModeActionRunner.Action(tool, JsonObject(emptyMap()))

    @Test
    fun `all tools available yields success`() = runBlocking {
        val seq = runner().run(
            actions = listOf(action("send_notification")),
            availableTools = listOf(fakeTool("send_notification")),
            invocation = invocation,
        )
        assertEquals("success", seq.finalOutcome)
        assertNull(seq.errorMessage)
    }

    @Test
    fun `unavailable tool yields failed outcome naming the tool`() = runBlocking {
        val seq = runner().run(
            actions = listOf(action("send_notification")),
            availableTools = emptyList(), // tool was disabled after job creation
            invocation = invocation,
        )
        assertEquals("failed", seq.finalOutcome)
        val err = requireNotNull(seq.errorMessage)
        assertTrue("error uses the tool_unavailable code", err.contains("tool_unavailable"))
        assertTrue("error names the exact missing tool", err.contains("send_notification"))
        assertTrue("error names the action index", err.contains("action 0"))
    }

    @Test
    fun `unavailable tool in second action is still reported by name`() = runBlocking {
        val seq = runner().run(
            actions = listOf(action("get_battery"), action("send_sms")),
            availableTools = listOf(fakeTool("get_battery")), // send_sms missing
            invocation = invocation,
        )
        assertEquals("failed", seq.finalOutcome)
        val err = requireNotNull(seq.errorMessage)
        assertTrue("error names the missing tool", err.contains("send_sms"))
        assertTrue("error uses the tool_unavailable code", err.contains("tool_unavailable"))
        assertTrue("error points at action index 1", err.contains("action 1"))
    }

    @Test
    fun `runtime preflight blocks direct action before legacy execute`() = runBlocking {
        var executed = false
        val tool = Tool(
            name = "send_notification",
            description = "fake",
            execute = {
                executed = true
                listOf(UIMessagePart.Text("should not run"))
            },
        )

        val seq = runner(
            preflight = ToolRunPreflight { _, _, _, _ ->
                ToolPreExecutionDecision.Deny("blocked_for_test", "test gate")
            },
        ).run(
            actions = listOf(action(tool.name)),
            availableTools = listOf(tool),
            invocation = invocation,
        )

        assertEquals("failed", seq.finalOutcome)
        assertTrue(seq.errorMessage?.contains("blocked_for_test") == true)
        assertFalse("legacy execute must not bypass runtime preflight", executed)
    }

    private companion object {
        val TEST_POLICY = ToolExecutionPolicy(
            effects = setOf(ToolEffect.LOCAL_READ),
            concurrency = ToolConcurrency.PARALLEL_SAFE,
            cancellationCapability = ToolCancellationCapability.LOCAL_WAIT_ONLY,
        )
    }
}
