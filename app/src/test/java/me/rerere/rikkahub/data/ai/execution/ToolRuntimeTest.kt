package me.rerere.rikkahub.data.ai.execution

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
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
import me.rerere.rikkahub.data.execution.RequestedTerminalOutcome
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
                assertEquals(expectedContext.copy(toolCallId = "call-1"), context)
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
    fun `host request binds tool call identity before any side effect`() = runBlocking {
        var startedContext: ToolExecutionContext? = null
        var persistedContext: RedactedToolCallContext? = null
        val commandId = Uuid.random()
        val supplied = executionContext().copy(commandId = commandId)
        val runtime = DefaultToolRuntime(
            policyResolver = resolver,
            criticalSink = CriticalToolLifecycleSink { event ->
                if (event.phase == RedactedToolLifecycleEvent.Phase.STARTING) {
                    persistedContext = event.context
                }
            },
        )

        val result = runtime.execute(
            ToolExecutionPlanRequest(
                toolCallId = "host-call",
                toolName = "privileged_run_command",
                args = buildJsonObject {},
                executionContext = supplied,
                startableTool = object : StartableTool {
                    override suspend fun start(
                        args: kotlinx.serialization.json.JsonElement,
                        context: ToolExecutionContext,
                    ): ToolExecutionHandle {
                        startedContext = context
                        return ImmediateHandle("host-bound", listOf(UIMessagePart.Text("ok")))
                    }
                },
                legacyExecute = { error("legacy must not run") },
                runControl = null,
                wallClockBudgetMs = 5_000,
            ),
        )

        assertTrue(result is ToolExecutionPlanResult.Completed)
        assertEquals("host-call", startedContext?.toolCallId)
        assertEquals(commandId, startedContext?.commandId)
        assertEquals("host-call", persistedContext?.toolCallId)
        assertEquals(commandId.toString(), persistedContext?.commandId)
    }

    @Test
    fun `conflicting tool call identity is rejected before side effect`() = runBlocking {
        var started = false
        val runtime = DefaultToolRuntime(policyResolver = resolver)

        val result = runtime.execute(
            ToolExecutionPlanRequest(
                toolCallId = "host-call",
                toolName = "privileged_run_command",
                args = buildJsonObject {},
                executionContext = executionContext().copy(toolCallId = "stale-call"),
                startableTool = object : StartableTool {
                    override suspend fun start(
                        args: kotlinx.serialization.json.JsonElement,
                        context: ToolExecutionContext,
                    ): ToolExecutionHandle {
                        started = true
                        return ImmediateHandle("should-not-start", emptyList())
                    }
                },
                legacyExecute = { error("legacy must not run") },
                runControl = null,
                wallClockBudgetMs = 5_000,
            ),
        )

        assertFalse(started)
        assertEquals(
            "tool_call_identity_mismatch",
            (result as ToolExecutionPlanResult.Rejected).errorCode,
        )
    }

    @Test
    fun `invalid tool call identity is rejected before gate ledger or side effect`() = runBlocking {
        var gateCalled = false
        var startCalled = false
        var lifecycleWrites = 0
        val runtime = DefaultToolRuntime(
            policyResolver = resolver,
            criticalSink = CriticalToolLifecycleSink { lifecycleWrites++ },
        )

        val result = runtime.execute(
            ToolExecutionPlanRequest(
                toolCallId = " ",
                toolName = "privileged_run_command",
                args = buildJsonObject {},
                executionContext = executionContext(),
                startableTool = object : StartableTool {
                    override suspend fun start(
                        args: kotlinx.serialization.json.JsonElement,
                        context: ToolExecutionContext,
                    ): ToolExecutionHandle {
                        startCalled = true
                        return ImmediateHandle("should-not-start", emptyList())
                    }
                },
                legacyExecute = { error("legacy must not run") },
                runControl = null,
                wallClockBudgetMs = 5_000,
                preExecutionGate = {
                    gateCalled = true
                    ToolPreExecutionDecision.Allow
                },
            ),
        )

        assertFalse(gateCalled)
        assertFalse(startCalled)
        assertEquals(0, lifecycleWrites)
        assertEquals(
            "tool_call_identity_invalid",
            (result as ToolExecutionPlanResult.Rejected).errorCode,
        )
    }

    @Test
    fun `host tool call identity has an absolute safe boundary`() {
        assertTrue(isValidHostToolCallId("x".repeat(256)))
        assertFalse(isValidHostToolCallId("x".repeat(257)))
        assertFalse(isValidHostToolCallId("call\u0000unsafe"))
    }

    @Test
    fun `foreign run control is rejected before gate ledger or side effect`() = runBlocking {
        var gateCalled = false
        var startCalled = false
        var lifecycleWrites = 0
        val context = executionContext().copy(
            runId = Uuid.parse("00000000-0000-0000-0000-000000000001"),
        )
        val foreignControl = GenerationRunControl(
            Uuid.parse("00000000-0000-0000-0000-000000000002"),
        )
        val runtime = DefaultToolRuntime(
            policyResolver = resolver,
            criticalSink = CriticalToolLifecycleSink { lifecycleWrites++ },
        )

        val result = runtime.execute(
            ToolExecutionPlanRequest(
                toolCallId = "host-call",
                toolName = "privileged_run_command",
                args = buildJsonObject {},
                executionContext = context,
                startableTool = object : StartableTool {
                    override suspend fun start(
                        args: kotlinx.serialization.json.JsonElement,
                        context: ToolExecutionContext,
                    ): ToolExecutionHandle {
                        startCalled = true
                        return ImmediateHandle("should-not-start", emptyList())
                    }
                },
                legacyExecute = { error("legacy must not run") },
                runControl = foreignControl,
                wallClockBudgetMs = 5_000,
                preExecutionGate = {
                    gateCalled = true
                    ToolPreExecutionDecision.Allow
                },
            ),
        )

        assertFalse(gateCalled)
        assertFalse(startCalled)
        assertEquals(0, lifecycleWrites)
        assertTrue(foreignControl.activeToolCallIds().isEmpty())
        assertEquals(
            "tool_run_identity_mismatch",
            (result as ToolExecutionPlanResult.Rejected).errorCode,
        )
    }

    @Test
    fun `raw result is recorded before a blocked completion ledger`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val ledgerEntered = CompletableDeferred<Unit>()
        val releaseLedger = CompletableDeferred<Unit>()
        val timing = recordingTiming(events)
        val runtime = DefaultToolRuntime(
            policyResolver = resolver,
            criticalSink = CriticalToolLifecycleSink { event ->
                when (event.phase) {
                    RedactedToolLifecycleEvent.Phase.STARTING -> events += "starting-ledger"
                    RedactedToolLifecycleEvent.Phase.COMPLETED -> {
                        events += "ledger-entered"
                        ledgerEntered.complete(Unit)
                        releaseLedger.await()
                        events += "ledger-returned"
                    }
                    else -> Unit
                }
            },
        )

        timing.notifyQueuedSafely()
        val execution = async {
            runtime.execute(
                request(
                    startableTool = object : StartableTool {
                        override suspend fun start(
                            args: kotlinx.serialization.json.JsonElement,
                            context: ToolExecutionContext,
                        ) = ImmediateHandle("ordered", listOf(UIMessagePart.Text("done")))
                    },
                    timingHook = timing,
                ),
            )
        }

        ledgerEntered.await()
        assertFalse(execution.isCompleted)
        assertEquals(
            listOf(
                "queued",
                "preflight",
                "preflight-finished",
                "starting-ledger",
                "execution",
                "raw",
                "ledger-entered",
            ),
            events.toList(),
        )
        releaseLedger.complete(Unit)
        assertTrue(execution.await() is ToolExecutionPlanResult.Completed)
        assertEquals(
            listOf(
                "queued",
                "preflight",
                "preflight-finished",
                "starting-ledger",
                "execution",
                "raw",
                "ledger-entered",
                "ledger-returned",
                "ledger-finished",
                "terminal-COMPLETED",
            ),
            events.toList(),
        )
    }

    @Test
    fun `cancelled tool reports one terminal after execution starts`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val executionStarted = CompletableDeferred<Unit>()
        val timing = object : ToolExecutionTimingHook {
            override fun onPreflightStarted() {
                events += "preflight"
            }

            override fun onPreflightFinished() {
                events += "preflight-finished"
            }

            override fun onExecutionStarted() {
                events += "execution"
                executionStarted.complete(Unit)
            }

            override fun onTerminal(outcome: ToolExecutionTimingOutcome) {
                events += "terminal-$outcome"
            }
        }
        val runtime = DefaultToolRuntime(policyResolver = resolver)
        val blocking = object : ToolExecutionHandle {
            override val executionId: String = "cancelled-handle"
            override suspend fun awaitResult(): List<UIMessagePart> = awaitCancellation()
            override fun requestCancel(reason: ToolCancelReason) = CancelRequestResult.Requested
            override suspend fun awaitTermination(gracePeriod: Duration) =
                ToolTerminationState.StoppedConfirmed
        }
        val execution = async {
            runtime.execute(
                request(
                    startableTool = object : StartableTool {
                        override suspend fun start(
                            args: kotlinx.serialization.json.JsonElement,
                            context: ToolExecutionContext,
                        ): ToolExecutionHandle = blocking
                    },
                    timingHook = timing,
                ),
            )
        }

        executionStarted.await()
        execution.cancelAndJoin()

        assertEquals(
            listOf(
                "preflight",
                "preflight-finished",
                "execution",
                "terminal-CANCELLED",
            ),
            events.toList(),
        )
    }

    @Test
    fun `timing callback failures never alter tool execution`() = runBlocking {
        val timing = object : ToolExecutionTimingHook {
            override fun onQueued() = error("queue diagnostics failed")
            override fun onPreflightStarted() = error("preflight diagnostics failed")
            override fun onPreflightFinished() = error("preflight diagnostics failed")
            override fun onExecutionStarted() = error("execution diagnostics failed")
            override fun onRawResultReady() = error("result diagnostics failed")
            override fun onCompletionLedgerFinished() = error("ledger diagnostics failed")
            override fun onTerminal(outcome: ToolExecutionTimingOutcome) =
                error("terminal diagnostics failed")
        }
        val runtime = DefaultToolRuntime(policyResolver = resolver)

        timing.notifyQueuedSafely()
        val result = runtime.execute(
            request(
                startableTool = object : StartableTool {
                    override suspend fun start(
                        args: kotlinx.serialization.json.JsonElement,
                        context: ToolExecutionContext,
                    ) = ImmediateHandle("safe", listOf(UIMessagePart.Text("done")))
                },
                timingHook = timing,
            ),
        )

        assertTrue(result is ToolExecutionPlanResult.Completed)
    }

    @Test
    fun `tool startup failure reports failed terminal once`() = runBlocking {
        val timingEvents = mutableListOf<String>()
        val runtime = DefaultToolRuntime(policyResolver = resolver)

        val failure = runCatching {
            runtime.execute(
                request(
                    startableTool = object : StartableTool {
                        override suspend fun start(
                            args: kotlinx.serialization.json.JsonElement,
                            context: ToolExecutionContext,
                        ): ToolExecutionHandle = error("startup failed")
                    },
                    timingHook = recordingTiming(timingEvents),
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            listOf(
                "preflight",
                "preflight-finished",
                "execution",
                "terminal-FAILED",
            ),
            timingEvents,
        )
    }

    @Test
    fun `missing identity for a startable tool fails without legacy fallback`() = runBlocking {
        var startCalled = false
        var legacyCalled = false
        val timingEvents = mutableListOf<String>()
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
                timingHook = recordingTiming(timingEvents),
            )
        )

        assertTrue(result is ToolExecutionPlanResult.Rejected)
        assertEquals(
            "tool_execution_context_missing",
            (result as ToolExecutionPlanResult.Rejected).errorCode,
        )
        assertFalse(startCalled)
        assertFalse(legacyCalled)
        assertEquals(
            listOf("preflight", "preflight-finished", "terminal-REJECTED"),
            timingEvents,
        )
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
        val timingEvents = mutableListOf<String>()
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
                timingHook = recordingTiming(timingEvents),
            )
        )

        assertTrue(result is ToolExecutionPlanResult.TimedOut)
        assertEquals(ToolCancelReason.TIMEOUT, cancelReason)
        assertTrue(terminationAwaited)
        assertEquals(
            listOf(
                "preflight",
                "preflight-finished",
                "execution",
                "terminal-TIMED_OUT",
            ),
            timingEvents,
        )
    }

    @Test
    fun `unconfirmed wall clock timeout persists timeout intent`() = runBlocking {
        val events = mutableListOf<RedactedToolLifecycleEvent>()
        val runtime = DefaultToolRuntime(
            policyResolver = resolver,
            criticalSink = CriticalToolLifecycleSink(events::add),
        )
        val blocking = object : ToolExecutionHandle {
            override val executionId: String = "termux:real"
            override suspend fun awaitResult(): List<UIMessagePart> = awaitCancellation()
            override fun requestCancel(reason: ToolCancelReason) = CancelRequestResult.Requested
            override suspend fun awaitTermination(gracePeriod: Duration) =
                ToolTerminationState.Unknown
        }

        runtime.execute(
            ToolExecutionPlanRequest(
                toolCallId = "slow-unconfirmed",
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
            ),
        )

        val timedOut = events.last { it.phase == RedactedToolLifecycleEvent.Phase.TIMED_OUT }
        assertEquals(RequestedTerminalOutcome.TIMED_OUT, timedOut.requestedTerminalOutcome)
        assertEquals(ToolTerminationState.Unknown, timedOut.terminationState)
    }

    @Test
    fun `critical tracking failure blocks a high risk tool before startup`() = runBlocking {
        var startCalled = false
        var attempts = 0
        val runtime = DefaultToolRuntime(
            policyResolver = resolver,
            criticalSink = CriticalToolLifecycleSink {
                attempts++
                error("db unavailable")
            },
            criticalRetryDelaysMs = longArrayOf(0L, 0L, 0L),
        )

        val result = runtime.execute(
            request(
                startableTool = object : StartableTool {
                    override suspend fun start(
                        args: kotlinx.serialization.json.JsonElement,
                        context: ToolExecutionContext,
                    ): ToolExecutionHandle {
                        startCalled = true
                        return ImmediateHandle("must-not-start", emptyList())
                    }
                },
            ),
        )

        assertEquals(3, attempts)
        assertFalse(startCalled)
        assertEquals(
            "execution_tracking_unavailable",
            (result as ToolExecutionPlanResult.Rejected).errorCode,
        )
    }

    @Test
    fun `known parallel read may continue explicitly untracked`() = runBlocking {
        var executed = false
        val readPolicy = ToolExecutionPolicy(
            effects = setOf(ToolEffect.LOCAL_READ),
            concurrency = ToolConcurrency.PARALLEL_SAFE,
            cancellationCapability = ToolCancellationCapability.COOPERATIVE,
        )
        val runtime = DefaultToolRuntime(
            policyResolver = ToolExecutionPolicyResolver { _, _, _ -> readPolicy },
            securityDescriptorResolver = descriptorResolver(),
            criticalSink = CriticalToolLifecycleSink { error("db unavailable") },
            criticalRetryDelaysMs = longArrayOf(0L, 0L, 0L),
        )

        val result = runtime.execute(
            request(
                toolName = "safe_read",
                startableTool = null,
                legacyExecute = {
                    executed = true
                    listOf(UIMessagePart.Text("safe"))
                },
            ),
        ) as ToolExecutionPlanResult.Completed

        assertTrue(executed)
        assertEquals(ToolTrackingState.UNTRACKED, result.trackingState)
    }

    @Test
    fun `terminal tracking failure never asks the model to retry a started side effect`() = runBlocking {
        val health = ExecutionTrackingHealth(nowMs = { 42L })
        val runtime = DefaultToolRuntime(
            policyResolver = resolver,
            criticalSink = CriticalToolLifecycleSink { event ->
                if (event.phase == RedactedToolLifecycleEvent.Phase.COMPLETED) {
                    error("terminal write failed")
                }
            },
            trackingHealth = health,
            criticalRetryDelaysMs = longArrayOf(0L, 0L, 0L),
        )

        val result = runtime.execute(
            request(
                startableTool = object : StartableTool {
                    override suspend fun start(
                        args: kotlinx.serialization.json.JsonElement,
                        context: ToolExecutionContext,
                    ) = ImmediateHandle("side-effect", listOf(UIMessagePart.Text("done")))
                },
            ),
        )

        val completed = result as ToolExecutionPlanResult.Completed
        assertEquals("done", (completed.output.single() as UIMessagePart.Text).text)
        assertEquals(ToolTrackingState.UNTRACKED, completed.trackingState)
        assertTrue(health.state.value.degraded)
        assertEquals(42L, health.state.value.degradedSinceMs)
    }

    private fun request(
        toolName: String = "privileged_run_command",
        startableTool: StartableTool?,
        legacyExecute: suspend (kotlinx.serialization.json.JsonElement) -> List<UIMessagePart> = {
            error("legacy must not run")
        },
        timingHook: ToolExecutionTimingHook? = null,
    ) = ToolExecutionPlanRequest(
        toolCallId = "tracked-call",
        toolName = toolName,
        args = buildJsonObject {},
        executionContext = executionContext(),
        startableTool = startableTool,
        legacyExecute = legacyExecute,
        runControl = null,
        wallClockBudgetMs = 5_000,
        timingHook = timingHook,
    )

    private fun recordingTiming(events: MutableList<String>) =
        object : ToolExecutionTimingHook {
            override fun onQueued() {
                events += "queued"
            }

            override fun onPreflightStarted() {
                events += "preflight"
            }

            override fun onPreflightFinished() {
                events += "preflight-finished"
            }

            override fun onExecutionStarted() {
                events += "execution"
            }

            override fun onRawResultReady() {
                events += "raw"
            }

            override fun onCompletionLedgerFinished() {
                events += "ledger-finished"
            }

            override fun onTerminal(outcome: ToolExecutionTimingOutcome) {
                events += "terminal-$outcome"
            }
        }

    private fun descriptorResolver() = ToolSecurityDescriptorResolver { toolName, _ ->
        ToolSecurityDescriptor(
            toolName = toolName,
            source = ToolDescriptorSource.INTERNAL,
            approval = ToolDescriptorApproval.DEFAULT,
            allowsPermanentApproval = true,
        )
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
