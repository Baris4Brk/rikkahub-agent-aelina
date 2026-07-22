package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import androidx.paging.PagingSource
import me.rerere.rikkahub.data.db.entity.MemoryCandidateEntity
import me.rerere.rikkahub.data.db.entity.MemoryCaptureEntity
import me.rerere.rikkahub.data.db.entity.MemoryRevisionEntity
import me.rerere.rikkahub.data.db.entity.MemoryEvidenceEntity
import me.rerere.rikkahub.data.db.entity.MemoryLinkEntity

data class MemoryPendingCaptureGroup(
    val conversationId: String,
    val captureSource: String,
)

data class MemoryLatestFailure(
    val errorCode: String?,
    val errorMessage: String?,
)

/** Persistent capture-ledger totals shown in the Memory Center queue card. */
data class MemoryCaptureStatusCounts(
    val pendingCaptures: Int,
    val processingCaptures: Int,
    val processedCaptures: Int,
    val noLongTermSignalCaptures: Int,
    val failedCaptures: Int,
    val pausedCaptures: Int,
    val discardedCaptures: Int,
)

@Dao
interface MemoryV2Dao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCapture(capture: MemoryCaptureEntity): Long

    @Query(
        "SELECT * FROM memory_captures WHERE conversation_id = :conversationId " +
            "AND assistant_message_id = :assistantMessageId " +
            "AND capture_source = :captureSource LIMIT 1",
    )
    suspend fun findCaptureByTurn(
        conversationId: String,
        assistantMessageId: String,
        captureSource: String,
    ): MemoryCaptureEntity?

    @Query(
        "SELECT COUNT(*) FROM memory_captures WHERE scope_id = :scopeId " +
            "AND state IN ('PENDING', 'FAILED', 'PAUSED') AND retry_count < 3",
    )
    suspend fun countPendingCaptures(scopeId: String): Int

    @Query(
        "UPDATE memory_captures SET state = 'PENDING', lease_owner = NULL, " +
            "lease_until_ms = NULL, last_error_code = 'LEASE_EXPIRED', " +
            "retry_count = CASE WHEN retry_count > 0 THEN retry_count - 1 ELSE 0 END, " +
            "last_error_message = 'Memory extraction lease expired', updated_at_ms = :nowMs " +
            "WHERE state = 'PROCESSING' AND lease_until_ms IS NOT NULL AND lease_until_ms < :nowMs",
    )
    suspend fun recoverExpiredLeases(nowMs: Long): Int

    @Query(
        "UPDATE memory_captures SET state = 'PENDING', lease_owner = NULL, lease_until_ms = NULL, " +
            "retry_count = CASE WHEN retry_count > 0 THEN retry_count - 1 ELSE 0 END, " +
            "last_error_code = 'WORK_CANCELLED', last_error_message = NULL, updated_at_ms = :nowMs " +
            "WHERE id IN (:ids) AND state = 'PROCESSING'",
    )
    suspend fun releaseClaimedCaptures(ids: List<String>, nowMs: Long): Int

    @Query(
        "SELECT conversation_id AS conversationId, capture_source AS captureSource " +
            "FROM memory_captures WHERE scope_id = :scopeId " +
            "AND state IN ('PENDING', 'FAILED', 'PAUSED') AND retry_count < 3 " +
            "GROUP BY conversation_id, capture_source ORDER BY MIN(created_at_ms) ASC LIMIT :limit",
    )
    suspend fun findPendingCaptureGroups(scopeId: String, limit: Int): List<MemoryPendingCaptureGroup>

    @Query(
        "SELECT * FROM memory_captures WHERE scope_id = :scopeId " +
            "AND conversation_id = :conversationId " +
            "AND capture_source = :captureSource " +
            "AND state IN ('PENDING', 'FAILED', 'PAUSED') AND retry_count < 3 " +
            "ORDER BY created_at_ms ASC LIMIT :limit",
    )
    suspend fun findClaimableCaptures(
        scopeId: String,
        conversationId: String,
        captureSource: String,
        limit: Int,
    ): List<MemoryCaptureEntity>

    @Query(
        "UPDATE memory_captures SET state = 'PROCESSING', lease_owner = :workerId, " +
            "lease_until_ms = :leaseUntilMs, retry_count = retry_count + 1, " +
            "updated_at_ms = :nowMs, last_error_code = NULL, last_error_message = NULL " +
            "WHERE id = :id AND state IN ('PENDING', 'FAILED', 'PAUSED') " +
            "AND retry_count < 3",
    )
    suspend fun claimCapture(
        id: String,
        workerId: String,
        leaseUntilMs: Long,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE memory_captures SET state = 'PROCESSED', lease_owner = NULL, " +
            "lease_until_ms = NULL, processed_at_ms = :nowMs, updated_at_ms = :nowMs, " +
            "last_error_code = NULL, last_error_message = NULL, " +
            "processing_outcome = :processingOutcome, candidate_count = :candidateCount " +
            "WHERE id IN (:ids) AND state = 'PROCESSING'",
    )
    suspend fun markCapturesProcessed(
        ids: List<String>,
        nowMs: Long,
        processingOutcome: String,
        candidateCount: Int,
    ): Int

    @Query(
        "UPDATE memory_captures SET state = :state, lease_owner = NULL, lease_until_ms = NULL, " +
            "retry_count = CASE WHEN :requiresManualRetry THEN 3 ELSE retry_count END, " +
            "updated_at_ms = :nowMs, last_error_code = :code, last_error_message = :message " +
            "WHERE id IN (:ids) AND state = 'PROCESSING'",
    )
    suspend fun markCapturesFailed(
        ids: List<String>,
        state: String,
        code: String,
        message: String?,
        requiresManualRetry: Boolean,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE memory_captures SET state = 'PAUSED', updated_at_ms = :nowMs, " +
            "last_error_code = :reason WHERE scope_id = :scopeId " +
            "AND state IN ('PENDING', 'FAILED')",
    )
    suspend fun pauseScope(scopeId: String, reason: String, nowMs: Long): Int

    @Query(
        "UPDATE memory_captures SET state = 'PENDING', retry_count = 0, " +
            "lease_owner = NULL, lease_until_ms = NULL, updated_at_ms = :nowMs, " +
            "last_error_code = NULL, last_error_message = NULL " +
            "WHERE scope_id = :scopeId AND state IN ('FAILED', 'PAUSED')",
    )
    suspend fun retryScope(scopeId: String, nowMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCandidate(candidate: MemoryCandidateEntity)

    @Query("SELECT * FROM memory_candidates WHERE id = :candidateId LIMIT 1")
    suspend fun findCandidate(candidateId: String): MemoryCandidateEntity?

    @Query(
        "UPDATE memory_candidates SET status = :status, applied_memory_id = :appliedMemoryId, " +
            "resolution_error = :resolutionError, updated_at_ms = :nowMs WHERE id = :candidateId",
    )
    suspend fun resolveCandidate(
        candidateId: String,
        status: String,
        appliedMemoryId: Int?,
        resolutionError: String?,
        nowMs: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(revision: MemoryRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvidence(evidence: List<MemoryEvidenceEntity>): List<Long>

    @Query("SELECT * FROM memory_evidence WHERE memory_id = :memoryId ORDER BY captured_at_ms ASC LIMIT 3")
    fun observeEvidence(memoryId: Int): Flow<List<MemoryEvidenceEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLinks(links: List<MemoryLinkEntity>): List<Long>

    @Query("SELECT * FROM memory_links WHERE source_memory_id = :memoryId OR target_memory_id = :memoryId ORDER BY weight DESC")
    fun observeLinks(memoryId: Int): Flow<List<MemoryLinkEntity>>

    @Query(
        "SELECT * FROM memory_candidates WHERE scope_id = :scopeId " +
            "AND status IN ('PENDING_REVIEW', 'CONFLICT') ORDER BY created_at_ms DESC",
    )
    fun observePendingCandidates(scopeId: String): Flow<List<MemoryCandidateEntity>>

    @Query(
        "SELECT * FROM memory_candidates WHERE scope_id = :scopeId " +
            "AND status IN ('PENDING_REVIEW', 'CONFLICT') ORDER BY created_at_ms DESC",
    )
    fun pagingPendingCandidates(scopeId: String): PagingSource<Int, MemoryCandidateEntity>

    @Query("SELECT MAX(processed_at_ms) FROM memory_captures WHERE scope_id = :scopeId")
    fun observeLastProcessedAt(scopeId: String): Flow<Long?>

    @Query(
        "SELECT COUNT(*) FROM memory_candidates WHERE scope_id = :scopeId " +
            "AND status IN ('PENDING_REVIEW', 'CONFLICT')",
    )
    fun observePendingCandidateCount(scopeId: String): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM memory_captures WHERE scope_id = :scopeId AND state = 'FAILED'",
    )
    fun observeFailedCaptureCount(scopeId: String): Flow<Int>

    @Query(
        "SELECT last_error_code AS errorCode, last_error_message AS errorMessage " +
            "FROM memory_captures WHERE scope_id = :scopeId AND state = 'FAILED' " +
            "ORDER BY updated_at_ms DESC, created_at_ms DESC LIMIT 1",
    )
    fun observeLatestFailure(scopeId: String): Flow<MemoryLatestFailure?>

    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN state = 'PENDING' THEN 1 ELSE 0 END), 0) AS pendingCaptures, " +
            "COALESCE(SUM(CASE WHEN state = 'PROCESSING' THEN 1 ELSE 0 END), 0) AS processingCaptures, " +
            "COALESCE(SUM(CASE WHEN state = 'PROCESSED' THEN 1 ELSE 0 END), 0) AS processedCaptures, " +
            "COALESCE(SUM(CASE WHEN state = 'PROCESSED' AND processing_outcome = 'NO_LONG_TERM_SIGNAL' " +
            "THEN 1 ELSE 0 END), 0) AS noLongTermSignalCaptures, " +
            "COALESCE(SUM(CASE WHEN state = 'FAILED' THEN 1 ELSE 0 END), 0) AS failedCaptures, " +
            "COALESCE(SUM(CASE WHEN state = 'PAUSED' THEN 1 ELSE 0 END), 0) AS pausedCaptures, " +
            "COALESCE(SUM(CASE WHEN state = 'DISCARDED' THEN 1 ELSE 0 END), 0) AS discardedCaptures " +
            "FROM memory_captures WHERE scope_id = :scopeId",
    )
    fun observeCaptureStatusCounts(scopeId: String): Flow<MemoryCaptureStatusCounts>

    @Query(
        "SELECT COUNT(*) FROM memory_captures WHERE scope_id = :scopeId " +
            "AND state IN ('PENDING', 'PROCESSING', 'PAUSED')",
    )
    fun observePendingCaptureCount(scopeId: String): Flow<Int>

    @Query(
        "SELECT * FROM memory_revisions WHERE memory_id = :memoryId " +
            "ORDER BY revision DESC LIMIT :limit",
    )
    fun observeRevisions(memoryId: Int, limit: Int = 20): Flow<List<MemoryRevisionEntity>>

    @Query(
        "SELECT * FROM memory_revisions WHERE memory_id = :memoryId AND revision = :revision LIMIT 1",
    )
    suspend fun findRevision(memoryId: Int, revision: Int): MemoryRevisionEntity?

    @Query(
        "DELETE FROM memory_revisions WHERE memory_id = :memoryId AND id NOT IN " +
            "(SELECT id FROM memory_revisions WHERE memory_id = :memoryId " +
            "ORDER BY revision DESC LIMIT :keep)",
    )
    suspend fun trimRevisions(memoryId: Int, keep: Int = 20): Int
}
