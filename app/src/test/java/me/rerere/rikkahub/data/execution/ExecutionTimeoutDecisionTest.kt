package me.rerere.rikkahub.data.execution

import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionTimeoutDecisionTest {
    @Test
    fun `unconfirmed managed timeout remains terminating and stale`() {
        val decision = decideTimedOutExecution(
            terminationState = ToolTerminationState.Unknown,
            hasRuntimeHandle = true,
        )

        assertEquals(ExecutionStatus.terminating, decision.target)
        assertEquals(VerificationState.STALE, decision.verification)
    }

    @Test
    fun `confirmed timeout becomes terminal`() {
        val decision = decideTimedOutExecution(
            terminationState = ToolTerminationState.StoppedConfirmed,
            hasRuntimeHandle = true,
        )

        assertEquals(ExecutionStatus.timed_out, decision.target)
        assertEquals(VerificationState.LIVE_CONFIRMED, decision.verification)
    }

    @Test
    fun `timeout before native start is terminal without a probe`() {
        val decision = decideTimedOutExecution(
            terminationState = null,
            hasRuntimeHandle = false,
        )

        assertEquals(ExecutionStatus.timed_out, decision.target)
        assertEquals(VerificationState.LIVE_CONFIRMED, decision.verification)
    }
}
