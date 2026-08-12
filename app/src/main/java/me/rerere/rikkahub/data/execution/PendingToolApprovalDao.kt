package me.rerere.rikkahub.data.execution

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingToolApprovalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(record: PendingToolApprovalRecord): Long

    @Query("SELECT * FROM pending_tool_approvals WHERE approval_id = :approvalId LIMIT 1")
    suspend fun getById(approvalId: String): PendingToolApprovalRecord?

    /** Exact authority lookup; no newest-row or tool-call fallback is permitted. */
    @Query(
        "SELECT * FROM pending_tool_approvals WHERE approval_id = :approvalId " +
            "AND execution_id = :executionId AND conversation_id = :conversationId " +
            "AND tool_call_id = :toolCallId LIMIT 1",
    )
    suspend fun getExact(
        approvalId: String,
        executionId: String,
        conversationId: String,
        toolCallId: String,
    ): PendingToolApprovalRecord?

    @Query("SELECT * FROM pending_tool_approvals WHERE execution_id = :executionId LIMIT 1")
    suspend fun getByExecutionId(executionId: String): PendingToolApprovalRecord?

    @Query(
        "SELECT * FROM pending_tool_approvals WHERE conversation_id = :conversationId " +
            "AND tool_call_id = :toolCallId ORDER BY requested_at_ms DESC LIMIT 1",
    )
    suspend fun getLatestForToolCall(
        conversationId: String,
        toolCallId: String,
    ): PendingToolApprovalRecord?

    @Query(
        "SELECT * FROM pending_tool_approvals WHERE conversation_id = :conversationId " +
            "AND status = 'PENDING' ORDER BY requested_at_ms ASC",
    )
    suspend fun getPendingForConversation(conversationId: String): List<PendingToolApprovalRecord>

    @Query(
        "SELECT * FROM pending_tool_approvals WHERE conversation_id = :conversationId " +
            "AND status = 'PENDING' ORDER BY requested_at_ms ASC",
    )
    fun observePending(conversationId: String): Flow<List<PendingToolApprovalRecord>>

    @Query("SELECT * FROM pending_tool_approvals WHERE status = 'PENDING' ORDER BY requested_at_ms ASC")
    suspend fun getAllPending(): List<PendingToolApprovalRecord>

    @Query(
        "UPDATE pending_tool_approvals SET status = :status, state_version = :nextVersion, " +
            "resolved_at_ms = :resolvedAtMs, resolution_reason = :resolutionReason, " +
            "resolution_request_id = :resolutionRequestId " +
            "WHERE approval_id = :approvalId AND status = 'PENDING' AND state_version = :expectedVersion",
    )
    suspend fun resolveCas(
        approvalId: String,
        expectedVersion: Long,
        nextVersion: Long,
        status: String,
        resolvedAtMs: Long,
        resolutionReason: String?,
        resolutionRequestId: String,
    ): Int

    @Query(
        "DELETE FROM pending_tool_approvals WHERE status != 'PENDING' " +
            "AND resolved_at_ms IS NOT NULL AND resolved_at_ms < :beforeMs",
    )
    suspend fun deleteResolvedBefore(beforeMs: Long): Int

    @Query(
        "DELETE FROM pending_tool_approvals WHERE status != 'PENDING' AND approval_id NOT IN " +
            "(SELECT approval_id FROM pending_tool_approvals WHERE status != 'PENDING' " +
            "ORDER BY resolved_at_ms DESC LIMIT :keep)",
    )
    suspend fun trimResolved(keep: Int): Int
}
