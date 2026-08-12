package me.rerere.rikkahub.learning.storage

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LearningPolicyDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPolicy(entity: LearningPolicyEntity)

    @Query("SELECT * FROM learning_policies WHERE id = :policyId LIMIT 1")
    suspend fun findPolicy(policyId: String): LearningPolicyEntity?

    @Query(
        "SELECT * FROM learning_policies WHERE scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND task_signature = :taskSignature AND artifact_sha256 = :artifactSha256 LIMIT 2",
    )
    suspend fun findPoliciesByArtifact(
        scopeKind: String,
        scopeId: String,
        taskSignature: String,
        artifactSha256: String,
    ): List<LearningPolicyEntity>

    @Query(
        "SELECT p.* FROM learning_policies p WHERE p.scope_kind = :scopeKind " +
            "AND p.scope_id = :scopeId AND p.status IN ('CANDIDATE', 'SHADOW') " +
            "AND p.source_valid = 1 AND p.schema_valid = 1 " +
            "AND EXISTS (SELECT 1 FROM policy_evidence pe " +
            "JOIN learning_episodes ep ON ep.id = pe.episode_id " +
            "LEFT JOIN learning_episode_lessons l ON l.episode_id = pe.episode_id " +
            "AND l.lesson_version = pe.lesson_version " +
            "LEFT JOIN learning_source_validity sv ON sv.stream_id = ep.stream_id " +
            "AND sv.replay_generation = ep.replay_generation " +
            "AND sv.scope_kind = ep.scope_kind AND sv.scope_id = ep.scope_id " +
            "AND sv.source_type = pe.source_type AND sv.source_id = pe.source_id " +
            "AND sv.source_revision = pe.source_revision WHERE pe.policy_id = p.id " +
            "AND " + VALID_POLICY_EVIDENCE_PREDICATE + ") " +
            "AND NOT EXISTS (SELECT 1 FROM policy_evidence pe " +
            "JOIN learning_episodes ep ON ep.id = pe.episode_id " +
            "LEFT JOIN learning_episode_lessons l ON l.episode_id = pe.episode_id " +
            "AND l.lesson_version = pe.lesson_version " +
            "LEFT JOIN learning_source_validity sv ON sv.stream_id = ep.stream_id " +
            "AND sv.replay_generation = ep.replay_generation " +
            "AND sv.scope_kind = ep.scope_kind AND sv.scope_id = ep.scope_id " +
            "AND sv.source_type = pe.source_type AND sv.source_id = pe.source_id " +
            "AND sv.source_revision = pe.source_revision WHERE pe.policy_id = p.id " +
            "AND NOT (" + VALID_POLICY_EVIDENCE_PREDICATE + ")) " +
            "AND p.task_signature = :taskSignature ORDER BY p.confidence DESC, " +
            "p.distinct_episode_support DESC, p.id ASC LIMIT :limit",
    )
    suspend fun listShadowCandidates(
        scopeKind: String,
        scopeId: String,
        taskSignature: String,
        limit: Int,
    ): List<LearningPolicyEntity>

    @Query(
        "SELECT p.* FROM learning_policies p WHERE p.scope_kind = :scopeKind " +
            "AND p.scope_id = :scopeId AND p.status IN ('CANDIDATE', 'SHADOW') " +
            "AND p.source_valid = 1 AND p.schema_valid = 1 " +
            "AND EXISTS (SELECT 1 FROM policy_evidence pe " +
            "JOIN learning_episodes ep ON ep.id = pe.episode_id " +
            "LEFT JOIN learning_episode_lessons l ON l.episode_id = pe.episode_id " +
            "AND l.lesson_version = pe.lesson_version " +
            "LEFT JOIN learning_source_validity sv ON sv.stream_id = ep.stream_id " +
            "AND sv.replay_generation = ep.replay_generation " +
            "AND sv.scope_kind = ep.scope_kind AND sv.scope_id = ep.scope_id " +
            "AND sv.source_type = pe.source_type AND sv.source_id = pe.source_id " +
            "AND sv.source_revision = pe.source_revision WHERE pe.policy_id = p.id " +
            "AND " + VALID_POLICY_EVIDENCE_PREDICATE + ") " +
            "AND NOT EXISTS (SELECT 1 FROM policy_evidence pe " +
            "JOIN learning_episodes ep ON ep.id = pe.episode_id " +
            "LEFT JOIN learning_episode_lessons l ON l.episode_id = pe.episode_id " +
            "AND l.lesson_version = pe.lesson_version " +
            "LEFT JOIN learning_source_validity sv ON sv.stream_id = ep.stream_id " +
            "AND sv.replay_generation = ep.replay_generation " +
            "AND sv.scope_kind = ep.scope_kind AND sv.scope_id = ep.scope_id " +
            "AND sv.source_type = pe.source_type AND sv.source_id = pe.source_id " +
            "AND sv.source_revision = pe.source_revision WHERE pe.policy_id = p.id " +
            "AND NOT (" + VALID_POLICY_EVIDENCE_PREDICATE + ")) " +
            "ORDER BY p.updated_at_ms DESC, p.confidence DESC, p.id ASC LIMIT :limit",
    )
    suspend fun listBoundedValidShadowPool(
        scopeKind: String,
        scopeId: String,
        limit: Int,
    ): List<LearningPolicyEntity>

    /** Second-stage identity fetch for FTS rows; all hard gates are repeated after the FTS LIMIT. */
    @Query(
        "SELECT p.* FROM learning_policies p WHERE p.scope_kind = :scopeKind " +
            "AND p.scope_id = :scopeId AND p.id IN (:policyIds) " +
            "AND p.status IN ('CANDIDATE', 'SHADOW') AND p.source_valid = 1 " +
            "AND p.schema_valid = 1 " +
            "AND EXISTS (SELECT 1 FROM policy_evidence pe " +
            "JOIN learning_episodes ep ON ep.id = pe.episode_id " +
            "LEFT JOIN learning_episode_lessons l ON l.episode_id = pe.episode_id " +
            "AND l.lesson_version = pe.lesson_version " +
            "LEFT JOIN learning_source_validity sv ON sv.stream_id = ep.stream_id " +
            "AND sv.replay_generation = ep.replay_generation " +
            "AND sv.scope_kind = ep.scope_kind AND sv.scope_id = ep.scope_id " +
            "AND sv.source_type = pe.source_type AND sv.source_id = pe.source_id " +
            "AND sv.source_revision = pe.source_revision WHERE pe.policy_id = p.id " +
            "AND " + VALID_POLICY_EVIDENCE_PREDICATE + ") " +
            "AND NOT EXISTS (SELECT 1 FROM policy_evidence pe " +
            "JOIN learning_episodes ep ON ep.id = pe.episode_id " +
            "LEFT JOIN learning_episode_lessons l ON l.episode_id = pe.episode_id " +
            "AND l.lesson_version = pe.lesson_version " +
            "LEFT JOIN learning_source_validity sv ON sv.stream_id = ep.stream_id " +
            "AND sv.replay_generation = ep.replay_generation " +
            "AND sv.scope_kind = ep.scope_kind AND sv.scope_id = ep.scope_id " +
            "AND sv.source_type = pe.source_type AND sv.source_id = pe.source_id " +
            "AND sv.source_revision = pe.source_revision WHERE pe.policy_id = p.id " +
            "AND NOT (" + VALID_POLICY_EVIDENCE_PREDICATE + ")) " +
            "ORDER BY p.updated_at_ms DESC, p.id ASC LIMIT 96",
    )
    suspend fun findEligibleShadowPoliciesByIds(
        scopeKind: String,
        scopeId: String,
        policyIds: List<String>,
    ): List<LearningPolicyEntity>

    @Query(
        "UPDATE learning_policies SET task_signature = :taskSignature, policy_type = :policyType, " +
            "trigger_summary = :triggerSummary, procedure_summary = :procedureSummary, " +
            "verification_summary = :verificationSummary, boundary_summary = :boundarySummary, " +
            "failure_mode_summary = :failureModeSummary, state_version = state_version + 1, " +
            "artifact_sha256 = :newArtifactSha256, compiler_abi = :compilerAbi, " +
            "status = :status, source_valid = :sourceValid, schema_valid = :schemaValid, " +
            "stale_reason = :staleReason, " +
            "distinct_episode_support = :distinctEpisodeSupport, " +
            "positive_episode_count = :positiveEpisodeCount, " +
            "negative_episode_count = :negativeEpisodeCount, confidence = :confidence, " +
            "updated_at_ms = :updatedAtMs WHERE id = :policyId " +
            "AND state_version = :expectedStateVersion AND artifact_sha256 = :expectedArtifactSha256 " +
            "AND usage_count = 0 AND last_used_at_ms IS NULL " +
            "AND observed_utility_delta IS NULL AND utility_uncertainty IS NULL " +
            "AND updated_at_ms <= :updatedAtMs",
    )
    suspend fun updatePolicyIfCurrent(
        policyId: String,
        expectedStateVersion: Long,
        expectedArtifactSha256: String,
        taskSignature: String,
        policyType: String,
        triggerSummary: String,
        procedureSummary: String,
        verificationSummary: String,
        boundarySummary: String,
        failureModeSummary: String,
        newArtifactSha256: String,
        compilerAbi: String,
        status: String,
        sourceValid: Boolean,
        schemaValid: Boolean,
        staleReason: String?,
        distinctEpisodeSupport: Long,
        positiveEpisodeCount: Long,
        negativeEpisodeCount: Long,
        confidence: Double,
        updatedAtMs: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvidenceIgnore(entity: PolicyEvidenceEntity): Long

    @Query(
        "SELECT * FROM policy_evidence WHERE policy_id = :policyId AND episode_id = :episodeId " +
            "LIMIT 1",
    )
    suspend fun findEvidence(
        policyId: String,
        episodeId: String,
    ): PolicyEvidenceEntity?

    @Query(
        "SELECT pe.policy_id, pe.episode_id, pe.polarity, pe.quality, CASE WHEN " +
            VALID_POLICY_EVIDENCE_PREDICATE +
            " THEN 1 ELSE 0 END AS source_valid FROM policy_evidence pe " +
            "JOIN learning_episodes ep ON ep.id = pe.episode_id " +
            "LEFT JOIN learning_episode_lessons l ON l.episode_id = pe.episode_id " +
            "AND l.lesson_version = pe.lesson_version " +
            "LEFT JOIN learning_source_validity sv ON sv.stream_id = ep.stream_id " +
            "AND sv.replay_generation = ep.replay_generation " +
            "AND sv.scope_kind = ep.scope_kind AND sv.scope_id = ep.scope_id " +
            "AND sv.source_type = pe.source_type AND sv.source_id = pe.source_id " +
            "AND sv.source_revision = pe.source_revision WHERE pe.policy_id = :policyId " +
            "ORDER BY pe.episode_id ASC LIMIT :limit",
    )
    suspend fun listEvidenceValidity(
        policyId: String,
        limit: Int,
    ): List<PolicyEvidenceValidityRow>

    @Query(
        "DELETE FROM policy_evidence WHERE policy_id = :policyId AND episode_id = :episodeId",
    )
    suspend fun deleteEvidence(policyId: String, episodeId: String): Int

    @Query("SELECT COUNT(DISTINCT episode_id) FROM policy_evidence WHERE policy_id = :policyId")
    suspend fun countDistinctEpisodeSupport(policyId: String): Long

    @Query(
        "SELECT COUNT(DISTINCT episode_id) FROM policy_evidence WHERE policy_id = :policyId " +
            "AND polarity = :polarity",
    )
    suspend fun countDistinctEpisodeSupportByPolarity(policyId: String, polarity: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(entity: PolicyRevisionEntity)

    @Query(
        "SELECT * FROM policy_revisions WHERE policy_id = :policyId " +
            "ORDER BY revision DESC LIMIT :limit",
    )
    suspend fun listRevisions(policyId: String, limit: Int): List<PolicyRevisionEntity>

    @Query(
        "SELECT * FROM policy_revisions WHERE policy_id = :policyId AND revision = :revision LIMIT 1",
    )
    suspend fun findRevision(policyId: String, revision: Long): PolicyRevisionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLineageIgnore(entity: PolicyLineageEntity): Long

    @Query(
        "SELECT * FROM policy_lineage WHERE child_policy_id = :policyId " +
            "ORDER BY relation_type ASC, parent_policy_id ASC LIMIT :limit",
    )
    suspend fun listParents(policyId: String, limit: Int): List<PolicyLineageEntity>

    @Query(
        "SELECT * FROM policy_lineage WHERE parent_policy_id = :policyId " +
            "AND relation_type IN ('DERIVED_FROM', 'MERGED_FROM') " +
            "ORDER BY child_policy_id ASC, relation_type ASC LIMIT :limit",
    )
    suspend fun listDerivedChildren(policyId: String, limit: Int): List<PolicyLineageEntity>

    @Query(
        "SELECT * FROM policy_lineage WHERE child_policy_id = :childPolicyId " +
            "AND parent_policy_id = :parentPolicyId AND relation_type = :relationType LIMIT 1",
    )
    suspend fun findLineage(
        childPolicyId: String,
        parentPolicyId: String,
        relationType: String,
    ): PolicyLineageEntity?

    @Query(
        "WITH RECURSIVE ancestors(policy_id, depth) AS (" +
            "SELECT parent_policy_id, 1 FROM policy_lineage WHERE child_policy_id = :candidateParentId " +
            "UNION ALL SELECT l.parent_policy_id, a.depth + 1 FROM policy_lineage l " +
            "JOIN ancestors a ON l.child_policy_id = a.policy_id WHERE a.depth < :maxDepth) " +
            "SELECT COUNT(*) FROM ancestors WHERE policy_id = :candidateChildId",
    )
    suspend fun countCyclePaths(
        candidateChildId: String,
        candidateParentId: String,
        maxDepth: Int,
    ): Long

    @Query(
        "WITH RECURSIVE ancestors(policy_id, depth) AS (" +
            "SELECT parent_policy_id, 1 FROM policy_lineage WHERE child_policy_id = :policyId " +
            "UNION ALL SELECT l.parent_policy_id, a.depth + 1 FROM policy_lineage l " +
            "JOIN ancestors a ON l.child_policy_id = a.policy_id WHERE a.depth < :maxDepth) " +
            "SELECT COUNT(*) FROM ancestors WHERE depth = :maxDepth",
    )
    suspend fun countPathsAtDepthLimit(policyId: String, maxDepth: Int): Long

    @Query(
        "SELECT DISTINCT pe.policy_id FROM policy_evidence pe " +
            "JOIN learning_policies p ON p.id = pe.policy_id " +
            "JOIN learning_episodes e ON e.id = pe.episode_id " +
            "JOIN learning_trace_features t ON t.episode_id = e.id " +
            "WHERE e.stream_id = :streamId AND e.replay_generation = :replayGeneration " +
            "AND p.scope_kind = :scopeKind AND p.scope_id = :scopeId " +
            "AND e.scope_kind = :scopeKind AND e.scope_id = :scopeId " +
            "AND t.source_type = :sourceType AND t.source_id = :sourceId " +
            "AND t.source_revision = :sourceRevision AND p.source_valid = 1 " +
            "AND pe.policy_id > :afterPolicyId ORDER BY pe.policy_id ASC LIMIT :limit",
    )
    suspend fun listPoliciesUsingSource(
        streamId: String,
        replayGeneration: Long,
        scopeKind: String,
        scopeId: String,
        sourceType: String,
        sourceId: String,
        sourceRevision: Long,
        afterPolicyId: String,
        limit: Int,
    ): List<String>

    @Query(
        "SELECT p.id FROM learning_policies p WHERE p.source_valid = 1 AND " +
            "EXISTS (SELECT 1 FROM policy_evidence pe " +
            "JOIN learning_episodes e ON e.id = pe.episode_id " +
            "LEFT JOIN learning_episode_lessons l ON l.episode_id = pe.episode_id " +
            "AND l.lesson_version = pe.lesson_version " +
            "LEFT JOIN learning_source_validity s ON s.stream_id = e.stream_id " +
            "AND s.replay_generation = e.replay_generation " +
            "AND s.scope_kind = e.scope_kind AND s.scope_id = e.scope_id " +
            "AND s.source_type = pe.source_type AND s.source_id = pe.source_id " +
            "AND s.source_revision = pe.source_revision WHERE pe.policy_id = p.id " +
            "AND NOT (" + VALID_POLICY_EVIDENCE_PREDICATE_FOR_E_ALIAS + ")) " +
            "ORDER BY p.id ASC LIMIT :limit",
    )
    suspend fun listLivePoliciesWithInvalidEvidence(limit: Int): List<String>

    @Query(RECONCILE_POLICY_SOURCE_SQL)
    suspend fun reconcilePolicySourceIfCurrent(
        policyId: String,
        expectedStateVersion: Long,
        status: String,
        sourceValid: Boolean,
        staleReason: String?,
        support: Long,
        positive: Long,
        negative: Long,
        confidence: Double,
        updatedAtMs: Long,
    ): Int

    @Query(
        "DELETE FROM learning_policies WHERE id IN (SELECT id FROM learning_policies " +
            "WHERE status IN ('CANDIDATE', 'SHADOW', 'ARCHIVED', 'STALE') " +
            "AND updated_at_ms < :cutoffMs " +
            "ORDER BY updated_at_ms ASC, id ASC LIMIT :limit)",
    )
    suspend fun deleteExpiredPolicies(cutoffMs: Long, limit: Int): Int

    @Query(
        "DELETE FROM policy_revisions WHERE rowid IN (SELECT r.rowid FROM policy_revisions r " +
            "JOIN learning_policies p ON p.id = r.policy_id " +
            "WHERE r.created_at_ms < :cutoffMs AND r.revision < p.state_version " +
            "ORDER BY r.created_at_ms ASC, r.policy_id ASC, r.revision ASC LIMIT :limit)",
    )
    suspend fun deleteExpiredNonCurrentRevisions(cutoffMs: Long, limit: Int): Int

    @Query("DELETE FROM learning_policies WHERE scope_kind = :scopeKind AND scope_id = :scopeId")
    suspend fun deletePoliciesByScope(scopeKind: String, scopeId: String): Int

    @Query("DELETE FROM policy_lineage")
    suspend fun deleteAllLineage(): Int

    @Query("DELETE FROM policy_evidence")
    suspend fun deleteAllEvidence(): Int

    @Query("DELETE FROM policy_revisions")
    suspend fun deleteAllRevisions(): Int

    @Query("DELETE FROM learning_policies")
    suspend fun deleteAllPolicies(): Int
}

data class PolicyEvidenceValidityRow(
    @ColumnInfo(name = "policy_id") val policyId: String,
    @ColumnInfo(name = "episode_id") val episodeId: String,
    val polarity: String,
    val quality: Double?,
    @ColumnInfo(name = "source_valid") val sourceValid: Boolean,
)

internal const val VALID_POLICY_EVIDENCE_PREDICATE =
    "l.episode_id IS NOT NULL AND l.state = 'VALID' AND sv.source_id IS NOT NULL " +
        "AND sv.state = 'VALID' AND sv.integrity_sha256 IS NOT NULL " +
        "AND sv.integrity_sha256 = pe.source_integrity_sha256 " +
        "AND EXISTS (SELECT 1 FROM learning_trace_features any_t WHERE any_t.episode_id = ep.id " +
        "AND any_t.source_type = 'CONVERSATION_MESSAGE') " +
        "AND NOT EXISTS (SELECT 1 FROM learning_trace_features t " +
        "LEFT JOIN learning_source_validity all_sv ON all_sv.stream_id = ep.stream_id " +
        "AND all_sv.replay_generation = ep.replay_generation " +
        "AND all_sv.scope_kind = ep.scope_kind AND all_sv.scope_id = ep.scope_id " +
        "AND all_sv.source_type = t.source_type AND all_sv.source_id = t.source_id " +
        "AND all_sv.source_revision = t.source_revision WHERE t.episode_id = ep.id " +
        "AND t.source_type = 'CONVERSATION_MESSAGE' " +
        "AND (t.source_revision IS NULL OR all_sv.source_id IS NULL " +
        "OR all_sv.state != 'VALID' OR all_sv.integrity_sha256 IS NULL))"

internal const val VALID_POLICY_EVIDENCE_PREDICATE_FOR_E_ALIAS =
    "l.episode_id IS NOT NULL AND l.state = 'VALID' AND s.source_id IS NOT NULL " +
        "AND s.state = 'VALID' AND s.integrity_sha256 IS NOT NULL " +
        "AND s.integrity_sha256 = pe.source_integrity_sha256 " +
        "AND EXISTS (SELECT 1 FROM learning_trace_features any_t WHERE any_t.episode_id = e.id " +
        "AND any_t.source_type = 'CONVERSATION_MESSAGE') " +
        "AND NOT EXISTS (SELECT 1 FROM learning_trace_features t " +
        "LEFT JOIN learning_source_validity all_s ON all_s.stream_id = e.stream_id " +
        "AND all_s.replay_generation = e.replay_generation " +
        "AND all_s.scope_kind = e.scope_kind AND all_s.scope_id = e.scope_id " +
        "AND all_s.source_type = t.source_type AND all_s.source_id = t.source_id " +
        "AND all_s.source_revision = t.source_revision WHERE t.episode_id = e.id " +
        "AND t.source_type = 'CONVERSATION_MESSAGE' " +
        "AND (t.source_revision IS NULL OR all_s.source_id IS NULL " +
        "OR all_s.state != 'VALID' OR all_s.integrity_sha256 IS NULL))"

internal const val RECONCILE_POLICY_SOURCE_SQL =
    "UPDATE learning_policies SET status = :status, source_valid = :sourceValid, " +
        "stale_reason = :staleReason, distinct_episode_support = :support, " +
        "positive_episode_count = :positive, negative_episode_count = :negative, " +
        "confidence = :confidence, state_version = state_version + 1, " +
        "updated_at_ms = MAX(updated_at_ms, :updatedAtMs) WHERE id = :policyId " +
        "AND state_version = :expectedStateVersion AND source_valid = 1"
