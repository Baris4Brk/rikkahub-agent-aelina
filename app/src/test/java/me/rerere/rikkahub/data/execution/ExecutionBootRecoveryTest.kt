package me.rerere.rikkahub.data.execution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionBootRecoveryTest {
    @Test
    fun `alive persisted cancellation resumes after process restart`() {
        assertTrue(shouldResumeCancellation(update(ExecutionStatus.cancel_requested, alive = true)))
        assertTrue(shouldResumeCancellation(update(ExecutionStatus.terminating, alive = true)))
    }

    @Test
    fun `recovery never sends another stop after confirmed exit`() {
        assertFalse(shouldResumeCancellation(update(ExecutionStatus.cancel_requested, alive = false)))
        assertFalse(shouldResumeCancellation(update(ExecutionStatus.running, alive = true)))
    }

    @Test
    fun `terminating recovery resumes at force phase without state regression`() {
        assertTrue(
            cancellationResumePhase(ExecutionStatus.cancel_requested) ==
                CancellationResumePhase.GRACEFUL,
        )
        assertTrue(
            cancellationResumePhase(ExecutionStatus.terminating) ==
                CancellationResumePhase.FORCE,
        )
    }

    @Test
    fun `boot probes managed child but never a tool parent that lacks a native handle`() {
        assertTrue(shouldProbeRuntimeOnBoot(record(ExecutionKind.MANAGED_PROCESS)))
        assertFalse(shouldProbeRuntimeOnBoot(record(ExecutionKind.TOOL_CALL)))
    }

    private fun update(status: ExecutionStatus, alive: Boolean) = ExecutionProbeUpdate(
        executionId = "workspace:wp_real",
        probe = if (alive) RuntimeProbeResult.Alive("generation:1") else RuntimeProbeResult.Exited(0),
        record = ExecutionRecord(
            id = "workspace:wp_real",
            traceId = "run",
            subjectId = "assistant",
            subjectType = "LOCAL_SECOND_USER",
            origin = "LocalChat",
            capabilityKeys = "linux.background",
            resourceSummary = "workspace",
            runtime = ExecutionRuntime.WORKSPACE.name,
            executionKind = ExecutionKind.MANAGED_PROCESS.name,
            status = status.name,
            createdAtMs = 1,
            updatedAtMs = 2,
            verificationState = VerificationState.RUNTIME_CONFIRMED.name,
            requestedTerminalOutcome = RequestedTerminalOutcome.CANCELLED.name,
        ),
        continuity = RuntimeContinuity.SAME_INSTANCE,
    )

    private fun record(kind: ExecutionKind) = ExecutionRecord(
        id = if (kind == ExecutionKind.MANAGED_PROCESS) "termux:native" else "tool:run:call",
        traceId = "run",
        subjectId = "assistant",
        subjectType = "LOCAL_SECOND_USER",
        origin = "LocalChat",
        capabilityKeys = "linux.background",
        resourceSummary = "termux",
        runtime = ExecutionRuntime.TERMUX.name,
        executionKind = kind.name,
        status = ExecutionStatus.starting.name,
        createdAtMs = 1,
        updatedAtMs = 2,
        verificationState = VerificationState.STALE.name,
    )
}
