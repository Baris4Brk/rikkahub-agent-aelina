package me.rerere.rikkahub.data.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionMutationReducerTest {
    @Test
    fun `accepted mutation advances one version and records its evidence`() {
        val current = record(status = ExecutionStatus.starting, version = 4)
        val mutation = mutation(
            current = current,
            target = ExecutionStatus.running,
            verification = VerificationState.RUNTIME_CONFIRMED,
        )

        val next = (ExecutionMutationReducer.reduce(current, mutation, 2_000L) as ExecutionReduction.Next).record

        assertEquals(5L, next.stateVersion)
        assertEquals(ExecutionStatus.running.name, next.status)
        assertEquals(VerificationState.RUNTIME_CONFIRMED.name, next.verificationState)
        assertEquals(ExecutionStateSource.PROBE.name, next.lastStateSource)
        assertEquals("runtime_alive", next.lastReasonCode)
        assertEquals(2_000L, next.heartbeatAtMs)
    }

    @Test
    fun `stale terminal cannot be overwritten`() {
        val current = record(status = ExecutionStatus.succeeded, version = 9)
        val result = ExecutionMutationReducer.reduce(
            current,
            mutation(current, ExecutionStatus.running, VerificationState.RUNTIME_CONFIRMED),
            3_000L,
        )

        assertTrue(result is ExecutionReduction.Terminal)
        assertEquals(9L, (result as ExecutionReduction.Terminal).record.stateVersion)
    }

    @Test
    fun `cancellation request is timestamped without claiming termination`() {
        val current = record(status = ExecutionStatus.running, version = 1)
        val next = (
            ExecutionMutationReducer.reduce(
                current,
                mutation(current, ExecutionStatus.cancel_requested, VerificationState.LIVE_CONFIRMED),
                4_000L,
            ) as ExecutionReduction.Next
            ).record

        assertEquals(ExecutionStatus.cancel_requested.name, next.status)
        assertEquals(4_000L, next.cancellationRequestedAtMs)
        assertNull(next.finishedAtMs)
    }

    @Test
    fun `requested timeout outcome survives nonterminal cancellation states`() {
        val current = record(status = ExecutionStatus.running, version = 1)
        val mutation = mutation(
            current,
            ExecutionStatus.terminating,
            VerificationState.STALE,
        ).copy(requestedTerminalOutcome = RequestedTerminalOutcome.TIMED_OUT)

        val next = (ExecutionMutationReducer.reduce(current, mutation, 4_000L) as
            ExecutionReduction.Next).record

        assertEquals(ExecutionStatus.terminating.name, next.status)
        assertEquals(RequestedTerminalOutcome.TIMED_OUT.name, next.requestedTerminalOutcome)
        assertNull(next.finishedAtMs)
    }

    @Test
    fun `illegal jump is rejected without changing the snapshot`() {
        val current = record(status = ExecutionStatus.queued, version = 2)
        val result = ExecutionMutationReducer.reduce(
            current,
            mutation(current, ExecutionStatus.succeeded, VerificationState.LIVE_CONFIRMED),
            5_000L,
        )

        assertEquals(
            ExecutionReduction.Invalid(ExecutionStatus.queued, ExecutionStatus.succeeded),
            result,
        )
    }

    private fun mutation(
        current: ExecutionRecord,
        target: ExecutionStatus,
        verification: VerificationState,
    ) = ExecutionMutation(
        executionId = current.id,
        mutationId = "mutation-${current.stateVersion}",
        expectedVersion = current.stateVersion,
        source = ExecutionStateSource.PROBE,
        reasonCode = "runtime_alive",
        targetStatus = target,
        verificationState = verification,
    )

    private fun record(status: ExecutionStatus, version: Long) = ExecutionRecord(
        id = "execution",
        traceId = "trace",
        subjectId = "subject",
        subjectType = "LOCAL_SECOND_USER",
        origin = "APP_UI",
        capabilityKeys = "linux.run",
        resourceSummary = "shell:managed",
        runtime = ExecutionRuntime.WORKSPACE.name,
        status = status.name,
        createdAtMs = 1_000L,
        updatedAtMs = 1_000L,
        stateVersion = version,
        verificationState = VerificationState.RECONCILING.name,
    )
}
