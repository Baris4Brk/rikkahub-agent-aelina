package me.rerere.rikkahub.data.ai.execution

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.StartableTool
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.uuid.Uuid

class ToolRuntimeTest {
    private val policy = ToolExecutionPolicy(
        effects = setOf(ToolEffect.SHELL_EXECUTION),
        concurrency = ToolConcurrency.GLOBAL_SERIAL,
        cancellationCapability = ToolCancellationCapability.REAL,
    )
    private val resolver = ToolExecutionPolicyResolver { _, _, _ -> policy }

    @Test
    fun `startable adapter is used instead of the legacy executor`() = runBlocking {
        var startCalled = false
        var legacyCalled = false
        val expectedContext = executionContext()
        val control = GenerationRunControl(expectedContext.runId)
        val runtime = DefaultToolRuntime(policyResolver = resolver)
        val startable = object : StartableTool {
            override suspend fun start(
                args: kotlinx.serialization.json.JsonElement,
                context: ToolExecutionContext,
            ): ToolExecutionHandle {
                startCalled = true
                assertEquals(expectedContext, context)
                return ImmediateHandle("real-handle", listOf(UIMessagePart.Text("real result")))
            }
        }

        val result = runtime.execute(
            ToolExecutionPlanRequest(
                toolCallId = "call-1",
                toolName = "privileged_run_command",
                args = buildJsonObject {},
                executionContext = expectedContext,
                startableTool = startable,
                legacyExecute = {
                    legacyCalled = true
                    listOf(UIMessagePart.Text("legacy result"))
                },
                runControl = control,
                wallClockBudgetMs = 5_000,
            )
        )

        assertTrue(startCalled)
        assertFalse(legacyCalled)
        assertEquals("real-handle", (result as ToolExecutionPlanResult.Completed).executionId)
        assertEquals("real result", (result.output.single() as UIMessagePart.Text).text)
        assertTrue(control.activeToolCallIds().isEmpty())
    }

    @Test
    fun `missing identity for a startable tool fails without legacy fallback`() = runBlocking {
        var startCalled = false
        var legacyCalled = false
        val runtime = DefaultToolRuntime(policyResolver = resolver)

        val result = runtime.execute(
            ToolExecutionPlanRequest(
                toolCallId = "call-without-owner",
                toolName = "privileged_run_command",
                args = buildJsonObject {},
                executionContext = null,
                startableTool = object : StartableTool {
                    override suspend fun start(
                        args: kotlinx.serialization.json.JsonElement,
                        context: ToolExecutionContext,
                    ): ToolExecutionHandle {
                        startCalled = true
                        return ImmediateHandle("should-not-start", emptyList())
                    }
                },
                legacyExecute = {
                    legacyCalled = true
                    emptyList()
                },
                runControl = null,
                wallClockBudgetMs = 5_000,
            )
        )

        assertTrue(result is ToolExecutionPlanResult.Rejected)
        assertEquals(
            "tool_execution_context_missing",
            (result as ToolExecutionPlanResult.Rejected).errorCode,
        )
        assertFalse(startCalled)
        assertFalse(legacyCalled)
    }

    @Test
    fun `explicit plugin descriptor permits unknown-effect serial plugin only`() = runBlocking {
        val runtime = DefaultToolRuntime(
            policyResolver = DefaultToolExecutionPolicyResolver(),
            securityDescriptorResolver = DefaultToolSecurityDescriptorResolver(
                pluginToolKnown = { it == "plugin__0123456789ab__read_status" },
            ),
        )

        val assessment = runtime.assess(
            ToolAssessmentRequest(
                toolName = "plugin__0123456789ab__read_status",
                args = buildJsonObject {},
                context = executionContext(),
            )
        )

        assertTrue(assessment.accepted)
        assertEquals(ToolDescriptorSource.PLUGIN, assessment.securityDescriptor?.source)
        assertEquals(ToolConcurrency.GLOBAL_SERIAL, assessment.policy.concurrency)
        assertFalse(assessment.policy.allowReadOnlyParallelBatch)
    }

    @Test
    fun `hard safety gate runs before an interceptor and can block startup`() = runBlocking {
        val events = mutableListOf<String>()
        val runtime = DefaultToolRuntime(
            policyResolver = resolver,
            interceptors = listOf(
                object : ToolCallInterceptor {
                    override suspend fun intercept(
                        context: RedactedToolCallContext,
                    ): ToolHookDecision {
                        events += "interceptor"
                        return ToolHookDecision.Proceed
                    }
                }
            ),
        )

        val result = runtime.execute(
            ToolExecutionPlanRequest(
                toolCallId = "blocked-call",
                toolName = "privileged_run_command",
                args = buildJsonObject {},
                executionContext = executionContext(),
                startableTool = null,
                legacyExecute = {
                    events += "execute"
                    emptyList()
                },
                runControl = null,
                wallClockBudgetMs = 5_000,
                preExecutionGate = {
                    events += "hard-gate"
                    ToolPreExecutionDecision.Deny("tool_blocked", "safety floor")
                },
            )
        )

        assertEquals(listOf("hard-gate"), events)
        assertEquals("tool_blocked", (result as ToolExecutionPlanResult.Rejected).errorCode)
    }

    @Test
    fun `wall clock timeout requests real handle cancellation with timeout reason`() = runBlocking {
        var cancelReason: ToolCancelReason? = null
        var terminationAwaited = false
        val runtime = DefaultToolRuntime(policyResolver = resolver)
        val blocking = object : ToolExecutionHandle {
            override val executionId: String = "blocking-real-handle"
            override suspend fun awaitResult(): List<UIMessagePart> = awaitCancellation()
            override fun requestCancel(reason: ToolCancelReason): CancelRequestResult {
                cancelReason = reason
                return CancelRequestResult.Requested
            }
            override suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState {
                terminationAwaited = true
                return ToolTerminationState.StoppedConfirmed
            }
        }

        val result = runtime.execute(
            ToolExecutionPlanRequest(
                toolCallId = "slow-call",
                toolName = "privileged_run_command",
                args = buildJsonObject {},
                executionContext = executionContext(),
                startableTool = object : StartableTool {
                    override suspend fun start(
                        args: kotlinx.serialization.json.JsonElement,
                        context: ToolExecutionContext,
                    ): ToolExecutionHandle = blocking
                },
                legacyExecute = { error("legacy must not run") },
                runControl = null,
                wallClockBudgetMs = 25,
            )
        )

        assertTrue(result is ToolExecutionPlanResult.TimedOut)
        assertEquals(ToolCancelReason.TIMEOUT, cancelReason)
        assertTrue(terminationAwaited)
    }

    private fun executionContext() = ToolExecutionContext(
        runId = Uuid.random(),
        conversationId = Uuid.random(),
        assistantId = "assistant-1",
        callOrigin = ToolCallOrigin.LocalChat,
    )

    private class ImmediateHandle(
        override val executionId: String,
        private val result: List<UIMessagePart>,
    ) : ToolExecutionHandle {
        override suspend fun awaitResult(): List<UIMessagePart> = result
        override fun requestCancel(reason: ToolCancelReason) = CancelRequestResult.Requested
        override suspend fun awaitTermination(gracePeriod: Duration) =
            ToolTerminationState.StoppedConfirmed
    }
}
