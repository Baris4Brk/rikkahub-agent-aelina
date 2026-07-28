package me.rerere.rikkahub.data.execution

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExecutionRecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(record: ExecutionRecord): Long

    @Query(
        """
        UPDATE execution_records SET
            status = :status,
            runtime = :runtime,
            execution_kind = :executionKind,
            runtime_handle_summary = :runtimeHandleSummary,
            updated_at_ms = :updatedAtMs,
            started_at_ms = :startedAtMs,
            heartbeat_at_ms = :heartbeatAtMs,
            finished_at_ms = :finishedAtMs,
            cancellation_result = :cancellationResult,
            terminal_detail = :terminalDetail,
            state_version = :nextVersion,
            last_state_source = :lastStateSource,
            last_reason_code = :lastReasonCode,
            verification_state = :verificationState,
            last_probe_at_ms = :lastProbeAtMs,
            completion_policy = :completionPolicy,
            runtime_instance_marker = :runtimeInstanceMarker,
            cancellation_requested_at_ms = :cancellationRequestedAtMs,
            requested_terminal_outcome = :requestedTerminalOutcome
        WHERE id = :id AND state_version = :expectedVersion
        """,
    )
    suspend fun compareAndSet(
        id: String,
        expectedVersion: Long,
        nextVersion: Long,
        status: String,
        runtime: String,
        executionKind: String,
        runtimeHandleSummary: String?,
        updatedAtMs: Long,
        startedAtMs: Long?,
        heartbeatAtMs: Long?,
        finishedAtMs: Long?,
        cancellationResult: String?,
        terminalDetail: String?,
        lastStateSource: String,
        lastReasonCode: String?,
        verificationState: String,
        lastProbeAtMs: Long?,
        completionPolicy: String,
        runtimeInstanceMarker: String?,
        cancellationRequestedAtMs: Long?,
        requestedTerminalOutcome: String,
    ): Int

    @Query("SELECT * FROM execution_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ExecutionRecord?

    @Query(
        "SELECT * FROM execution_records WHERE status NOT IN " +
            "('succeeded', 'failed', 'cancelled', 'timed_out', 'orphaned', 'unknown') " +
            "ORDER BY updated_at_ms ASC",
    )
    suspend fun getInFlight(): List<ExecutionRecord>

    @Query(
        "SELECT * FROM execution_records WHERE conversation_id = :conversationId " +
            "AND subject_id = :subjectId AND status NOT IN " +
            "('succeeded', 'failed', 'cancelled', 'timed_out', 'orphaned', 'unknown') " +
            "ORDER BY updated_at_ms ASC",
    )
    suspend fun getInFlightForSubject(
        conversationId: String,
        subjectId: String,
    ): List<ExecutionRecord>

    @Query(
        "SELECT * FROM execution_records WHERE conversation_id = :conversationId " +
            "AND subject_id = :subjectId AND status NOT IN " +
            "('succeeded', 'failed', 'cancelled', 'timed_out', 'orphaned', 'unknown') " +
            "ORDER BY updated_at_ms ASC",
    )
    fun observeActiveForSubject(
        conversationId: String,
        subjectId: String,
    ): Flow<List<ExecutionRecord>>

    @Query(
        "SELECT * FROM execution_records WHERE parent_execution_id = :parentExecutionId " +
            "ORDER BY created_at_ms ASC",
    )
    suspend fun getChildren(parentExecutionId: String): List<ExecutionRecord>

    @Query(
        "SELECT * FROM execution_records WHERE parent_execution_id = :parentExecutionId " +
            "ORDER BY created_at_ms ASC",
    )
    fun observeChildren(parentExecutionId: String): Flow<List<ExecutionRecord>>

    @Query(
        "SELECT * FROM execution_records WHERE runtime = :runtime " +
            "AND runtime_handle_summary = :handle LIMIT 1",
    )
    suspend fun getByRuntimeHandle(runtime: String, handle: String): ExecutionRecord?

    @Query(
        "SELECT * FROM execution_records WHERE updated_at_ms < :beforeMs AND status NOT IN " +
            "('succeeded', 'failed', 'cancelled', 'timed_out', 'orphaned', 'unknown') " +
            "ORDER BY updated_at_ms ASC",
    )
    suspend fun getStaleInFlight(beforeMs: Long): List<ExecutionRecord>

    @Query(
        "SELECT * FROM execution_records WHERE conversation_id = :conversationId " +
            "AND subject_id = :subjectId AND status IN " +
            "('succeeded', 'failed', 'cancelled', 'timed_out', 'orphaned', 'unknown') " +
            "ORDER BY finished_at_ms DESC LIMIT :limit",
    )
    fun observeRecentTerminalForSubject(
        conversationId: String,
        subjectId: String,
        limit: Int,
    ): Flow<List<ExecutionRecord>>

    @Query(
        "SELECT * FROM execution_records WHERE idempotency_key = :idempotencyKey " +
            "ORDER BY updated_at_ms DESC LIMIT 1",
    )
    suspend fun getLatestByIdempotencyKey(idempotencyKey: String): ExecutionRecord?

    @Query("SELECT * FROM execution_records ORDER BY updated_at_ms DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ExecutionRecord>>

    @Query("SELECT * FROM execution_records ORDER BY updated_at_ms DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ExecutionRecord>

    @Query(
        "DELETE FROM execution_records WHERE status IN " +
            "('succeeded', 'failed', 'cancelled', 'timed_out', 'orphaned', 'unknown') " +
            "AND finished_at_ms IS NOT NULL AND finished_at_ms < :beforeMs",
    )
    suspend fun deleteTerminalBefore(beforeMs: Long): Int

    @Query(
        "DELETE FROM execution_records WHERE status IN " +
            "('succeeded', 'failed', 'cancelled', 'timed_out', 'orphaned', 'unknown') " +
            "AND id NOT IN (SELECT id FROM execution_records WHERE status IN " +
            "('succeeded', 'failed', 'cancelled', 'timed_out', 'orphaned', 'unknown') " +
            "ORDER BY finished_at_ms DESC, updated_at_ms DESC LIMIT :keep)",
    )
    suspend fun trimTerminal(keep: Int): Int
}
