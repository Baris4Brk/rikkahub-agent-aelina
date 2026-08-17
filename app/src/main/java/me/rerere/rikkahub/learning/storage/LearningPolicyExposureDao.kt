package me.rerere.rikkahub.learning.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Storage-only primitives for the P2 exposure state machine.
 *
 * Cross-table Episode consistency and transition validation belong to the later Room transaction
 * owner. This DAO provides ABORT inserts and one full-snapshot expected-version CAS; it never uses
 * REPLACE and never manufactures an orphan Episode.
 */
@Dao
interface LearningPolicyExposureDao {
    /** P2 actual-pipeline aggregate; Stage-D shadow recall lives in its separate DAO. */
    @Query(
        "SELECT COUNT(*) AS actual_retrieved_count, " +
            "COALESCE(SUM(CASE WHEN i.injected_at_ms IS NOT NULL THEN 1 ELSE 0 END), 0) " +
            "AS injected_hit_count, " +
            "COALESCE(SUM(CASE WHEN x.host_dispatched_at_ms IS NOT NULL THEN 1 ELSE 0 END), 0) " +
            "AS host_dispatched_hit_count, " +
            "COALESCE(SUM(CASE WHEN i.drop_reason IS NOT NULL THEN 1 ELSE 0 END), 0) " +
            "AS dropped_item_count, " +
            "COALESCE(SUM(i.estimated_tokens), 0) AS estimated_token_cost " +
            "FROM learning_policy_exposure_items i " +
            "JOIN learning_policy_exposures x ON x.id = i.exposure_id " +
            "WHERE i.policy_id = :policyId",
    )
    suspend fun aggregateForPolicyReview(policyId: String): PolicyReviewExposureAggregate

