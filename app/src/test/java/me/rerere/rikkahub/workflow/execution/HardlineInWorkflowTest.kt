package me.rerere.rikkahub.workflow.execution

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
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
import me.rerere.rikkahub.workflow.model.WorkflowAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Phase 12 — workflow action runner ALWAYS routes through HardlineCommandGuard, regardless
 * of headless mode. The classic `rm -rf /` smoke test, plus a couple of nearby surface
 * checks (unknown tool, plain success path, per-action timeout).
 */
class HardlineInWorkflowTest {

    private val invocation = ToolRuntimeInvocation(
        executionContext = ToolExecutionContext(
            runId = Uuid.random(),
            conversationId = Uuid.random(),
            assistantId = "assistant",
            callOrigin = ToolCallOrigin.TrustedWorkflow,
        ),
    )

    private fun runner() = WorkflowActionRunner(
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
        preflight = ToolRunPreflight { _, _, _, _ -> ToolPreExecutionDecision.Allow },
    )

    private val toastTool = Tool(
        name = "show_toast",
        description = "show",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    private val termuxTool = Tool(
        name = "termux_run_command",
        description = "shell",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        execute = { listOf(UIMessagePart.Text("ran")) },
    )

    @Test fun `rm -rf root blocked by hardline`() = runBlocking {
        val actions = listOf(
            WorkflowAction(
                tool = "termux_run_command",
                args = buildJsonObject { put("command", "rm -rf /") },
                timeoutSeconds = 10,
            ),
        )
        val result = runner().run(actions, listOf(termuxTool), invocation)
        assertFalse("rm -rf / must not succeed", result.success)
        assertTrue(
            "expected hardline error, got ${result.error}",
            result.error?.contains("hardline", ignoreCase = true) == true,
        )
    }

    @Test fun `unknown tool short-circuits`() = runBlocking {
        val actions = listOf(
            WorkflowAction(tool = "format_disk", args = buildJsonObject {}, timeoutSeconds = 10),
        )
        val result = runner().run(actions, listOf(toastTool), invocation)
        assertFalse(result.success)
        assertTrue(result.error?.contains("unknown_tool") == true)
    }

    @Test fun `clean toast action succeeds`() = runBlocking {
        val actions = listOf(
            WorkflowAction(tool = "show_toast", args = buildJsonObject { put("text", "hi") }, timeoutSeconds = 10),
        )
        val result = runner().run(actions, listOf(toastTool), invocation)
        assertTrue("expected success: ${result.error}", result.success)
    }

    @Test fun `aborts on first failure leaves later actions un-run`() = runBlocking {
        var lateExecuted = false
        val lateTool = Tool(
            name = "late_tool",
            description = "",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = { lateExecuted = true; listOf(UIMessagePart.Text("late")) },
        )
        val actions = listOf(
            WorkflowAction(tool = "format_disk", args = buildJsonObject {}, timeoutSeconds = 10),
            WorkflowAction(tool = "late_tool", args = buildJsonObject {}, timeoutSeconds = 10),
        )
        val result = runner().run(actions, listOf(lateTool), invocation)
        assertFalse(result.success)
        assertFalse("late tool must NOT have executed after the early failure", lateExecuted)
    }

    @Test fun `non-hardline ssh command runs`() = runBlocking {
        val ssh = Tool(
            name = "ssh_exec",
            description = "",
            parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
            execute = { listOf(UIMessagePart.Text("ok")) },
        )
        val actions = listOf(
            WorkflowAction(
                tool = "ssh_exec",
                args = buildJsonObject { put("command", "uname -a") },
                timeoutSeconds = 10,
            ),
        )
        val result = runner().run(actions, listOf(ssh), invocation)
        assertTrue("uname -a must not be hardline-blocked", result.success)
    }

    private companion object {
        val TEST_POLICY = ToolExecutionPolicy(
            effects = setOf(ToolEffect.LOCAL_READ),
            concurrency = ToolConcurrency.PARALLEL_SAFE,
            cancellationCapability = ToolCancellationCapability.LOCAL_WAIT_ONLY,
        )
    }
}
