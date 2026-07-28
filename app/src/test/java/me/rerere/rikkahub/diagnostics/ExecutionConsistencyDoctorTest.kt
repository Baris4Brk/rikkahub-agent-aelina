package me.rerere.rikkahub.diagnostics

import me.rerere.rikkahub.data.execution.ExecutionConsistencyMetrics
import me.rerere.rikkahub.data.execution.ExecutionEventRecord
import me.rerere.rikkahub.data.execution.ExecutionRecord
import me.rerere.rikkahub.data.execution.ExecutionRuntime
import me.rerere.rikkahub.data.execution.ExecutionStatus
import me.rerere.rikkahub.data.execution.PendingToolApprovalRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionConsistencyDoctorTest {
    @Test
    fun `redaction accepts stable categories and reason codes`() {
        assertFalse(recordHasRedactionViolation(record()))
        assertFalse(eventHasRedactionViolation(event("runtime_alive_after_cancel")))
        assertFalse(approvalHasRedactionViolation(approval("workspace")))
    }

    @Test
    fun `redaction rejects commands paths credentials and output labels`() {
        assertTrue(recordHasRedactionViolation(record(resourceSummary = "/storage/emulated/0/private")))
        assertTrue(eventHasRedactionViolation(event("command=hidden")))
        assertTrue(approvalHasRedactionViolation(approval("token=hidden")))
        assertTrue(containsSensitiveExecutionDetail("stdout: hidden"))
        assertTrue(containsSensitiveExecutionDetail("C:\\Users\\private"))
        assertTrue(containsSensitiveExecutionDetail("https://host/path?token-value"))
        assertTrue(containsSensitiveExecutionDetail("ssh://user@host/private"))
        assertTrue(containsSensitiveExecutionDetail("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMSJ9.c2lnbmF0dXJlMTIz"))
        assertTrue(containsSensitiveExecutionDetail("owner@example.com"))
        assertTrue(containsSensitiveExecutionDetail("+86 138 0013 8000"))
        assertTrue(containsSensitiveExecutionDetail("AKIAABCDEFGHIJKLMNOP"))
    }

    @Test
    fun `consistency counters retain no probe payload`() {
        val metrics = ExecutionConsistencyMetrics()
        repeat(2) { metrics.recordCasConflict() }
        metrics.recordStaleProbeDiscard()

        assertEquals(2L, metrics.snapshot().casConflicts)
        assertEquals(1L, metrics.snapshot().staleProbeDiscards)
    }

    private fun record(resourceSummary: String = "workspace") = ExecutionRecord(
        id = "workspace:wp_real",
        traceId = "run",
        conversationId = "conversation",
        subjectId = "assistant",
        subjectType = "LOCAL_SECOND_USER",
        origin = "APP_UI",
        capabilityKeys = "linux.background",
        resourceSummary = resourceSummary,
        runtime = ExecutionRuntime.WORKSPACE.name,
        status = ExecutionStatus.running.name,
        createdAtMs = 1,
        updatedAtMs = 2,
        lastReasonCode = "runtime_alive",
    )

    private fun event(reason: String) = ExecutionEventRecord(
        eventId = "event",
        executionId = "workspace:wp_real",
        sequence = 2,
        previousStatus = ExecutionStatus.starting.name,
        nextStatus = ExecutionStatus.running.name,
        previousVerification = "DATABASE_CONFIRMED",
        nextVerification = "RUNTIME_CONFIRMED",
        source = "PROBE",
        reasonCode = reason,
        createdAtMs = 2,
    )

    private fun approval(resourceCategory: String) = PendingToolApprovalRecord(
        approvalId = "approval",
        executionId = "tool:run:call",
        traceId = "run",
        toolCallId = "call",
        conversationId = "conversation",
        subjectId = "assistant",
        subjectType = "LOCAL_SECOND_USER",
        origin = "APP_UI",
        capabilityKey = "linux.execute",
        resourceCategory = resourceCategory,
        requestedAtMs = 1,
    )
}
