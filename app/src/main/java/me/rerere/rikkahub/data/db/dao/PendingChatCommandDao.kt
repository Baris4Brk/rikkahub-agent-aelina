package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.PendingChatCommandEntity

@Dao
interface PendingChatCommandDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(command: PendingChatCommandEntity): Long

    @Query("SELECT * FROM pending_chat_commands WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PendingChatCommandEntity?

    @Query("SELECT * FROM pending_chat_commands WHERE id = :id LIMIT 1")
    fun observeById(id: String): kotlinx.coroutines.flow.Flow<PendingChatCommandEntity?>

    @Query("SELECT * FROM pending_chat_commands WHERE idempotencyKey = :key LIMIT 1")
    suspend fun findByIdempotencyKey(key: String): PendingChatCommandEntity?

    @Query(
        "SELECT * FROM pending_chat_commands WHERE conversationId = :conversationId " +
            "AND state IN ('PENDING', 'INTERRUPTED', 'WAITING_APPROVAL') " +
            "AND (expiresAt IS NULL OR expiresAt > :now) " +
            "ORDER BY priority DESC, sequence ASC, id ASC LIMIT :limit"
    )
    suspend fun findPending(conversationId: String, now: Long, limit: Int): List<PendingChatCommandEntity>

    @Query(
        "SELECT * FROM pending_chat_commands WHERE state IN ('PENDING', 'INTERRUPTED', 'WAITING_APPROVAL') " +
            "AND (expiresAt IS NULL OR expiresAt > :now) " +
            "ORDER BY priority DESC, sequence ASC, id ASC LIMIT :limit"
    )
    suspend fun findPendingGlobally(now: Long, limit: Int): List<PendingChatCommandEntity>

    @Query(
        "SELECT * FROM pending_chat_commands WHERE conversationId = :conversationId " +
            "AND dedupeKey = :dedupeKey " +
            "AND state IN ('PENDING', 'INTERRUPTED', 'RUNNING', 'WAITING_APPROVAL') " +
            "ORDER BY sequence ASC, id ASC LIMIT 1"
    )
    suspend fun findActiveByDedupeKey(conversationId: String, dedupeKey: String): PendingChatCommandEntity?

    /** Replay candidates for one runtime only. WAITING rows are suspension barriers, not work. */
    @Query(
        "SELECT * FROM pending_chat_commands WHERE conversationId = :conversationId " +
            "AND state IN ('PENDING', 'INTERRUPTED') " +
            "AND (expiresAt IS NULL OR expiresAt > :now) " +
            "ORDER BY priority DESC, sequence ASC, id ASC LIMIT :limit"
    )
    suspend fun findReplayableForConversation(
        conversationId: String,
        now: Long,
        limit: Int,
    ): List<PendingChatCommandEntity>

    @Query(
        "SELECT * FROM pending_chat_commands WHERE conversationId = :conversationId " +
            "AND state = 'WAITING_APPROVAL' ORDER BY sequence ASC, id ASC LIMIT :limit"
    )
    suspend fun findWaitingForConversation(
        conversationId: String,
        limit: Int,
    ): List<PendingChatCommandEntity>

    /**
     * Bounded WAITING snapshot. Callers that require an all-rows decision must compare this
     * result with [countWaitingForConversation] inside the same database transaction.
     */
    @Query(
        "SELECT * FROM pending_chat_commands WHERE conversationId = :conversationId " +
            "AND state = 'WAITING_APPROVAL' ORDER BY sequence ASC, id ASC LIMIT :limit"
    )
    suspend fun listWaitingForConversation(
        conversationId: String,
        limit: Int,
    ): List<PendingChatCommandEntity>

    @Query(
        "SELECT COUNT(*) FROM pending_chat_commands WHERE conversationId = :conversationId " +
            "AND state = 'WAITING_APPROVAL'"
    )
    suspend fun countWaitingForConversation(conversationId: String): Int

    /**
     * Bounded lineage snapshot. A transactional all-lineage operation must pair this with
     * [countWaitingByLineage] so LIMIT can never silently leave a WAITING authority row behind.
     */
    @Query(
        "SELECT * FROM pending_chat_commands WHERE conversationId = :conversationId " +
            "AND lineageId = :lineageId AND state = 'WAITING_APPROVAL' " +
            "ORDER BY sequence ASC, id ASC LIMIT :limit"
    )
    suspend fun listWaitingByLineage(
        conversationId: String,
        lineageId: String,
        limit: Int,
    ): List<PendingChatCommandEntity>

    @Query(
        "SELECT COUNT(*) FROM pending_chat_commands WHERE conversationId = :conversationId " +
            "AND lineageId = :lineageId AND state = 'WAITING_APPROVAL'"
    )
    suspend fun countWaitingByLineage(conversationId: String, lineageId: String): Int

    /** Sequence allocation is authority state and must be read in the admission transaction. */
    @Query(
        "SELECT MAX(sequence) FROM pending_chat_commands WHERE conversationId = :conversationId"
    )
    suspend fun maxSequenceForConversation(conversationId: String): Long?

    @Query("SELECT * FROM pending_chat_commands WHERE conversationId = :conversationId ORDER BY sequence ASC")
    fun observe(conversationId: String): Flow<List<PendingChatCommandEntity>>

    @Query("SELECT * FROM pending_chat_commands WHERE state IN ('PENDING', 'INTERRUPTED', 'WAITING_APPROVAL') ORDER BY sequence ASC")
    fun observePending(): Flow<List<PendingChatCommandEntity>>

    @Query("SELECT * FROM pending_chat_commands WHERE state IN ('PENDING', 'INTERRUPTED', 'WAITING_APPROVAL', 'RUNNING')")
    suspend fun listActive(): List<PendingChatCommandEntity>

    @Query(
        "UPDATE pending_chat_commands SET type = :type, payloadJson = :payloadJson " +
            ", stateVersion = stateVersion + 1 " +
            "WHERE id = :id AND state = 'PENDING'"
    )
    suspend fun rewritePendingCommand(id: String, type: String, payloadJson: String): Int

    @Query(
        "UPDATE pending_chat_commands SET state = 'RUNNING', claimedBy = :workerId, " +
            "leaseUntil = :leaseUntil, startedAt = COALESCE(startedAt, :now), attempt = attempt + 1, " +
            "stateVersion = stateVersion + 1 " +
            "WHERE id = :id AND state IN ('PENDING', 'INTERRUPTED') " +
            "AND (expiresAt IS NULL OR expiresAt > :now)"
    )
    suspend fun claim(id: String, workerId: String, leaseUntil: Long, now: Long): Int

    @Query(
        "UPDATE pending_chat_commands SET leaseUntil = :leaseUntil, stateVersion = stateVersion + 1 " +
            "WHERE id = :id AND state = 'RUNNING' AND claimedBy = :workerId"
    )
    suspend fun renewLease(id: String, workerId: String, leaseUntil: Long): Int

    @Query(
        "UPDATE pending_chat_commands SET state = 'INTERRUPTED', claimedBy = NULL, leaseUntil = NULL, " +
            "lastErrorCode = 'LEASE_EXPIRED', lastErrorMessage = :message, " +
            "stateVersion = stateVersion + 1 " +
            "WHERE state = 'RUNNING' AND leaseUntil IS NOT NULL AND leaseUntil < :now"
    )
    suspend fun interruptExpired(now: Long, message: String = "Worker lease expired"): Int

    @Query(
        "UPDATE pending_chat_commands SET state = :state, finishedAt = :finishedAt, " +
            "claimedBy = NULL, leaseUntil = NULL, lastErrorCode = :errorCode, " +
            "lastErrorMessage = :errorMessage, stateVersion = stateVersion + 1 " +
            "WHERE id = :id AND state = :expectedState"
    )
    suspend fun finish(
        id: String,
        state: String,
        finishedAt: Long,
        expectedState: String = "RUNNING",
        errorCode: String? = null,
        errorMessage: String? = null,
    ): Int

    @Query(
        "UPDATE pending_chat_commands SET state = :state, finishedAt = :finishedAt, " +
            "claimedBy = NULL, leaseUntil = NULL, lastErrorCode = :errorCode, " +
            "lastErrorMessage = :errorMessage, stateVersion = stateVersion + 1 " +
            "WHERE id = :id AND state IN ('PENDING', 'INTERRUPTED', 'WAITING_APPROVAL', 'RUNNING')"
    )
    suspend fun resolvePending(
        id: String,
        state: String,
        finishedAt: Long,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM pending_chat_commands WHERE conversationId = :conversationId " +
            "AND state IN ('PENDING', 'INTERRUPTED', 'RUNNING', 'WAITING_APPROVAL')"
    )
    suspend fun countActive(conversationId: String): Int

    @Deprecated("Use CommandStateTransaction.cancelConversationPending for per-row CAS and outbox")
    @Query(
        "UPDATE pending_chat_commands SET state = 'CANCELLED', finishedAt = 0, " +
            "claimedBy = NULL, leaseUntil = NULL, lastErrorCode = 'LEGACY_CLEAR_PENDING', " +
            "lastErrorMessage = NULL, stateVersion = stateVersion + 1 " +
            "WHERE conversationId = :conversationId AND state = 'PENDING'"
    )
    suspend fun clearPending(conversationId: String): Int

    @Query(
        "UPDATE pending_chat_commands SET state = 'CANCELLED', finishedAt = :finishedAt, " +
            "claimedBy = NULL, leaseUntil = NULL, lastErrorCode = :code, " +
            "lastErrorMessage = :message, stateVersion = stateVersion + 1 " +
            "WHERE authoritySubjectId = :subjectId " +
            "AND state IN ('PENDING', 'INTERRUPTED', 'WAITING_APPROVAL', 'RUNNING')",
    )
    @Deprecated("Use CommandStateTransaction.cancelByAuthoritySubject for per-row CAS and outbox")
    suspend fun cancelByAuthoritySubject(
        subjectId: String,
        finishedAt: Long,
        code: String = "SECOND_USER_AUTHORITY_REVOKED",
        message: String = "Second-user authority was reassigned or revoked",
    ): Int

    /**
     * v38 rows did not carry an authority epoch.  During revocation the old protected
     * conversation is the only safe scope for those rows: do not replay them into a newly
     * assigned authority subject.
     */
    @Query(
        "UPDATE pending_chat_commands SET state = 'CANCELLED', finishedAt = :finishedAt, " +
            "claimedBy = NULL, leaseUntil = NULL, lastErrorCode = :code, " +
            "lastErrorMessage = :message, stateVersion = stateVersion + 1 " +
            "WHERE conversationId = :conversationId AND authoritySubjectId IS NULL " +
            "AND state IN ('PENDING', 'INTERRUPTED', 'WAITING_APPROVAL', 'RUNNING')",
    )
    @Deprecated(
        "Use CommandStateTransaction.cancelLegacyUnscopedForConversation for per-row CAS and outbox"
    )
    suspend fun cancelLegacyUnscopedForConversation(
        conversationId: String,
        finishedAt: Long,
        code: String = "SECOND_USER_AUTHORITY_LEGACY_UNSCOPED",
        message: String = "Legacy command requires a new second-user submission",
    ): Int

    // Fenced v2 primitives. Callers must use CommandStateTransaction so the state CAS and
    // append-only authority event share one transaction.

    @Query(
        "UPDATE pending_chat_commands SET state = 'RUNNING', claimedBy = :workerId, " +
            "leaseUntil = :leaseUntil, startedAt = COALESCE(startedAt, :now), " +
            "attempt = attempt + 1, stateVersion = stateVersion + 1 " +
            "WHERE id = :id AND stateVersion = :expectedVersion " +
            "AND state IN ('PENDING', 'INTERRUPTED') " +
            "AND assistantIdSnapshot IS NOT NULL AND lineageId IS NOT NULL " +
            "AND branchAnchorMessageId IS NOT NULL " +
            "AND (expiresAt IS NULL OR expiresAt > :now)"
    )
    suspend fun claimFenced(
        id: String,
        expectedVersion: Long,
        workerId: String,
        leaseUntil: Long,
        now: Long,
    ): Int

    @Query(
        "UPDATE pending_chat_commands SET leaseUntil = :leaseUntil, stateVersion = stateVersion + 1 " +
            "WHERE id = :id AND state = 'RUNNING' AND stateVersion = :expectedVersion " +
            "AND claimedBy = :workerId AND leaseUntil = :expectedLeaseUntil " +
            "AND leaseUntil >= :now"
    )
    suspend fun renewLeaseFenced(
        id: String,
        expectedVersion: Long,
        workerId: String,
        expectedLeaseUntil: Long,
        leaseUntil: Long,
        now: Long,
    ): Int

    @Query(
        "UPDATE pending_chat_commands SET state = :nextState, finishedAt = :finishedAt, " +
            "claimedBy = NULL, leaseUntil = NULL, lastErrorCode = :errorCode, " +
            "lastErrorMessage = :errorMessage, stateVersion = stateVersion + 1 " +
            "WHERE id = :id AND state = 'RUNNING' AND stateVersion = :expectedVersion " +
            "AND claimedBy = :workerId AND leaseUntil = :expectedLeaseUntil " +
            "AND leaseUntil >= :now"
    )
    suspend fun finishClaimedFenced(
        id: String,
        expectedVersion: Long,
        workerId: String,
        expectedLeaseUntil: Long,
        nextState: String,
        finishedAt: Long,
        now: Long,
        errorCode: String?,
        errorMessage: String?,
    ): Int

    /**
     * Combined graph/command authority CAS. Completion and exact source identity are written only
     * by a caller that owns the surrounding Conversation transaction.
     */
    @Query(
        "UPDATE pending_chat_commands SET state = :nextState, finishedAt = :finishedAt, " +
            "claimedBy = NULL, leaseUntil = NULL, lastErrorCode = :errorCode, " +
            "lastErrorMessage = NULL, completionKind = :completionKind, " +
            "conversationSourceRevision = :conversationSourceRevision, " +
            "resultAssistantMessageId = :resultMessageId, " +
            "resultAssistantMessageRevision = :resultMessageRevision, " +
            "stateVersion = stateVersion + 1 " +
            "WHERE id = :id AND conversationId = :conversationId " +
            "AND state = 'RUNNING' AND stateVersion = :expectedVersion " +
            "AND claimedBy = :workerId AND leaseUntil = :expectedLeaseUntil " +
            "AND leaseUntil >= :now AND :nextState IN " +
            "('COMPLETED', 'FAILED', 'CANCELLED', 'MANUAL_CONFIRMATION') " +
            "AND :completionKind IN ('GENERATION_FINAL_SAVED', 'FAST_PATH_HANDLED', " +
            "'CONTROL_ONLY', 'CENSORED_CANCELLED', 'SUPERSEDED_REGENERATE', " +
            "'FAILED_FINAL_SAVE', 'FAILED_OTHER') " +
            "AND ((:completionKind IN ('GENERATION_FINAL_SAVED', 'FAST_PATH_HANDLED') " +
            "AND :resultMessageId IS NOT NULL AND :resultMessageRevision > 0) OR " +
            "(:completionKind NOT IN ('GENERATION_FINAL_SAVED', 'FAST_PATH_HANDLED') " +
            "AND :resultMessageId IS NULL AND :resultMessageRevision IS NULL)) " +
            "AND ((:completionKind = 'FAILED_FINAL_SAVE' " +
            "AND :conversationSourceRevision IS NULL) OR " +
            "(:completionKind != 'FAILED_FINAL_SAVE' AND :conversationSourceRevision > 0))",
    )
    suspend fun finishClaimedWithCompletionFenced(
        id: String,
        conversationId: String,
        expectedVersion: Long,
        workerId: String,
        expectedLeaseUntil: Long,
        nextState: String,
        finishedAt: Long,
        now: Long,
        errorCode: String?,
        conversationSourceRevision: Long?,
        completionKind: String,
        resultMessageId: String?,
        resultMessageRevision: Long?,
    ): Int

    @Query(
        "UPDATE pending_chat_commands SET state = 'WAITING_APPROVAL', " +
            "claimedBy = NULL, leaseUntil = NULL, lastErrorCode = NULL, " +
            "lastErrorMessage = NULL, stateVersion = stateVersion + 1 " +
            "WHERE id = :id AND state = 'RUNNING' AND stateVersion = :expectedVersion " +
            "AND claimedBy = :workerId AND leaseUntil = :expectedLeaseUntil " +
            "AND leaseUntil >= :now"
    )
    suspend fun markWaitingApprovalFenced(
        id: String,
        expectedVersion: Long,
        workerId: String,
        expectedLeaseUntil: Long,
        now: Long,
    ): Int

    @Query(
        "UPDATE pending_chat_commands SET state = 'WAITING_APPROVAL', " +
            "claimedBy = NULL, leaseUntil = NULL, lastErrorCode = NULL, " +
            "lastErrorMessage = NULL, completionKind = :completionKind, " +
            "conversationSourceRevision = :conversationSourceRevision, " +
            "resultAssistantMessageId = :resultMessageId, " +
            "resultAssistantMessageRevision = :resultMessageRevision, " +
            "stateVersion = stateVersion + 1 " +
            "WHERE id = :id AND conversationId = :conversationId " +
            "AND state = 'RUNNING' AND stateVersion = :expectedVersion " +
            "AND claimedBy = :workerId AND leaseUntil = :expectedLeaseUntil " +
            "AND leaseUntil >= :now " +
            "AND :completionKind = 'GENERATION_WAITING_APPROVAL' " +
            "AND :conversationSourceRevision > 0 " +
            "AND :resultMessageId IS NOT NULL AND :resultMessageRevision > 0",
    )
    suspend fun markWaitingApprovalWithCompletionFenced(
        id: String,
        conversationId: String,
        expectedVersion: Long,
        workerId: String,
        expectedLeaseUntil: Long,
        now: Long,
        conversationSourceRevision: Long,
        completionKind: String,
        resultMessageId: String,
        resultMessageRevision: Long,
    ): Int

    @Query(
        "UPDATE pending_chat_commands SET state = :nextState, finishedAt = :finishedAt, " +
            "claimedBy = NULL, leaseUntil = NULL, lastErrorCode = :errorCode, " +
            "lastErrorMessage = :errorMessage, stateVersion = stateVersion + 1 " +
            "WHERE id = :id AND state = :expectedState AND stateVersion = :expectedVersion"
    )
    suspend fun finishUnclaimedFenced(
        id: String,
        expectedState: String,
        expectedVersion: Long,
        nextState: String,
        finishedAt: Long,
        errorCode: String?,
        errorMessage: String?,
    ): Int

    @Query(
        "UPDATE pending_chat_commands SET state = :nextState, finishedAt = :finishedAt, " +
            "claimedBy = NULL, leaseUntil = NULL, lastErrorCode = :errorCode, " +
            "lastErrorMessage = NULL, completionKind = :completionKind, " +
            "conversationSourceRevision = :conversationSourceRevision, " +
            "resultAssistantMessageId = :resultMessageId, " +
            "resultAssistantMessageRevision = :resultMessageRevision, " +
            "stateVersion = stateVersion + 1 " +
            "WHERE id = :id AND conversationId = :conversationId " +
            "AND state = :expectedState AND stateVersion = :expectedVersion " +
            "AND :nextState IN ('COMPLETED', 'FAILED', 'CANCELLED', 'MANUAL_CONFIRMATION') " +
            "AND :completionKind IN ('GENERATION_FINAL_SAVED', 'FAST_PATH_HANDLED', " +
            "'CONTROL_ONLY', 'CENSORED_CANCELLED', 'SUPERSEDED_REGENERATE', " +
            "'FAILED_FINAL_SAVE', 'FAILED_OTHER') " +
            "AND ((:completionKind IN ('GENERATION_FINAL_SAVED', 'FAST_PATH_HANDLED') " +
            "AND :resultMessageId IS NOT NULL AND :resultMessageRevision > 0) OR " +
            "(:completionKind NOT IN ('GENERATION_FINAL_SAVED', 'FAST_PATH_HANDLED') " +
            "AND :resultMessageId IS NULL AND :resultMessageRevision IS NULL)) " +
            "AND ((:completionKind = 'FAILED_FINAL_SAVE' " +
            "AND :conversationSourceRevision IS NULL) OR " +
            "(:completionKind != 'FAILED_FINAL_SAVE' AND :conversationSourceRevision > 0))",
    )
    suspend fun finishUnclaimedWithCompletionFenced(
        id: String,
        conversationId: String,
        expectedState: String,
        expectedVersion: Long,
        nextState: String,
        finishedAt: Long,
        errorCode: String?,
        conversationSourceRevision: Long?,
        completionKind: String,
        resultMessageId: String?,
        resultMessageRevision: Long?,
    ): Int

    @Query(
        "UPDATE pending_chat_commands SET state = 'INTERRUPTED', claimedBy = NULL, " +
            "leaseUntil = NULL, lastErrorCode = 'LEASE_EXPIRED', lastErrorMessage = :message, " +
            "stateVersion = stateVersion + 1 " +
            "WHERE id = :id AND state = 'RUNNING' AND stateVersion = :expectedVersion " +
            "AND leaseUntil = :expectedLeaseUntil AND leaseUntil < :now"
    )
    suspend fun interruptExpiredFenced(
        id: String,
        expectedVersion: Long,
        expectedLeaseUntil: Long,
        now: Long,
        message: String,
    ): Int

    @Query(
        "SELECT * FROM pending_chat_commands WHERE state = 'RUNNING' " +
            "AND leaseUntil IS NOT NULL AND leaseUntil < :now ORDER BY leaseUntil ASC LIMIT :limit"
    )
    suspend fun listExpiredRunning(now: Long, limit: Int): List<PendingChatCommandEntity>

    @Query(
        "SELECT * FROM pending_chat_commands WHERE state = 'PENDING' " +
            "AND expiresAt IS NOT NULL AND expiresAt <= :now " +
            "ORDER BY expiresAt ASC, sequence ASC, id ASC LIMIT :limit"
    )
    suspend fun listExpiredPending(now: Long, limit: Int): List<PendingChatCommandEntity>

    @Query(
        "SELECT * FROM pending_chat_commands WHERE conversationId = :conversationId " +
            "AND state IN ('PENDING', 'INTERRUPTED', 'WAITING_APPROVAL', 'RUNNING') " +
            "ORDER BY sequence ASC LIMIT :limit"
    )
    suspend fun listActiveForConversation(
        conversationId: String,
        limit: Int,
    ): List<PendingChatCommandEntity>

    @Query(
        "SELECT * FROM pending_chat_commands WHERE authoritySubjectId = :subjectId " +
            "AND state IN ('PENDING', 'INTERRUPTED', 'WAITING_APPROVAL', 'RUNNING') " +
            "ORDER BY sequence ASC LIMIT :limit"
    )
    suspend fun listActiveForAuthoritySubject(
        subjectId: String,
        limit: Int,
    ): List<PendingChatCommandEntity>

    @Query(
        "SELECT * FROM pending_chat_commands WHERE conversationId = :conversationId " +
            "AND authoritySubjectId IS NULL " +
            "AND state IN ('PENDING', 'INTERRUPTED', 'WAITING_APPROVAL', 'RUNNING') " +
            "ORDER BY sequence ASC LIMIT :limit"
    )
    suspend fun listLegacyUnscopedActiveForConversation(
        conversationId: String,
        limit: Int,
    ): List<PendingChatCommandEntity>
}
