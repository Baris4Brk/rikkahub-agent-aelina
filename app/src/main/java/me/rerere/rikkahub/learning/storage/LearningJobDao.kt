package me.rerere.rikkahub.learning.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Raw Room access for the rebuildable Learning job table.
 *
 * Callers that execute jobs must go through the fenced job store. Error parameters deliberately
 * use [LearningJobErrorCode], rather than arbitrary strings, so malformed or sensitive exception
 * text cannot be persisted through a public DAO method.
 */
@Dao
interface LearningJobDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(job: LearningJobEntity): Long

    @Query("SELECT * FROM learning_jobs WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): LearningJobEntity?

    @Query("SELECT * FROM learning_jobs WHERE dedupe_key = :dedupeKey LIMIT 1")
    suspend fun findByDedupeKey(dedupeKey: String): LearningJobEntity?

    @Query(
        "SELECT * FROM learning_jobs WHERE stream_id = :streamId " +
            "AND replay_generation = :replayGeneration AND source_event_id = :sourceEventId " +
            "AND job_type = :jobType ORDER BY created_at_ms ASC, id ASC LIMIT 2",
    )
    suspend fun listBySourceEventAndType(
        streamId: String,
        replayGeneration: Long,
        sourceEventId: String,
        jobType: String,
    ): List<LearningJobEntity>

    @Query("SELECT MAX(replay_generation) FROM learning_jobs")
    suspend fun maxReplayGeneration(): Long?

    @Query("SELECT COUNT(*) FROM learning_jobs WHERE state IN ('PENDING', 'RETRY', 'RUNNING')")
    suspend fun countActive(): Long

    @Query("SELECT COUNT(*) FROM learning_jobs WHERE state = 'RETRY'")
    suspend fun countRetry(): Long

    @Query("SELECT COUNT(*) FROM learning_jobs WHERE state = 'DEAD_LETTER'")
    suspend fun countDeadLetter(): Long

    @Query(
        "SELECT * FROM learning_jobs WHERE state IN ('PENDING', 'RETRY', 'RUNNING') " +
            "AND job_type IN (:eligibleJobTypes) " +
            "AND updated_at_ms > :nowMs " +
            "ORDER BY updated_at_ms DESC, id ASC LIMIT 1",
    )
    suspend fun findActiveClockRollbackCandidate(
        nowMs: Long,
        eligibleJobTypes: List<String>,
    ): LearningJobEntity?

    @Query(
        "SELECT MAX(updated_at_ms) FROM learning_jobs " +
            "WHERE state IN ('PENDING', 'RETRY', 'RUNNING')",
    )
    suspend fun maxActiveUpdatedAt(): Long?

    @Query(
        "SELECT * FROM learning_jobs WHERE attempts < max_attempts " +
            "AND job_type IN (:eligibleJobTypes) " +
            "AND updated_at_ms <= :nowMs AND (" +
            "(state IN ('PENDING', 'RETRY') AND not_before_ms <= :nowMs) OR " +
            "(state = 'RUNNING' AND lease_until_ms IS NOT NULL AND lease_until_ms <= :nowMs)" +
            ") ORDER BY priority DESC, not_before_ms ASC, created_at_ms ASC, id ASC LIMIT 1",
    )
    suspend fun findClaimCandidate(
        nowMs: Long,
        eligibleJobTypes: List<String>,
    ): LearningJobEntity?

    @Query(
        "UPDATE learning_jobs SET state = 'RUNNING', attempts = attempts + 1, " +
            "lease_process_session_id = :processSessionId, lease_worker_id = :workerId, " +
            "lease_generation = lease_generation + 1, lease_until_ms = :leaseUntilMs, " +
            "last_error_code = NULL, updated_at_ms = :nowMs, finished_at_ms = NULL " +
            "WHERE id = :id AND lease_generation = :expectedGeneration " +
            "AND job_type IN (:eligibleJobTypes) " +
            "AND attempts < max_attempts AND updated_at_ms <= :nowMs AND (" +
            "(state IN ('PENDING', 'RETRY') AND not_before_ms <= :nowMs) OR " +
            "(state = 'RUNNING' AND lease_until_ms IS NOT NULL AND lease_until_ms <= :nowMs)" +
            ")",
    )
    suspend fun claim(
        id: String,
        expectedGeneration: Long,
        processSessionId: String,
        workerId: String,
        nowMs: Long,
        leaseUntilMs: Long,
        eligibleJobTypes: List<String>,
    ): Int

    @Query(
        "UPDATE learning_jobs SET lease_until_ms = :leaseUntilMs, updated_at_ms = :nowMs " +
            "WHERE id = :id AND state = 'RUNNING' " +
            "AND lease_process_session_id = :processSessionId AND lease_worker_id = :workerId " +
            "AND lease_generation = :leaseGeneration AND lease_until_ms > :nowMs " +
            "AND lease_until_ms < :leaseUntilMs AND updated_at_ms <= :nowMs",
    )
    suspend fun heartbeatExtendingOnly(
        id: String,
        processSessionId: String,
        workerId: String,
        leaseGeneration: Long,
        nowMs: Long,
        leaseUntilMs: Long,
    ): Int

    @Query(
        "UPDATE learning_jobs SET state = 'DONE', lease_process_session_id = NULL, " +
            "lease_worker_id = NULL, lease_until_ms = NULL, updated_at_ms = :nowMs, " +
            "finished_at_ms = :nowMs, last_error_code = NULL " +
            "WHERE id = :id AND state = 'RUNNING' " +
            "AND lease_process_session_id = :processSessionId AND lease_worker_id = :workerId " +
            "AND lease_generation = :leaseGeneration AND lease_until_ms > :nowMs " +
            "AND updated_at_ms <= :nowMs",
    )
    suspend fun finishDone(
        id: String,
        processSessionId: String,
        workerId: String,
        leaseGeneration: Long,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE learning_jobs SET state = 'RETRY', not_before_ms = :notBeforeMs, " +
            "lease_process_session_id = NULL, lease_worker_id = NULL, lease_until_ms = NULL, " +
            "lease_generation = lease_generation + 1, last_error_code = :errorCode, " +
            "updated_at_ms = :nowMs, finished_at_ms = NULL " +
            "WHERE id = :id AND state = 'RUNNING' " +
            "AND lease_process_session_id = :processSessionId AND lease_worker_id = :workerId " +
            "AND lease_generation = :leaseGeneration AND lease_until_ms > :nowMs " +
            "AND updated_at_ms <= :nowMs AND attempts < max_attempts",
    )
    suspend fun retry(
        id: String,
        processSessionId: String,
        workerId: String,
        leaseGeneration: Long,
        nowMs: Long,
        notBeforeMs: Long,
        errorCode: LearningJobErrorCode,
    ): Int

    @Query(
        "UPDATE learning_jobs SET state = 'DEAD_LETTER', lease_process_session_id = NULL, " +
            "lease_worker_id = NULL, lease_until_ms = NULL, lease_generation = lease_generation + 1, " +
            "last_error_code = :errorCode, updated_at_ms = :nowMs, finished_at_ms = :nowMs " +
            "WHERE id = :id AND state = 'RUNNING' " +
            "AND lease_process_session_id = :processSessionId AND lease_worker_id = :workerId " +
            "AND lease_generation = :leaseGeneration AND lease_until_ms > :nowMs " +
            "AND updated_at_ms <= :nowMs",
    )
    suspend fun finishDeadLetter(
        id: String,
        processSessionId: String,
        workerId: String,
        leaseGeneration: Long,
        nowMs: Long,
        errorCode: LearningJobErrorCode,
    ): Int

    @Query(
        "UPDATE learning_jobs SET state = 'DEAD_LETTER', lease_process_session_id = NULL, " +
            "lease_worker_id = NULL, lease_until_ms = NULL, lease_generation = lease_generation + 1, " +
            "last_error_code = :errorCode, updated_at_ms = :nowMs, finished_at_ms = :nowMs " +
            "WHERE state IN ('PENDING', 'RETRY', 'RUNNING') AND attempts >= max_attempts " +
            "AND updated_at_ms <= :nowMs",
    )
    suspend fun deadLetterAllExhausted(
        nowMs: Long,
        errorCode: LearningJobErrorCode,
    ): Int

    @Query(
        "UPDATE learning_jobs SET state = 'RETRY', not_before_ms = :notBeforeMs, " +
            "lease_process_session_id = NULL, lease_worker_id = NULL, lease_until_ms = NULL, " +
            "lease_generation = lease_generation + 1, last_error_code = :errorCode, " +
            "updated_at_ms = :nowMs, finished_at_ms = NULL " +
            "WHERE state = 'RUNNING' AND lease_until_ms IS NOT NULL AND lease_until_ms <= :nowMs " +
            "AND updated_at_ms <= :nowMs AND attempts < max_attempts",
    )
    suspend fun recoverExpired(
        nowMs: Long,
        notBeforeMs: Long,
        errorCode: LearningJobErrorCode,
    ): Int

    @Query(
        "UPDATE learning_jobs SET state = 'RETRY', not_before_ms = :notBeforeMs, " +
            "lease_process_session_id = NULL, lease_worker_id = NULL, lease_until_ms = NULL, " +
            "lease_generation = lease_generation + 1, last_error_code = :errorCode, " +
            "updated_at_ms = :nowMs, finished_at_ms = NULL " +
            "WHERE state = 'RUNNING' AND lease_process_session_id IS NOT NULL " +
            "AND lease_process_session_id != :currentProcessSessionId AND updated_at_ms <= :nowMs " +
            "AND attempts < max_attempts",
    )
    suspend fun recoverOtherProcessSessions(
        currentProcessSessionId: String,
        nowMs: Long,
        notBeforeMs: Long,
        errorCode: LearningJobErrorCode,
    ): Int

    @Query(
        "UPDATE learning_jobs SET state = 'CANCELLED', lease_process_session_id = NULL, " +
            "lease_worker_id = NULL, lease_until_ms = NULL, lease_generation = lease_generation + 1, " +
            "last_error_code = :errorCode, updated_at_ms = :nowMs, finished_at_ms = :nowMs " +
            "WHERE state IN ('PENDING', 'RETRY', 'RUNNING')",
    )
    suspend fun cancelAllActive(
        nowMs: Long,
        errorCode: LearningJobErrorCode,
    ): Int

    @Query("DELETE FROM learning_jobs")
    suspend fun deleteAll(): Int

    @Query("DELETE FROM learning_jobs WHERE scope_kind = :scopeKind AND scope_id = :scopeId")
    suspend fun deleteByScope(scopeKind: String, scopeId: String): Int
}