    /** Bounded content-free reason codes rendered by the review UI and redacted export. */
    @Query(
        "SELECT DISTINCT i.drop_reason FROM learning_policy_exposure_items i " +
            "WHERE i.policy_id = :policyId AND i.drop_reason IS NOT NULL " +
            "ORDER BY i.drop_reason ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 16 THEN :limit ELSE 0 END",
    )
    suspend fun listDropReasonsForPolicy(policyId: String, limit: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExposure(entity: LearningPolicyExposureEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItem(entity: LearningPolicyExposureItemEntity): Long

    @Query("SELECT * FROM learning_policy_exposures WHERE id = :id LIMIT 1")
    suspend fun findExposure(id: String): LearningPolicyExposureEntity?

    @Query(
        "SELECT * FROM learning_policy_exposures WHERE stream_id = :streamId " +
            "AND episode_id = :episodeId AND logical_run_id = :logicalRunId " +
            "AND attempt_ordinal = :attemptOrdinal AND policy_set_digest = :policySetDigest " +
            "LIMIT 2",
    )
    suspend fun findExactReservation(
        streamId: String,
        episodeId: String,
        logicalRunId: String,
        attemptOrdinal: Int,
        policySetDigest: String,
    ): List<LearningPolicyExposureEntity>

    @Query(
        "SELECT * FROM learning_policy_exposure_items WHERE exposure_id = :exposureId " +
            "ORDER BY rank ASC, policy_id ASC LIMIT :limit",
    )
    suspend fun listItems(
        exposureId: String,
        limit: Int,
    ): List<LearningPolicyExposureItemEntity>

    @Query(
        "UPDATE learning_policy_exposure_items SET drop_reason = :dropReason " +
            "WHERE exposure_id = :exposureId AND policy_id = :policyId " +
            "AND drop_reason IS NULL AND injected_at_ms IS NULL",
    )
    suspend fun markItemDroppedIfNotInjected(
        exposureId: String,
        policyId: String,
        dropReason: String,
    ): Int

    @Query(
        "UPDATE learning_policy_exposure_items SET compiled_at_ms = :compiledAtMs " +
            "WHERE exposure_id = :exposureId AND compiled_at_ms IS NULL " +
            "AND injected_at_ms IS NULL AND drop_reason IS NULL " +
            "AND retrieved_at_ms <= :compiledAtMs",
    )
    suspend fun markItemsCompiledIfRetrieved(
        exposureId: String,
        compiledAtMs: Long,
    ): Int

    @Query(
        "UPDATE learning_policy_exposure_items SET injected_at_ms = :injectedAtMs " +
            "WHERE exposure_id = :exposureId AND compiled_at_ms IS NOT NULL " +
            "AND compiled_at_ms <= :injectedAtMs AND injected_at_ms IS NULL " +
            "AND drop_reason IS NULL",
    )
    suspend fun markItemsInjectedIfCompiled(
        exposureId: String,
        injectedAtMs: Long,
    ): Int

    @Query(
        "SELECT * FROM learning_policy_exposures WHERE scope_kind = :scopeKind " +
            "AND scope_id = :scopeId AND updated_at_ms >= :fromUpdatedAtMs " +
            "AND updated_at_ms < :toUpdatedAtMs " +
            "ORDER BY updated_at_ms DESC, id DESC LIMIT :limit",
    )
    suspend fun listByScopeAndUpdatedRange(
        scopeKind: String,
        scopeId: String,
        fromUpdatedAtMs: Long,
        toUpdatedAtMs: Long,
        limit: Int,
    ): List<LearningPolicyExposureEntity>

    /**
     * Bounded recovery page for authority-derived outcome links.
     *
     * Every hard attempt prerequisite is applied before LIMIT. The caller still rehydrates the
     * receipt and uses [LearningPolicyExposureEntity.stateVersion] as the exact CAS revision; this
     * query never promotes an undispatched or non-terminal attempt into attribution.
     */
    @Query(
        "SELECT * FROM learning_policy_exposures WHERE stream_id = :streamId " +
            "AND replay_generation = :replayGeneration AND episode_id = :episodeId " +
            "AND logical_run_id = :logicalRunId AND scope_kind = :scopeKind " +
            "AND scope_id = :scopeId AND injected_at_ms IS NOT NULL " +
            "AND host_dispatched_at_ms IS NOT NULL AND terminal_outcome IS NOT NULL " +
            "AND terminal_at_ms IS NOT NULL AND outcome_linked_at_ms IS NULL " +
            "AND outcome_source_type IS NULL AND outcome_source_id IS NULL " +
            "AND outcome_source_revision IS NULL AND attribution_state = 'UNKNOWN' " +
            "ORDER BY attempt_ordinal ASC, id ASC LIMIT :limit",
    )
    suspend fun listUnlinkedTerminalAttempts(
        streamId: String,
        replayGeneration: Long,
        episodeId: String,
        logicalRunId: String,
        scopeKind: String,
        scopeId: String,
        limit: Int,
    ): List<LearningPolicyExposureEntity>

    @Query(
        "UPDATE learning_policy_exposures SET state_version = state_version + 1, " +
            "furthest_state = :furthestState, retrieved_at_ms = :retrievedAtMs, " +
            "compiled_at_ms = :compiledAtMs, injected_at_ms = :injectedAtMs, " +
            "host_dispatched_at_ms = :hostDispatchedAtMs, " +
            "first_progress_at_ms = :firstProgressAtMs, " +
            "response_finished_at_ms = :responseFinishedAtMs, " +
            "outcome_linked_at_ms = :outcomeLinkedAtMs, terminal_outcome = :terminalOutcome, " +
            "terminal_at_ms = :terminalAtMs, outcome_source_type = :outcomeSourceType, " +
            "outcome_source_id = :outcomeSourceId, " +
            "outcome_source_revision = :outcomeSourceRevision, " +
            "attribution_state = :attributionState, updated_at_ms = :updatedAtMs " +
            "WHERE id = :id AND state_version = :expectedStateVersion " +
            "AND updated_at_ms <= :updatedAtMs",
    )
    suspend fun updateSnapshotIfCurrent(
        id: String,
        expectedStateVersion: Long,
        furthestState: String,
        retrievedAtMs: Long?,
        compiledAtMs: Long?,
        injectedAtMs: Long?,
        hostDispatchedAtMs: Long?,
        firstProgressAtMs: Long?,
        responseFinishedAtMs: Long?,
        outcomeLinkedAtMs: Long?,
        terminalOutcome: String?,
        terminalAtMs: Long?,
        outcomeSourceType: String?,
        outcomeSourceId: String?,
        outcomeSourceRevision: Long?,
        attributionState: String,
        updatedAtMs: Long,
    ): Int

    @Query(
        "DELETE FROM learning_policy_exposures WHERE id IN " +
            "(SELECT id FROM learning_policy_exposures WHERE scope_kind = :scopeKind " +
            "AND scope_id = :scopeId AND updated_at_ms < :updatedBeforeMs " +
            "ORDER BY updated_at_ms ASC, id ASC LIMIT :limit)",
    )
    suspend fun deleteByScopeBefore(
        scopeKind: String,
        scopeId: String,
        updatedBeforeMs: Long,
        limit: Int,
    ): Int

    @Query(
        "DELETE FROM learning_policy_exposures WHERE id IN " +
            "(SELECT id FROM learning_policy_exposures WHERE scope_kind = :scopeKind " +
            "AND scope_id = :scopeId ORDER BY updated_at_ms ASC, id ASC LIMIT :limit)",
    )
    suspend fun deleteScopePage(
        scopeKind: String,
        scopeId: String,
        limit: Int,
    ): Int

    @Query("SELECT MAX(replay_generation) FROM learning_policy_exposures")
    suspend fun maxReplayGeneration(): Long?

    @Query("DELETE FROM learning_policy_exposure_items")
    suspend fun deleteAllItems(): Int

    @Query("DELETE FROM learning_policy_exposures")
    suspend fun deleteAllExposures(): Int

    /**
     * A dispatched attempt without both terminal and authority link stays for recovery/audit.
     * Retrieval/drop-only and fully settled attempts may expire in a bounded page.
     */
    @Query(
        "DELETE FROM learning_policy_exposures WHERE id IN (" +
            "SELECT id FROM learning_policy_exposures WHERE updated_at_ms < :updatedBeforeMs " +
            "AND (host_dispatched_at_ms IS NULL OR " +
            "(terminal_at_ms IS NOT NULL AND outcome_linked_at_ms IS NOT NULL)) " +
            "ORDER BY updated_at_ms ASC, id ASC LIMIT :limit)",
    )
    suspend fun deleteExpiredSettledPage(
        updatedBeforeMs: Long,
        limit: Int,
    ): Int
}

data class PolicyReviewExposureAggregate(
    @androidx.room.ColumnInfo(name = "actual_retrieved_count") val actualRetrievedCount: Long,
    @androidx.room.ColumnInfo(name = "injected_hit_count") val injectedHitCount: Long,
    @androidx.room.ColumnInfo(name = "host_dispatched_hit_count") val hostDispatchedHitCount: Long,
    @androidx.room.ColumnInfo(name = "dropped_item_count") val droppedItemCount: Long,
    @androidx.room.ColumnInfo(name = "estimated_token_cost") val estimatedTokenCost: Long,
)
