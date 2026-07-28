package me.rerere.rikkahub.data.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExecutionProbePlanningTest {
    @Test
    fun `workspace never missing becomes lost orphan`() {
        val plan = planExecutionProbeMutation(
            record(completionPolicy = CompletionPolicy.DETACH_BACKGROUND),
            RuntimeProbeResult.Missing(authoritative = true),
            probedAt = 5_000L,
        )

        assertEquals(ExecutionStatus.orphaned, plan.mutation.targetStatus)
        assertEquals(VerificationState.UNKNOWN, plan.mutation.verificationState)
        assertEquals(RuntimeContinuity.LOST, plan.continuity)
        assertEquals("workspace_never_lost", plan.mutation.reasonCode)
    }

    @Test
    fun `new workspace instance remains running and reports restarted`() {
        val plan = planExecutionProbeMutation(
            record(
                completionPolicy = CompletionPolicy.SERVICE_EXPECTED_TO_STAY_ALIVE,
                runtimeInstanceMarker = "generation:100",
            ),
            RuntimeProbeResult.Alive("generation:200"),
            probedAt = 5_000L,
        )

        assertEquals(ExecutionStatus.running, plan.mutation.targetStatus)
        assertEquals(VerificationState.RUNTIME_CONFIRMED, plan.mutation.verificationState)
        assertEquals(RuntimeContinuity.RESTARTED, plan.continuity)
        assertEquals("workspace_process_restarted", plan.mutation.reasonCode)
        assertEquals("generation:200", plan.mutation.runtimeInstanceMarker)
    }

    @Test
    fun `manager loading preserves status as reconciling`() {
        val plan = planExecutionProbeMutation(
            record(status = ExecutionStatus.starting),
            RuntimeProbeResult.Recovering("workspace_manager_loading"),
            probedAt = 5_000L,
        )

        assertEquals(ExecutionStatus.starting, plan.mutation.targetStatus)
        assertEquals(VerificationState.RECONCILING, plan.mutation.verificationState)
        assertEquals(RuntimeContinuity.UNKNOWN, plan.continuity)
    }

    @Test
    fun `non authoritative missing never claims lost or cancellation`() {
        val plan = planExecutionProbeMutation(
            record(status = ExecutionStatus.cancel_requested),
            RuntimeProbeResult.Missing(authoritative = false),
            probedAt = 5_000L,
        )

        assertEquals(ExecutionStatus.cancel_requested, plan.mutation.targetStatus)
        assertEquals(VerificationState.STALE, plan.mutation.verificationState)
        assertEquals(RuntimeContinuity.UNKNOWN, plan.continuity)
        assertEquals("runtime_missing_unconfirmed", plan.mutation.reasonCode)
        assertNull(plan.mutation.cancellationResult)
    }

    @Test
    fun `alive cancellation remains pending instead of reverting to running`() {
        val plan = planExecutionProbeMutation(
            record(status = ExecutionStatus.terminating),
            RuntimeProbeResult.Alive("generation:100"),
            probedAt = 5_000L,
        )

        assertEquals(ExecutionStatus.terminating, plan.mutation.targetStatus)
        assertEquals("runtime_alive_after_cancel", plan.mutation.reasonCode)
    }

    @Test
    fun `confirmed exit after timeout becomes timed out instead of cancelled`() {
        val plan = planExecutionProbeMutation(
            record(
                status = ExecutionStatus.terminating,
                requestedOutcome = RequestedTerminalOutcome.TIMED_OUT,
            ),
            RuntimeProbeResult.Exited(exitCode = 143),
            probedAt = 5_000L,
        )

        assertEquals(ExecutionStatus.timed_out, plan.mutation.targetStatus)
        assertEquals(VerificationState.RUNTIME_CONFIRMED, plan.mutation.verificationState)
        assertEquals("STOPPED_CONFIRMED", plan.mutation.cancellationResult)
    }

    @Test
    fun `authoritative missing after user cancellation becomes cancelled`() {
        val plan = planExecutionProbeMutation(
            record(
                status = ExecutionStatus.cancel_requested,
                requestedOutcome = RequestedTerminalOutcome.CANCELLED,
            ),
            RuntimeProbeResult.Missing(authoritative = true),
            probedAt = 5_000L,
        )

        assertEquals(ExecutionStatus.cancelled, plan.mutation.targetStatus)
        assertEquals(VerificationState.RUNTIME_CONFIRMED, plan.mutation.verificationState)
        assertEquals("STOPPED_CONFIRMED", plan.mutation.cancellationResult)
    }

    private fun record(
        status: ExecutionStatus = ExecutionStatus.running,
        completionPolicy: CompletionPolicy = CompletionPolicy.WAIT_FOR_CHILDREN,
        runtimeInstanceMarker: String? = null,
        requestedOutcome: RequestedTerminalOutcome = RequestedTerminalOutcome.NONE,
    ) = ExecutionRecord(
        id = "workspace:wp_real",
        traceId = "run",
        conversationId = "conversation",
        subjectId = "assistant",
        subjectType = "LOCAL_SECOND_USER",
        origin = "APP_UI",
        capabilityKeys = "process.manage",
        resourceSummary = "workspace:managed",
        runtime = ExecutionRuntime.WORKSPACE.name,
        executionKind = ExecutionKind.MANAGED_PROCESS.name,
        runtimeHandleSummary = "workspace:wp_real",
        status = status.name,
        createdAtMs = 1_000L,
        updatedAtMs = 2_000L,
        stateVersion = 4,
        verificationState = VerificationState.RECONCILING.name,
        completionPolicy = completionPolicy.name,
        runtimeInstanceMarker = runtimeInstanceMarker,
        requestedTerminalOutcome = requestedOutcome.name,
    )
}
