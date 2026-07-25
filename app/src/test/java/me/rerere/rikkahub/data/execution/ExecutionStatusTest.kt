package me.rerere.rikkahub.data.execution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionStatusTest {
    @Test
    fun `normal lifecycle reaches immutable terminal state`() {
        assertTrue(ExecutionStatus.queued.canTransitionTo(ExecutionStatus.starting))
        assertTrue(ExecutionStatus.starting.canTransitionTo(ExecutionStatus.running))
        assertTrue(ExecutionStatus.running.canTransitionTo(ExecutionStatus.succeeded))
        assertFalse(ExecutionStatus.succeeded.canTransitionTo(ExecutionStatus.running))
    }

    @Test
    fun `unconfirmed cancellation cannot be presented as cancelled`() {
        assertTrue(ExecutionStatus.running.canTransitionTo(ExecutionStatus.cancel_requested))
        assertTrue(ExecutionStatus.cancel_requested.canTransitionTo(ExecutionStatus.orphaned))
        assertFalse(ExecutionStatus.orphaned.canTransitionTo(ExecutionStatus.cancelled))
    }

    @Test
    fun `timeout has its own immutable terminal state`() {
        assertTrue(ExecutionStatus.running.canTransitionTo(ExecutionStatus.terminating))
        assertTrue(ExecutionStatus.terminating.canTransitionTo(ExecutionStatus.timed_out))
        assertFalse(ExecutionStatus.timed_out.canTransitionTo(ExecutionStatus.cancelled))
    }
}
