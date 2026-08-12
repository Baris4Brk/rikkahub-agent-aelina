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
import me.rerere.rikkahub.data.db.entity.MemoryLinkRevisionEntity
import me.rerere.rikkahub.data.db.entity.MemoryRelationCandidateEntity
import me.rerere.rikkahub.data.db.entity.MemorySourceTombstoneEntity

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
        "SELECT * FROM memory_captures WHERE scope_id = :scopeId " +
            "AND conversation_id = :conversationId " +
            "AND assistant_message_id = :assistantMessageId " +
            "AND capture_source = :captureSource LIMIT 1",
    )
    suspend fun findCaptureByTurn(
        scopeId: String,
        conversationId: String,
        assistantMessageId: String,
        captureSource: String,
    ): MemoryCaptureEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceTombstones(
        tombstones: List<MemorySourceTombstoneEntity>,
    ): List<Long>

    @Query(
        "SELECT * FROM memory_source_tombstones WHERE scope_id = :scopeId " +
            "AND conversation_id = :conversationId",
    )
    suspend fun getSourceTombstones(
        scopeId: String,
        conversationId: String,
    ): List<MemorySourceTombstoneEntity>

    @Query(
        "SELECT * FROM memory_captures WHERE scope_id = :scopeId " +
            "AND conversation_id = :conversationId",
    )
    suspend fun getCapturesForSourceConversation(
        scopeId: String,
        conversationId: String,
    ): List<MemoryCaptureEntity>

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
            "WHERE state = 'PROCESSING' AND lease_until_ms IS NOT NULL AND lease_until_ms <= :nowMs",
    )
    suspend fun recoverExpiredLeases(nowMs: Long): Int

    @Query(
        "UPDATE memory_captures SET state = 'PENDING', lease_owner = NULL, lease_until_ms = NULL, " +
            "retry_count = CASE WHEN retry_count > 0 THEN retry_count - 1 ELSE 0 END, " +
            "last_error_code = 'WORK_CANCELLED', last_error_message = NULL, updated_at_ms = :nowMs " +
            "WHERE id IN (:ids) AND scope_id = :scopeId AND state = 'PROCESSING' " +
            "AND lease_owner = :workerId AND lease_until_ms IS NOT NULL " +
            "AND lease_until_ms > :nowMs",
    )
    suspend fun releaseClaimedCaptures(
        ids: List<String>,
        scopeId: String,
        workerId: String,
        nowMs: Long,
    ): Int

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
            "WHERE id = :id AND scope_id = :scopeId " +
            "AND state IN ('PENDING', 'FAILED', 'PAUSED') " +
            "AND retry_count < 3",
    )
    suspend fun claimCapture(
        id: String,
        scopeId: String,
        workerId: String,
        leaseUntilMs: Long,
        nowMs: Long,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM memory_captures WHERE id IN (:ids) AND scope_id = :scopeId " +
            "AND assistant_id = :assistantId AND conversation_id = :conversationId " +
            "AND state = 'PROCESSING' AND lease_owner = :workerId " +
            "AND lease_until_ms IS NOT NULL AND lease_until_ms > :nowMs",
    )
    suspend fun countOwnedProcessingCaptures(
        ids: List<String>,
        scopeId: String,
        assistantId: String,
        conversationId: String,
        workerId: String,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE memory_captures SET state = 'PROCESSED', lease_owner = NULL, " +
            "lease_until_ms = NULL, processed_at_ms = :nowMs, updated_at_ms = :nowMs, " +
            "last_error_code = NULL, last_error_message = NULL, " +
            "processing_outcome = :processingOutcome, candidate_count = :candidateCount " +
            "WHERE id IN (:ids) AND scope_id = :scopeId AND assistant_id = :assistantId " +
            "AND conversation_id = :conversationId AND state = 'PROCESSING' " +
            "AND lease_owner = :workerId AND lease_until_ms IS NOT NULL " +
            "AND lease_until_ms > :nowMs",
    )
    suspend fun markCapturesProcessed(
        ids: List<String>,
        scopeId: String,
        assistantId: String,
        conversationId: String,
        workerId: String,
        nowMs: Long,
        processingOutcome: String,
        candidateCount: Int,
    ): Int

    @Query(
        "UPDATE memory_captures SET state = :state, lease_owner = NULL, lease_until_ms = NULL, " +
            "retry_count = CASE WHEN :requiresManualRetry THEN 3 ELSE retry_count END, " +
            "updated_at_ms = :nowMs, last_error_code = :code, last_error_message = :message " +
            "WHERE id IN (:ids) AND scope_id = :scopeId AND state = 'PROCESSING' " +
            "AND lease_owner = :workerId AND lease_until_ms IS NOT NULL " +
            "AND lease_until_ms > :nowMs",
    )
    suspend fun markCapturesFailed(
        ids: List<String>,
        scopeId: String,
        workerId: String,
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

    @Query(
        "SELECT * FROM memory_candidates WHERE id = :candidateId " +
            "AND scope_id = :scopeId LIMIT 1",
    )
    suspend fun findCandidate(candidateId: String, scopeId: String): MemoryCandidateEntity?

    @Query(
        "SELECT * FROM memory_candidates WHERE id = :candidateId AND batch_id = :batchId " +
            "AND scope_id = :scopeId LIMIT 1",
    )
    suspend fun findCandidateInBatch(
        candidateId: String,
        batchId: String,
        scopeId: String,
    ): MemoryCandidateEntity?

    @Query(
        "UPDATE memory_candidates SET status = :status, applied_memory_id = :appliedMemoryId, " +
            "resolution_error = :resolutionError, updated_at_ms = :nowMs " +
            "WHERE id = :candidateId AND scope_id = :scopeId AND status IN ('PENDING_REVIEW', 'CONFLICT')",
    )
    suspend fun resolveCandidate(
        candidateId: String,
        scopeId: String,
        status: String,
        appliedMemoryId: Int?,
        resolutionError: String?,
        nowMs: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(revision: MemoryRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvidence(evidence: List<MemoryEvidenceEntity>): List<Long>

    @Query(
        "SELECT e.* FROM memory_evidence e INNER JOIN memoryentity m ON m.id = e.memory_id " +
            "WHERE e.memory_id = :memoryId AND m.assistant_id = :scopeId " +
            "ORDER BY e.captured_at_ms ASC LIMIT 3",
    )
    fun observeEvidence(memoryId: Int, scopeId: String): Flow<List<MemoryEvidenceEntity>>

    @Query(
        "UPDATE memory_evidence SET memory_id = :memoryId " +
            "WHERE candidate_id = :candidateId AND memory_id IS NULL",
    )
    suspend fun attachCandidateEvidenceToMemory(candidateId: String, memoryId: Int): Int

    @Query(
        "UPDATE memory_evidence SET link_id = :linkId " +
            "WHERE relation_candidate_id = :relationCandidateId AND link_id IS NULL",
    )
    suspend fun attachRelationEvidenceToLink(relationCandidateId: String, linkId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLinks(links: List<MemoryLinkEntity>): List<Long>

    @Query(
        "SELECT * FROM memory_links WHERE scope_id = :scopeId AND " +
            "(source_memory_id = :memoryId OR target_memory_id = :memoryId) " +
            "ORDER BY weight DESC",
    )
    fun observeLinks(memoryId: Int, scopeId: String): Flow<List<MemoryLinkEntity>>

    @Query(
        "SELECT * FROM memory_links WHERE id = :linkId AND scope_id = :scopeId LIMIT 1",
    )
    suspend fun findLink(linkId: String, scopeId: String): MemoryLinkEntity?

    @Query(
        "SELECT * FROM memory_links WHERE scope_id = :scopeId AND source_memory_id = :sourceMemoryId " +
            "AND target_memory_id = :targetMemoryId AND relation_type = :relationType LIMIT 1",
    )
    suspend fun findLinkByEndpoints(
        scopeId: String,
        sourceMemoryId: Int,
        targetMemoryId: Int,
        relationType: String,
    ): MemoryLinkEntity?

    @Query(
        "SELECT * FROM memory_links WHERE scope_id = :scopeId AND lifecycle_status = :lifecycle " +
            "AND (source_memory_id = :memoryId OR target_memory_id = :memoryId)",
    )
    suspend fun getIncidentLinks(
        memoryId: Int,
        scopeId: String,
        lifecycle: String,
    ): List<MemoryLinkEntity>

    @Query(
        "SELECT * FROM memory_links WHERE scope_id = :scopeId AND target_memory_id = :targetMemoryId " +
            "AND relation_type = 'DERIVED_FROM' AND lifecycle_status = 'ACTIVE'",
    )
    suspend fun getActiveDerivedLinksForTarget(
        targetMemoryId: Int,
        scopeId: String,
    ): List<MemoryLinkEntity>

    @Query(
        "SELECT * FROM memory_links WHERE scope_id = :scopeId AND source_memory_id = :sourceMemoryId " +
            "AND relation_type = 'DERIVED_FROM' AND lifecycle_status = 'ACTIVE'",
    )
    suspend fun getActiveDerivedLinksForSource(
        sourceMemoryId: Int,
        scopeId: String,
    ): List<MemoryLinkEntity>

    @Query(
        "UPDATE memory_links SET lifecycle_status = :lifecycle, revision = revision + 1, " +
            "created_by_assistant_id = :createdByAssistantId, " +
            "evidence_message_ids_json = :evidenceMessageIdsJson, " +
            "relation_candidate_id = :relationCandidateId, " +
            "updated_at_ms = :nowMs, invalidated_at_ms = :invalidatedAtMs, " +
            "invalidation_reason = :reason WHERE id = :linkId AND scope_id = :scopeId " +
            "AND revision = :expectedRevision",
    )
    suspend fun updateLinkLifecycle(
        linkId: String,
        scopeId: String,
        expectedRevision: Int,
        lifecycle: String,
        createdByAssistantId: String,
        evidenceMessageIdsJson: String,
        relationCandidateId: String?,
        nowMs: Long,
        invalidatedAtMs: Long?,
        reason: String?,
    ): Int

    @Query(
        "UPDATE memory_links SET created_by_assistant_id = :createdByAssistantId, " +
            "evidence_message_ids_json = :evidenceMessageIdsJson, " +
            "relation_candidate_id = :relationCandidateId, " +
            "revision = revision + 1, updated_at_ms = :nowMs " +
            "WHERE id = :linkId AND scope_id = :scopeId AND revision = :expectedRevision",
    )
    suspend fun scrubLinkProvenance(
        linkId: String,
        scopeId: String,
        expectedRevision: Int,
        createdByAssistantId: String,
        evidenceMessageIdsJson: String,
        relationCandidateId: String?,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE memory_links SET lifecycle_status = 'ACTIVE', revision = revision + 1, " +
            "source_revision = :sourceRevision, target_revision = :targetRevision, " +
            "source_semantic_hash = :sourceSemanticHash, " +
            "target_semantic_hash = :targetSemanticHash, relation_candidate_id = :candidateId, " +
            "updated_at_ms = :nowMs, invalidated_at_ms = NULL, invalidation_reason = NULL " +
            "WHERE id = :linkId AND scope_id = :scopeId AND revision = :expectedRevision " +
            "AND lifecycle_status = 'INVALIDATED'",
    )
    suspend fun reactivateInvalidatedLinkAfterReview(
        linkId: String,
        scopeId: String,
        expectedRevision: Int,
        sourceRevision: Int,
        targetRevision: Int,
        sourceSemanticHash: String,
        targetSemanticHash: String,
        candidateId: String,
        nowMs: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLinkRevision(revision: MemoryLinkRevisionEntity)

    @Query(
        "UPDATE memory_link_revisions SET before_snapshot_json = NULL, " +
            "after_snapshot_json = NULL, relation_candidate_id = NULL, " +
            "reason_code = :reasonCode WHERE link_id = :linkId",
    )
    suspend fun tombstoneLinkRevisionPayloads(linkId: String, reasonCode: String): Int

    @Query(
        "DELETE FROM memory_link_revisions WHERE link_id = :linkId AND id NOT IN " +
            "(SELECT id FROM memory_link_revisions WHERE link_id = :linkId " +
            "ORDER BY revision DESC LIMIT :keep)",
    )
    suspend fun trimLinkRevisions(linkId: String, keep: Int = 20): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRelationCandidate(candidate: MemoryRelationCandidateEntity)

    @Query(
        "SELECT * FROM memory_relation_candidates WHERE id = :candidateId " +
            "AND scope_id = :scopeId LIMIT 1",
    )
    suspend fun findRelationCandidate(
        candidateId: String,
        scopeId: String,
    ): MemoryRelationCandidateEntity?

    @Query(
        "UPDATE memory_relation_candidates SET status = :status, resolved_link_id = :resolvedLinkId, " +
            "resolution_error = :resolutionError, updated_at_ms = :nowMs " +
            "WHERE id = :candidateId AND scope_id = :scopeId AND status = 'PENDING'",
    )
    suspend fun resolveRelationCandidate(
        candidateId: String,
        scopeId: String,
        status: String,
        resolvedLinkId: String?,
        resolutionError: String?,
        nowMs: Long,
    ): Int

    @Query(
        "SELECT * FROM memory_relation_candidates WHERE scope_id = :scopeId " +
            "AND status = 'PENDING' ORDER BY created_at_ms DESC",
    )
    fun observePendingRelationCandidates(scopeId: String): Flow<List<MemoryRelationCandidateEntity>>

    @Query(
        "UPDATE memory_relation_candidates SET status = 'INVALIDATED', " +
            "resolution_error = :reason, updated_at_ms = :nowMs WHERE scope_id = :scopeId " +
            "AND status = 'PENDING' AND (source_candidate_id = :memoryCandidateId " +
            "OR target_candidate_id = :memoryCandidateId)",
    )
    suspend fun invalidateRelationsForMemoryCandidate(
        memoryCandidateId: String,
        scopeId: String,
        reason: String,
        nowMs: Long,
    ): Int

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
        "SELECT r.* FROM memory_revisions r INNER JOIN memoryentity m ON m.id = r.memory_id " +
            "WHERE r.memory_id = :memoryId AND m.assistant_id = :scopeId " +
            "ORDER BY r.revision DESC LIMIT :limit",
    )
    fun observeRevisions(
        memoryId: Int,
        scopeId: String,
        limit: Int = 20,
    ): Flow<List<MemoryRevisionEntity>>

    @Query(
        "SELECT r.* FROM memory_revisions r INNER JOIN memoryentity m ON m.id = r.memory_id " +
            "WHERE r.memory_id = :memoryId AND r.revision = :revision " +
            "AND m.assistant_id = :scopeId LIMIT 1",
    )
    suspend fun findRevision(
        memoryId: Int,
        revision: Int,
        scopeId: String,
    ): MemoryRevisionEntity?

    @Query(
        "DELETE FROM memory_revisions WHERE memory_id = :memoryId AND id NOT IN " +
            "(SELECT id FROM memory_revisions WHERE memory_id = :memoryId " +
            "ORDER BY revision DESC LIMIT :keep) " +
            "AND NOT EXISTS (SELECT 1 FROM dream_claim_version_sources s " +
            "WHERE s.memory_id = memory_revisions.memory_id " +
            "AND s.memory_revision = memory_revisions.revision)",
    )
    suspend fun trimRevisions(memoryId: Int, keep: Int = 20): Int

    @Query(
        "UPDATE memory_captures SET user_text = '', assistant_text = '', " +
            "source_identities_json = '[]', " +
            "last_error_message = NULL, payload_purged_at_ms = :nowMs, updated_at_ms = :nowMs " +
            "WHERE payload_purged_at_ms IS NULL AND state IN ('PROCESSED', 'DISCARDED') " +
            "AND COALESCE(processed_at_ms, updated_at_ms) <= :processedBeforeMs",
    )
    suspend fun purgeProcessedCapturePayloads(processedBeforeMs: Long, nowMs: Long): Int

    @Query(
        "UPDATE memory_captures SET state = 'DISCARDED', user_text = '', assistant_text = '', " +
            "source_identities_json = '[]', " +
            "last_error_message = NULL, payload_purged_at_ms = :nowMs, updated_at_ms = :nowMs, " +
            "lease_owner = NULL, lease_until_ms = NULL WHERE payload_purged_at_ms IS NULL " +
            "AND state IN ('FAILED', 'PAUSED') AND updated_at_ms <= :failedBeforeMs",
    )
    suspend fun discardAndPurgeFailedCapturePayloads(failedBeforeMs: Long, nowMs: Long): Int

    @Query(
        "UPDATE memory_captures SET state = CASE WHEN state IN " +
            "('PENDING', 'PROCESSING', 'FAILED', 'PAUSED') THEN 'DISCARDED' ELSE state END, " +
            "user_text = '', assistant_text = '', source_identities_json = '[]', " +
            "last_error_message = NULL, lease_owner = NULL, lease_until_ms = NULL, " +
            "payload_purged_at_ms = COALESCE(payload_purged_at_ms, :nowMs), updated_at_ms = :nowMs " +
            "WHERE scope_id = :scopeId AND conversation_id = :conversationId",
    )
    suspend fun purgeCapturePayloadsForConversation(
        scopeId: String,
        conversationId: String,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE memory_captures SET state = CASE WHEN state IN " +
            "('PENDING', 'PROCESSING', 'FAILED', 'PAUSED') THEN 'DISCARDED' ELSE state END, " +
            "user_text = '', assistant_text = '', source_identities_json = '[]', " +
            "last_error_message = NULL, lease_owner = NULL, lease_until_ms = NULL, " +
            "payload_purged_at_ms = COALESCE(payload_purged_at_ms, :nowMs), " +
            "updated_at_ms = :nowMs WHERE scope_id = :scopeId AND id IN (:captureIds)",
    )
    suspend fun discardAndPurgeCapturePayloadsByIds(
        scopeId: String,
        captureIds: List<String>,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE memory_evidence SET excerpt = '', content_hash = '', quality = 'SOURCE_DELETED' " +
            "WHERE conversation_id = :conversationId AND quality != 'SOURCE_DELETED' AND (" +
            "memory_id IN (SELECT id FROM memoryentity WHERE assistant_id = :scopeId) OR " +
            "candidate_id IN (SELECT id FROM memory_candidates WHERE scope_id = :scopeId) OR " +
            "relation_candidate_id IN (SELECT id FROM memory_relation_candidates " +
            "WHERE scope_id = :scopeId) OR " +
            "link_id IN (SELECT id FROM memory_links WHERE scope_id = :scopeId))",
    )
    suspend fun invalidateEvidenceForConversation(
        scopeId: String,
        conversationId: String,
    ): Int

    @Query(
        "UPDATE memory_evidence SET excerpt = '', content_hash = '', quality = 'SOURCE_DELETED' " +
            "WHERE conversation_id = :conversationId AND message_id IN (:messageIds) " +
            "AND quality != 'SOURCE_DELETED' AND (" +
            "memory_id IN (SELECT id FROM memoryentity WHERE assistant_id = :scopeId) OR " +
            "candidate_id IN (SELECT id FROM memory_candidates WHERE scope_id = :scopeId) OR " +
            "relation_candidate_id IN (SELECT id FROM memory_relation_candidates " +
            "WHERE scope_id = :scopeId) OR " +
            "link_id IN (SELECT id FROM memory_links WHERE scope_id = :scopeId))",
    )
    suspend fun invalidateEvidenceForMessages(
        scopeId: String,
        conversationId: String,
        messageIds: List<String>,
    ): Int

    @Query(
        "UPDATE memory_evidence SET excerpt = '', content_hash = '', quality = 'SOURCE_DELETED' " +
            "WHERE conversation_id = :conversationId AND message_id = :messageId " +
            "AND source_digest = :sourceDigest AND quality != 'SOURCE_DELETED' AND (" +
            "memory_id IN (SELECT id FROM memoryentity WHERE assistant_id = :scopeId) OR " +
            "candidate_id IN (SELECT id FROM memory_candidates WHERE scope_id = :scopeId) OR " +
            "relation_candidate_id IN (SELECT id FROM memory_relation_candidates " +
            "WHERE scope_id = :scopeId) OR " +
            "link_id IN (SELECT id FROM memory_links WHERE scope_id = :scopeId))",
    )
    suspend fun invalidateEvidenceForSourceVersion(
        scopeId: String,
        conversationId: String,
        messageId: String,
        sourceDigest: String,
    ): Int

    @Query(
        "SELECT DISTINCT l.* FROM memory_links l " +
            "INNER JOIN memory_evidence e ON e.link_id = l.id " +
            "WHERE l.scope_id = :scopeId AND l.lifecycle_status = 'ACTIVE' " +
            "AND e.quality != 'SOURCE_DELETED' AND e.conversation_id = :conversationId",
    )
    suspend fun getActiveLinksWithEvidenceForConversation(
        scopeId: String,
        conversationId: String,
    ): List<MemoryLinkEntity>

    @Query(
        "SELECT DISTINCT l.* FROM memory_links l " +
            "INNER JOIN memory_evidence e ON e.link_id = l.id " +
            "WHERE l.scope_id = :scopeId AND l.lifecycle_status = 'ACTIVE' " +
            "AND e.quality != 'SOURCE_DELETED' AND e.conversation_id = :conversationId " +
            "AND e.message_id IN (:messageIds)",
    )
    suspend fun getActiveLinksWithEvidenceForMessages(
        scopeId: String,
        conversationId: String,
        messageIds: List<String>,
    ): List<MemoryLinkEntity>

    @Query(
        "SELECT DISTINCT l.* FROM memory_links l " +
            "INNER JOIN memory_evidence e ON e.link_id = l.id " +
            "WHERE l.scope_id = :scopeId AND l.lifecycle_status = 'ACTIVE' " +
            "AND e.quality != 'SOURCE_DELETED' AND e.conversation_id = :conversationId " +
            "AND e.message_id = :messageId AND e.source_digest = :sourceDigest",
    )
    suspend fun getActiveLinksWithEvidenceForSourceVersion(
        scopeId: String,
        conversationId: String,
        messageId: String,
        sourceDigest: String,
    ): List<MemoryLinkEntity>

    @Query(
        "SELECT COUNT(*) FROM (SELECT evidence_group_id FROM memory_evidence " +
            "WHERE memory_id = :memoryId GROUP BY evidence_group_id " +
            "HAVING SUM(CASE WHEN quality = 'SOURCE_DELETED' THEN 1 ELSE 0 END) = 0)",
    )
    suspend fun countValidEvidence(memoryId: Int): Int

    @Query(
        "SELECT COUNT(*) FROM (SELECT evidence_group_id FROM memory_evidence " +
            "WHERE link_id = :linkId GROUP BY evidence_group_id " +
            "HAVING SUM(CASE WHEN quality = 'SOURCE_DELETED' THEN 1 ELSE 0 END) = 0)",
    )
    suspend fun countValidEvidenceForLink(linkId: String): Int

    @Query(
        "SELECT e.* FROM memory_evidence e WHERE e.candidate_id = :candidateId " +
            "AND e.quality != 'SOURCE_DELETED' AND NOT EXISTS (" +
            "SELECT 1 FROM memory_evidence deleted WHERE " +
            "deleted.candidate_id = e.candidate_id AND " +
            "deleted.evidence_group_id = e.evidence_group_id AND " +
            "deleted.quality = 'SOURCE_DELETED') " +
            "ORDER BY e.captured_at_ms ASC, e.id ASC",
    )
    suspend fun getValidEvidenceForCandidate(candidateId: String): List<MemoryEvidenceEntity>

    @Query(
        "SELECT e.* FROM memory_evidence e WHERE e.relation_candidate_id = :candidateId " +
            "AND e.link_id IS NULL AND e.quality != 'SOURCE_DELETED' AND NOT EXISTS (" +
            "SELECT 1 FROM memory_evidence deleted WHERE " +
            "deleted.relation_candidate_id = e.relation_candidate_id AND " +
            "deleted.evidence_group_id = e.evidence_group_id AND " +
            "deleted.quality = 'SOURCE_DELETED') " +
            "ORDER BY e.captured_at_ms ASC, e.id ASC",
    )
    suspend fun getUnattachedValidEvidenceForRelationCandidate(
        candidateId: String,
    ): List<MemoryEvidenceEntity>

    @Query(
        "SELECT e.* FROM memory_evidence e WHERE e.conversation_id = :conversationId " +
            "AND e.quality != 'SOURCE_DELETED' AND (" +
            "e.memory_id IN (SELECT id FROM memoryentity WHERE assistant_id = :scopeId) OR " +
            "e.link_id IN (SELECT id FROM memory_links WHERE scope_id = :scopeId)) " +
            "ORDER BY e.captured_at_ms ASC, e.id ASC",
    )
    suspend fun getLiveFormalEvidenceForConversation(
        scopeId: String,
        conversationId: String,
    ): List<MemoryEvidenceEntity>

    @Query(
        "SELECT r.* FROM memory_revisions r INNER JOIN memoryentity m ON m.id = r.memory_id " +
            "WHERE r.memory_id = :memoryId AND m.assistant_id = :scopeId " +
            "ORDER BY r.revision ASC",
    )
    suspend fun getRevisionsForMemory(
        memoryId: Int,
        scopeId: String,
    ): List<MemoryRevisionEntity>

    @Query(
        "UPDATE memory_revisions SET before_snapshot_json = NULL, after_snapshot_json = NULL, " +
        "source_conversation_id = NULL, source_message_ids_json = '[]', " +
            "source_identities_json = '[]', " +
            "reason_code = :reasonCode WHERE id = :revisionId AND memory_id = :memoryId",
    )
    suspend fun tombstoneRevisionPayload(
        revisionId: String,
        memoryId: Int,
        reasonCode: String,
    ): Int

    @Query(
        "UPDATE memory_relation_candidates SET status = 'INVALIDATED', " +
            "resolution_error = 'SOURCE_MESSAGE_DELETED', updated_at_ms = :nowMs " +
            "WHERE scope_id = :scopeId AND status = 'PENDING' AND id IN (" +
            "SELECT relation_candidate_id FROM memory_evidence WHERE conversation_id = :conversationId " +
            "AND message_id IN (:messageIds) AND relation_candidate_id IS NOT NULL)",
    )
    suspend fun invalidateRelationCandidatesForMessages(
        scopeId: String,
        conversationId: String,
        messageIds: List<String>,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE memory_relation_candidates SET status = 'INVALIDATED', " +
            "resolution_error = 'SOURCE_VERSION_DELETED', updated_at_ms = :nowMs " +
            "WHERE scope_id = :scopeId AND status = 'PENDING' AND id IN (" +
            "SELECT relation_candidate_id FROM memory_evidence WHERE conversation_id = :conversationId " +
            "AND message_id = :messageId AND source_digest = :sourceDigest " +
            "AND relation_candidate_id IS NOT NULL)",
    )
    suspend fun invalidateRelationCandidatesForSourceVersion(
        scopeId: String,
        conversationId: String,
        messageId: String,
        sourceDigest: String,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE memory_candidates SET status = 'SUPERSEDED', resolution_error = 'SOURCE_DELETED', " +
            "updated_at_ms = :nowMs WHERE scope_id = :scopeId AND source_conversation_id = :conversationId " +
            "AND status IN ('PENDING_REVIEW', 'CONFLICT')",
    )
    suspend fun invalidateCandidatesForConversation(
        scopeId: String,
        conversationId: String,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE memory_candidates SET status = 'SUPERSEDED', " +
            "resolution_error = 'SOURCE_MESSAGE_DELETED', updated_at_ms = :nowMs " +
            "WHERE scope_id = :scopeId AND status IN ('PENDING_REVIEW', 'CONFLICT') AND id IN (" +
            "SELECT candidate_id FROM memory_evidence WHERE conversation_id = :conversationId " +
            "AND message_id IN (:messageIds) AND candidate_id IS NOT NULL)",
    )
    suspend fun invalidateCandidatesForMessages(
        scopeId: String,
        conversationId: String,
        messageIds: List<String>,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE memory_candidates SET status = 'SUPERSEDED', " +
            "resolution_error = 'SOURCE_VERSION_DELETED', updated_at_ms = :nowMs " +
            "WHERE scope_id = :scopeId AND status IN ('PENDING_REVIEW', 'CONFLICT') AND id IN (" +
            "SELECT candidate_id FROM memory_evidence WHERE conversation_id = :conversationId " +
            "AND message_id = :messageId AND source_digest = :sourceDigest " +
            "AND candidate_id IS NOT NULL)",
    )
    suspend fun invalidateCandidatesForSourceVersion(
        scopeId: String,
        conversationId: String,
        messageId: String,
        sourceDigest: String,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE memory_relation_candidates SET status = 'INVALIDATED', " +
            "resolution_error = 'SOURCE_DELETED', updated_at_ms = :nowMs " +
            "WHERE scope_id = :scopeId AND status = 'PENDING' AND (" +
            "source_candidate_id IN (SELECT id FROM memory_candidates " +
            "WHERE scope_id = :scopeId AND source_conversation_id = :conversationId) OR " +
            "target_candidate_id IN (SELECT id FROM memory_candidates " +
            "WHERE scope_id = :scopeId AND source_conversation_id = :conversationId))",
    )
    suspend fun invalidateRelationCandidatesForConversation(
        scopeId: String,
        conversationId: String,
        nowMs: Long,
    ): Int

    @Query(
        "SELECT CASE WHEN EXISTS (SELECT 1 FROM memoryentity WHERE assistant_id = :scopeId) " +
            "OR EXISTS (SELECT 1 FROM memory_links WHERE scope_id = :scopeId) " +
            "OR EXISTS (SELECT 1 FROM memory_source_tombstones WHERE scope_id = :scopeId) " +
            "THEN 1 ELSE 0 END",
    )
    suspend fun hasFormalAuthorityForScope(scopeId: String): Boolean

    @Query(
        "DELETE FROM memory_link_revisions WHERE link_id IN " +
            "(SELECT id FROM memory_links WHERE scope_id = :scopeId)",
    )
    suspend fun deleteLinkRevisionsForScope(scopeId: String): Int

    @Query(
        "DELETE FROM memory_evidence WHERE " +
            "memory_id IN (SELECT id FROM memoryentity WHERE assistant_id = :scopeId) OR " +
            "candidate_id IN (SELECT id FROM memory_candidates WHERE scope_id = :scopeId) OR " +
            "relation_candidate_id IN (SELECT id FROM memory_relation_candidates WHERE scope_id = :scopeId) OR " +
            "link_id IN (SELECT id FROM memory_links WHERE scope_id = :scopeId)",
    )
    suspend fun deleteEvidenceForScope(scopeId: String): Int

    @Query(
        "DELETE FROM memory_revisions WHERE memory_id IN " +
            "(SELECT id FROM memoryentity WHERE assistant_id = :scopeId)",
    )
    suspend fun deleteMemoryRevisionsForScope(scopeId: String): Int

    @Query("DELETE FROM memory_links WHERE scope_id = :scopeId")
    suspend fun deleteLinksForScope(scopeId: String): Int

    @Query("DELETE FROM memory_relation_candidates WHERE scope_id = :scopeId")
    suspend fun deleteRelationCandidatesForScope(scopeId: String): Int

    @Query("DELETE FROM memory_candidates WHERE scope_id = :scopeId")
    suspend fun deleteCandidatesForScope(scopeId: String): Int

    @Query("DELETE FROM memory_captures WHERE scope_id = :scopeId")
    suspend fun deleteCapturesForScope(scopeId: String): Int

    @Query("DELETE FROM memory_source_tombstones WHERE scope_id = :scopeId")
    suspend fun deleteSourceTombstonesForScope(scopeId: String): Int

    @Query("DELETE FROM memory_backfill_runs WHERE scope_id = :scopeId")
    suspend fun deleteBackfillRunsForScope(scopeId: String): Int

    @Query(
        "UPDATE memory_captures SET state = 'DISCARDED', user_text = '', assistant_text = '', " +
            "source_identities_json = '[]', " +
            "last_error_message = NULL, payload_purged_at_ms = COALESCE(payload_purged_at_ms, :nowMs), " +
            "lease_owner = NULL, lease_until_ms = NULL, updated_at_ms = :nowMs " +
            "WHERE scope_id = :globalScopeId AND assistant_id = :assistantId",
    )
    suspend fun scrubGlobalCapturesForAssistant(
        globalScopeId: String,
        assistantId: String,
        nowMs: Long,
    ): Int

    @Query(
        "SELECT e.* FROM memory_evidence e WHERE " +
            "e.candidate_id IN (SELECT id FROM memory_candidates " +
            "WHERE scope_id = :globalScopeId AND assistant_id = :assistantId) OR " +
            "e.relation_candidate_id IN (SELECT id FROM memory_relation_candidates " +
            "WHERE scope_id = :globalScopeId AND created_by_assistant_id = :assistantId) " +
            "ORDER BY e.captured_at_ms ASC, e.id ASC",
    )
    suspend fun getGlobalCandidateEvidenceForAssistant(
        globalScopeId: String,
        assistantId: String,
    ): List<MemoryEvidenceEntity>

    @Query(
        "DELETE FROM memory_evidence WHERE candidate_id IN (SELECT id FROM memory_candidates " +
            "WHERE scope_id = :globalScopeId AND assistant_id = :assistantId) OR " +
            "relation_candidate_id IN (SELECT id FROM memory_relation_candidates " +
            "WHERE scope_id = :globalScopeId AND created_by_assistant_id = :assistantId)",
    )
    suspend fun deleteGlobalCandidateEvidenceForAssistant(
        globalScopeId: String,
        assistantId: String,
    ): Int

    @Query(
        "DELETE FROM memory_relation_candidates WHERE scope_id = :globalScopeId " +
            "AND created_by_assistant_id = :assistantId",
    )
    suspend fun deleteGlobalRelationCandidatesForAssistant(
        globalScopeId: String,
        assistantId: String,
    ): Int

    @Query(
        "DELETE FROM memory_candidates WHERE scope_id = :globalScopeId AND assistant_id = :assistantId",
    )
    suspend fun deleteGlobalCandidatesForAssistant(
        globalScopeId: String,
        assistantId: String,
    ): Int

    @Query(
        "SELECT * FROM memory_links WHERE scope_id = :globalScopeId " +
            "AND created_by_assistant_id = :assistantId AND lifecycle_status = 'ACTIVE'",
    )
    suspend fun getActiveGlobalLinksCreatedByAssistant(
        globalScopeId: String,
        assistantId: String,
    ): List<MemoryLinkEntity>

    @Query(
        "SELECT * FROM memory_links WHERE scope_id = :globalScopeId " +
            "AND created_by_assistant_id = :assistantId ORDER BY id ASC",
    )
    suspend fun getGlobalLinksCreatedByAssistant(
        globalScopeId: String,
        assistantId: String,
    ): List<MemoryLinkEntity>
}
