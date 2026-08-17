package me.rerere.rikkahub.learning.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Storage primitives only; exact validation and cross-table transaction ownership live upstream. */
@Dao
interface LearningObservedUtilityDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAssignment(entity: LearningObservedUtilityAssignmentEntity): Long

    @Query("SELECT * FROM learning_observed_utility_assignments WHERE id = :id LIMIT 1")
    suspend fun findAssignment(id: String): LearningObservedUtilityAssignmentEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutcome(entity: LearningObservedUtilityOutcomeEntity): Long

    @Query(
        "SELECT * FROM learning_observed_utility_outcomes " +
            "WHERE assignment_id = :assignmentId LIMIT 1",
    )
    suspend fun findOutcome(assignmentId: String): LearningObservedUtilityOutcomeEntity?

    @Query(
        "SELECT * FROM learning_observed_utility_assignments " +
            "WHERE scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND target_policy_id = :policyId " +
            "AND target_policy_state_version = :policyStateVersion " +
            "AND target_policy_content_revision = :policyContentRevision " +
            "AND target_policy_artifact_sha256 = :policyArtifactSha256 " +
            "AND policy_set_digest = :policySetDigest AND design_digest = :designDigest " +
            "AND cohort_digest = :cohortDigest " +
            "AND source_window_start_ms = :sourceWindowStartMs " +
            "AND source_window_end_ms = :sourceWindowEndMs " +
            "ORDER BY assigned_at_ms ASC, id ASC LIMIT :limit",
    )
    suspend fun listExactAssignments(
        scopeKind: String,
        scopeId: String,
        policyId: String,
        policyStateVersion: Long,
        policyContentRevision: Long,
        policyArtifactSha256: String,
        policySetDigest: String,
        designDigest: String,
        cohortDigest: String,
        sourceWindowStartMs: Long,
        sourceWindowEndMs: Long,
        limit: Int,
    ): List<LearningObservedUtilityAssignmentEntity>

    @Query(
        "SELECT * FROM learning_observed_utility_outcomes " +
            "WHERE assignment_id IN (:assignmentIds) ORDER BY assignment_id ASC",
    )
    suspend fun listOutcomes(
        assignmentIds: List<String>,
    ): List<LearningObservedUtilityOutcomeEntity>

    @Query(
        "SELECT a.* FROM learning_observed_utility_assignments a " +
            "WHERE a.stream_id = :streamId AND a.replay_generation = :replayGeneration " +
            "AND a.episode_id = :episodeId AND a.logical_run_id = :logicalRunId " +
            "AND NOT EXISTS (SELECT 1 FROM learning_observed_utility_outcomes o " +
            "WHERE o.assignment_id = a.id) " +
            "ORDER BY a.attempt_ordinal ASC, a.design_digest ASC, a.id ASC LIMIT :limit",
    )
    suspend fun listUnclosedAssignmentsForEpisode(
        streamId: String,
        replayGeneration: Long,
        episodeId: String,
        logicalRunId: String,
        limit: Int,
    ): List<LearningObservedUtilityAssignmentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvaluationReceipt(
        entity: LearningObservedUtilityEvaluationReceiptEntity,
    ): Long

    @Query(
        "SELECT * FROM learning_observed_utility_evaluation_receipts " +
            "WHERE receipt_digest = :receiptDigest LIMIT 1",
    )
    suspend fun findEvaluationReceipt(
        receiptDigest: String,
    ): LearningObservedUtilityEvaluationReceiptEntity?

    @Query(
        "SELECT * FROM learning_policies WHERE id = :policyId " +
            "AND scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND state_version = :stateVersion AND content_revision = :contentRevision " +
            "AND artifact_sha256 = :artifactSha256 LIMIT 1",
    )
    suspend fun findExactPolicyFence(
        policyId: String,
        scopeKind: String,
        scopeId: String,
        stateVersion: Long,
        contentRevision: Long,
        artifactSha256: String,
    ): LearningPolicyEntity?

    /** Scalar projection is in the same transaction as append-only receipt insertion. */
    @Query(
        "UPDATE learning_policies SET observed_utility_delta = :observedUtilityDelta, " +
            "utility_uncertainty = :utilityUncertainty, updated_at_ms = CASE " +
            "WHEN updated_at_ms < :evaluatedAtMs THEN :evaluatedAtMs ELSE updated_at_ms END " +
            "WHERE id = :policyId AND scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND state_version = :stateVersion AND content_revision = :contentRevision " +
            "AND artifact_sha256 = :artifactSha256 AND status = 'ACTIVE' " +
            "AND source_valid = 1 AND schema_valid = 1 AND stale_reason IS NULL",
    )
    suspend fun updateObservedUtilityProjectionIfExact(
        policyId: String,
        scopeKind: String,
        scopeId: String,
        stateVersion: Long,
        contentRevision: Long,
        artifactSha256: String,
        observedUtilityDelta: Double,
        utilityUncertainty: Double,
        evaluatedAtMs: Long,
    ): Int

    /**
     * One canonical representative per exact pre-registered design/cohort/window. The correlated
     * MIN is applied before LIMIT; a missing outcome remains eligible and becomes durable ABSTAIN.
     */
    @Query(
        "SELECT a.* FROM learning_observed_utility_assignments a " +
            // Do not join the current Policy head here. A changed/deleted/suspended fence is a
            // due candidate whose immutable terminal result is FENCE_CHANGED, not invisible work.
            "WHERE a.source_window_end_ms <= :frozenNowMs AND (" +
            "a.source_window_end_ms > :afterWindowEndMs OR " +
            "(a.source_window_end_ms = :afterWindowEndMs AND a.design_digest > :afterDesignDigest) OR " +
            "(a.source_window_end_ms = :afterWindowEndMs AND a.design_digest = :afterDesignDigest " +
            "AND a.cohort_digest > :afterCohortDigest) OR " +
            "(a.source_window_end_ms = :afterWindowEndMs AND a.design_digest = :afterDesignDigest " +
            "AND a.cohort_digest = :afterCohortDigest AND a.target_policy_id > :afterPolicyId) OR " +
            "(a.source_window_end_ms = :afterWindowEndMs AND a.design_digest = :afterDesignDigest " +
            "AND a.cohort_digest = :afterCohortDigest AND a.target_policy_id = :afterPolicyId " +
            "AND a.id > :afterAssignmentId)) " +
            "AND a.id = (SELECT MIN(g.id) FROM learning_observed_utility_assignments g " +
            "WHERE g.scope_kind = a.scope_kind AND g.scope_id = a.scope_id " +
            "AND g.target_policy_id = a.target_policy_id " +
            "AND g.target_policy_state_version = a.target_policy_state_version " +
            "AND g.target_policy_content_revision = a.target_policy_content_revision " +
            "AND g.target_policy_artifact_sha256 = a.target_policy_artifact_sha256 " +
            "AND g.policy_set_digest = a.policy_set_digest " +
            "AND g.design_digest = a.design_digest AND g.cohort_digest = a.cohort_digest " +
            "AND g.source_window_start_ms = a.source_window_start_ms " +
            "AND g.source_window_end_ms = a.source_window_end_ms) " +
            "AND NOT EXISTS (SELECT 1 FROM learning_observed_utility_evaluation_receipts r " +
            "WHERE r.scope_kind = a.scope_kind AND r.scope_id = a.scope_id " +
            "AND r.policy_id = a.target_policy_id " +
            "AND r.expected_state_version = a.target_policy_state_version " +
            "AND r.expected_content_revision = a.target_policy_content_revision " +
            "AND r.expected_artifact_sha256 = a.target_policy_artifact_sha256 " +
            "AND r.design_digest = a.design_digest AND r.cohort_digest = a.cohort_digest " +
            "AND r.source_window_start_ms = a.source_window_start_ms " +
            "AND r.source_window_end_ms = a.source_window_end_ms) " +
            "ORDER BY a.source_window_end_ms ASC, a.design_digest ASC, " +
            "a.cohort_digest ASC, a.target_policy_id ASC, a.id ASC LIMIT :limit",
    )
    suspend fun listDueDesignRepresentatives(
        frozenNowMs: Long,
        afterWindowEndMs: Long,
        afterDesignDigest: String,
        afterCohortDigest: String,
        afterPolicyId: String,
        afterAssignmentId: String,
        limit: Int,
    ): List<LearningObservedUtilityAssignmentEntity>

    @Query("SELECT MAX(replay_generation) FROM learning_observed_utility_assignments")
    suspend fun maxReplayGeneration(): Long?

    @Query(
        "DELETE FROM learning_observed_utility_evaluation_receipts WHERE receipt_digest IN (" +
            "SELECT receipt_digest FROM learning_observed_utility_evaluation_receipts " +
            "WHERE evaluated_at_ms < :cutoffMs " +
            "ORDER BY evaluated_at_ms ASC, receipt_digest ASC LIMIT :limit)",
    )
    suspend fun deleteExpiredEvaluationReceiptsPage(cutoffMs: Long, limit: Int): Int

    @Query(
        "DELETE FROM learning_observed_utility_assignments WHERE id IN (" +
            "SELECT id FROM learning_observed_utility_assignments " +
            "WHERE source_window_end_ms < :cutoffMs " +
            "ORDER BY source_window_end_ms ASC, id ASC LIMIT :limit)",
    )
    suspend fun deleteExpiredAssignmentsPage(cutoffMs: Long, limit: Int): Int

    @Query(
        "DELETE FROM learning_observed_utility_evaluation_receipts WHERE receipt_digest IN (" +
            "SELECT receipt_digest FROM learning_observed_utility_evaluation_receipts " +
            "WHERE scope_kind = :scopeKind AND scope_id = :scopeId " +
            "ORDER BY evaluated_at_ms ASC, receipt_digest ASC LIMIT :limit)",
    )
    suspend fun deleteEvaluationScopePage(scopeKind: String, scopeId: String, limit: Int): Int

    @Query(
        "DELETE FROM learning_observed_utility_assignments WHERE id IN (" +
            "SELECT id FROM learning_observed_utility_assignments " +
            "WHERE scope_kind = :scopeKind AND scope_id = :scopeId " +
            "ORDER BY assigned_at_ms ASC, id ASC LIMIT :limit)",
    )
    suspend fun deleteAssignmentScopePage(scopeKind: String, scopeId: String, limit: Int): Int

    @Query("DELETE FROM learning_observed_utility_evaluation_receipts")
    suspend fun deleteAllEvaluationReceipts(): Int

    @Query("DELETE FROM learning_observed_utility_outcomes")
    suspend fun deleteAllOutcomes(): Int

    @Query("DELETE FROM learning_observed_utility_assignments")
    suspend fun deleteAllAssignments(): Int
}
