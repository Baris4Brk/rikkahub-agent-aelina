package me.rerere.rikkahub.data.execution

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExecutionRecordDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ExecutionRecordDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.executionRecordDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun getInFlight_excludesEveryTerminalStatus() = runBlocking {
        ExecutionStatus.entries.forEachIndexed { index, status ->
            dao.insertIgnore(row(status.name, status, index.toLong()))
        }

        assertEquals(
            ExecutionStatus.IN_FLIGHT.map { it.name }.toSet(),
            dao.getInFlight().map { it.status }.toSet(),
        )
    }

    @Test
    fun pendingApproval_resolutionUsesVersionCasAndIsIdempotent() = runBlocking {
        val approvals = db.pendingToolApprovalDao()
        val pending = PendingToolApprovalRecord(
            approvalId = "approval-1",
            executionId = "tool:run:call",
            traceId = "run",
            toolCallId = "call",
            conversationId = "conversation",
            subjectId = "assistant:conversation",
            subjectType = "LOCAL_SECOND_USER",
            origin = "SystemAssistant",
            capabilityKey = "linux.execute",
            resourceCategory = "workspace",
            requestedAtMs = 10,
            stateVersion = 1,
        )
        approvals.insertIgnore(pending)

        assertEquals(0, approvals.resolveCas(
            approvalId = pending.approvalId,
            expectedVersion = 0,
            nextVersion = 1,
            status = ApprovalStatus.APPROVED.name,
            resolvedAtMs = 20,
            resolutionReason = "approval_granted",
            resolutionRequestId = "request-1",
        ))
        assertEquals(1, approvals.resolveCas(
            approvalId = pending.approvalId,
            expectedVersion = 1,
            nextVersion = 2,
            status = ApprovalStatus.APPROVED.name,
            resolvedAtMs = 20,
            resolutionReason = "approval_granted",
            resolutionRequestId = "request-1",
        ))
        assertEquals(0, approvals.resolveCas(
            approvalId = pending.approvalId,
            expectedVersion = 1,
            nextVersion = 2,
            status = ApprovalStatus.APPROVED.name,
            resolvedAtMs = 20,
            resolutionReason = "approval_granted",
            resolutionRequestId = "request-1",
        ))
        assertEquals(ApprovalStatus.APPROVED.name, approvals.getById("approval-1")?.status)
    }

    private fun row(id: String, status: ExecutionStatus, timestamp: Long) = ExecutionRecord(
        id = id,
        traceId = "trace",
        subjectId = "subject",
        subjectType = "LOCAL_SECOND_USER",
        origin = "APP_UI",
        capabilityKeys = "safe.read",
        resourceSummary = "generic",
        runtime = ExecutionRuntime.LOCAL_TOOL.name,
        status = status.name,
        createdAtMs = timestamp,
        updatedAtMs = timestamp,
        finishedAtMs = timestamp.takeIf { status.isTerminal },
    )
}
