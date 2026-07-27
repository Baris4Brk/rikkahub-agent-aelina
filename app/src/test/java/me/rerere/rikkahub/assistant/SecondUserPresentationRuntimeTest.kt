package me.rerere.rikkahub.assistant

import me.rerere.rikkahub.data.execution.ApprovalStatus
import me.rerere.rikkahub.data.execution.CompletionPolicy
import me.rerere.rikkahub.data.execution.ExecutionKind
import me.rerere.rikkahub.data.execution.ExecutionRecord
import me.rerere.rikkahub.data.execution.ExecutionRuntime
import me.rerere.rikkahub.data.execution.ExecutionStatus
import me.rerere.rikkahub.data.execution.PendingToolApprovalRecord
import me.rerere.rikkahub.data.execution.RuntimeContinuity
import me.rerere.rikkahub.data.execution.VerificationState
import me.rerere.rikkahub.service.chat.QueueStatus
import me.rerere.rikkahub.service.chat.RuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondUserPresentationRuntimeTest {
    @Test
    fun `fixed priority keeps safety above approval and cancellation`() {
        val state = reduce(
            active = listOf(record(status = ExecutionStatus.cancel_requested)),
            approvals = listOf(approval()),
            safetyBlocked = true,
        )

        assertEquals(SecondUserPresentationStatus.SAFETY_BLOCKED, state.status)
        assertEquals(1, state.pendingApprovalCount)
    }

    @Test
    fun `waiting approval stays above cancellation`() {
        val state = reduce(
            active = listOf(record(status = ExecutionStatus.cancel_requested)),
            approvals = listOf(approval()),
        )

        assertEquals(SecondUserPresentationStatus.WAITING_APPROVAL, state.status)
    }

    @Test
    fun `unconfirmed runtime is recovering or stale and never running`() {
        val recovering = reduce(
            active = listOf(record(verification = VerificationState.RECONCILING)),
        )
        val stale = reduce(
            active = listOf(record(verification = VerificationState.STALE)),
        )

        assertEquals(SecondUserPresentationStatus.RECOVERING, recovering.status)
        assertEquals(SecondUserPresentationStatus.STALE, stale.status)
        assertFalse(recovering.trusted)
        assertFalse(stale.trusted)
    }

    @Test
    fun `failed recent beats successful recent and expires after eight seconds`() {
        val failed = record(
            id = "failed",
            status = ExecutionStatus.failed,
            updatedAtMs = NOW - 1_000,
            finishedAtMs = NOW - 1_000,
        )
        val succeeded = record(
            id = "succeeded",
            status = ExecutionStatus.succeeded,
            updatedAtMs = NOW - 500,
            finishedAtMs = NOW - 500,
        )
        assertEquals(
            SecondUserPresentationStatus.FAILED_RECENTLY,
            reduce(recent = listOf(succeeded, failed)).status,
        )

        val expired = failed.copy(updatedAtMs = NOW - 8_001, finishedAtMs = NOW - 8_001)
        assertEquals(SecondUserPresentationStatus.IDLE, reduce(recent = listOf(expired)).status)
    }

    @Test
    fun `successful parent does not hide detached child after recent window`() {
        val child = record(
            id = "workspace:real_wp",
            kind = ExecutionKind.MANAGED_PROCESS,
            policy = CompletionPolicy.SERVICE_EXPECTED_TO_STAY_ALIVE,
            runtime = ExecutionRuntime.WORKSPACE,
            runtimeInstanceMarker = "generation:200",
        )

        val state = reduce(active = listOf(child))

        assertEquals(SecondUserPresentationStatus.BACKGROUND_SERVICE_RUNNING, state.status)
        assertEquals(1, state.backgroundServiceCount)
        assertEquals(RuntimeContinuity.SAME_INSTANCE, state.continuity)
        assertTrue(state.cancellable)
    }

    @Test
    fun `summaries cap at eight and cannot carry command path or output`() {
        val records = (0 until 12).map { index ->
            record(
                id = "tool:$index",
                capability = if (index % 2 == 0) "files.read" else "device.battery",
                resourceSummary = "secret-command-/private/path-$index",
                updatedAtMs = NOW - index,
            )
        }

        val state = reduce(active = records)

        assertEquals(12, state.totalExecutionCount)
        assertEquals(8, state.executionSummaries.size)
        assertTrue(state.executionSummaries.any { it.category == SafeExecutionCategory.FILES })
        assertTrue(state.executionSummaries.any { it.category == SafeExecutionCategory.DEVICE })
        assertFalse(state.executionSummaries.toString().contains("secret-command"))
        assertFalse(state.executionSummaries.toString().contains("private/path"))
    }

    private fun reduce(
        runtime: RuntimeState = RuntimeState.Idle,
        queue: QueueStatus = QueueStatus(false, 0, null),
        active: List<ExecutionRecord> = emptyList(),
        recent: List<ExecutionRecord> = emptyList(),
        approvals: List<PendingToolApprovalRecord> = emptyList(),
        safetyBlocked: Boolean = false,
    ) = reduceSecondUserPresentation(
        runtime = runtime,
        queue = queue,
        activeRecords = active,
        recentRecords = recent,
        approvals = approvals,
        subAgents = emptyList(),
        safetyBlocked = safetyBlocked,
        nowMs = NOW,
    )

    private fun record(
        id: String = "tool:run:call",
        status: ExecutionStatus = ExecutionStatus.running,
        verification: VerificationState = VerificationState.RUNTIME_CONFIRMED,
        kind: ExecutionKind = ExecutionKind.TOOL_CALL,
        policy: CompletionPolicy = CompletionPolicy.WAIT_FOR_CHILDREN,
        runtime: ExecutionRuntime = ExecutionRuntime.LOCAL_TOOL,
        runtimeInstanceMarker: String? = null,
        capability: String = "linux.execute",
        resourceSummary: String = "tool",
        updatedAtMs: Long = NOW,
        finishedAtMs: Long? = null,
    ) = ExecutionRecord(
        id = id,
        traceId = "run",
        conversationId = "conversation",
        subjectId = "assistant",
        subjectType = "LOCAL_SECOND_USER",
        origin = "APP_UI",
        capabilityKeys = capability,
        resourceSummary = resourceSummary,
        runtime = runtime.name,
        executionKind = kind.name,
        runtimeHandleSummary = id,
        status = status.name,
        createdAtMs = NOW - 2_000,
        updatedAtMs = updatedAtMs,
        finishedAtMs = finishedAtMs,
        stateVersion = 2,
        verificationState = verification.name,
        completionPolicy = policy.name,
        runtimeInstanceMarker = runtimeInstanceMarker,
    )

    private fun approval() = PendingToolApprovalRecord(
        approvalId = "approval",
        executionId = "tool:run:call",
        traceId = "run",
        toolCallId = "call",
        conversationId = "conversation",
        subjectId = "assistant",
        subjectType = "LOCAL_SECOND_USER",
        origin = "APP_UI",
        capabilityKey = "linux.execute",
        resourceCategory = "workspace",
        requestedAtMs = NOW,
        status = ApprovalStatus.PENDING.name,
    )

    private companion object {
        const val NOW = 100_000L
    }
}
