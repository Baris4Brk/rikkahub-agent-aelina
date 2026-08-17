package me.rerere.rikkahub.learning.storage

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LearningPolicyDao {
    /**
     * Bounded user-review surface. Assistant-scoped rows never cross Assistant boundaries;
     * authority-subject rows remain visible because the explicit consuming Assistant is bound only
     * when the user creates a grant.
     */
    @Query(
        "SELECT * FROM learning_policies WHERE " +
            "((scope_kind = 'ASSISTANT' AND scope_id = :consumingAssistantId) " +
            "OR scope_kind = 'AUTHORITY_SUBJECT') " +
            "ORDER BY updated_at_ms DESC, id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 80 THEN :limit ELSE 0 END",
    )
    suspend fun listForBoundedReview(
        consumingAssistantId: String,
        limit: Int,
    ): List<LearningPolicyEntity>

    @Query(
        "SELECT COUNT(*) FROM learning_policies WHERE status IN ('CANDIDATE', 'SHADOW') " +
            "AND source_valid = 1 AND schema_valid = 1 " +
            "AND substr(applicable_tool_schemas_wire, 1, 9) = 'EXACT_V1:' " +
            "AND substr(applicable_model_identity_wire, 1, 9) = 'EXACT_V1:' " +
            "AND substr(applicable_provider_identity_wire, 1, 9) = 'EXACT_V1:' " +
            "AND applicable_template_identity IS NOT NULL " +
            "AND applicable_configuration_identity IS NOT NULL " +
            "AND applicable_configuration_generation > 0",
    )
    suspend fun countEligibleShadowPolicies(): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPolicy(entity: LearningPolicyEntity)

    @Query("SELECT * FROM learning_policies WHERE id = :policyId LIMIT 1")
    suspend fun findPolicy(policyId: String): LearningPolicyEntity?

    /** Content-free applicability projection consumed after final provider/tool identity binding. */
    @Query(
        "SELECT id AS policy_id, content_revision, artifact_sha256, status, schema_valid, " +
            "applicable_tool_schemas_wire, applicable_model_identity_wire, " +
            "applicable_provider_identity_wire, applicable_template_identity, " +
            "applicable_configuration_identity, " +
            "applicable_configuration_generation, applicable_capability_digest, " +
            "applicable_authority_digest FROM learning_policies " +
            "WHERE id = :policyId LIMIT 1",
    )
    suspend fun findPolicyApplicability(
        policyId: String,
    ): LearningPolicyApplicabilityProjection?

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

    /** Exact artifact+producer cohort lookup; cross-cohort text/artifacts never merge evidence. */
    @Query(
        "SELECT * FROM learning_policies WHERE scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND task_signature = :taskSignature AND artifact_sha256 = :artifactSha256 " +
            "AND producer_model_identity = :producerModelIdentity " +
            "AND producer_provider_identity = :producerProviderIdentity " +
            "AND producer_configuration_identity = :producerConfigurationIdentity " +
            "AND producer_config_generation = :producerConfigGeneration LIMIT 2",
    )
    suspend fun findPoliciesByExactArtifactCohort(
        scopeKind: String,
        scopeId: String,
        taskSignature: String,
        artifactSha256: String,
        producerModelIdentity: String,
        producerProviderIdentity: String,
        producerConfigurationIdentity: String,
        producerConfigGeneration: Long,
    ): List<LearningPolicyEntity>

    @Query(
        "SELECT p.* FROM learning_policies p WHERE p.scope_kind = :scopeKind " +
            "AND p.scope_id = :scopeId AND p.status IN ('CANDIDATE', 'SHADOW') " +
            "AND p.updated_at_ms >= :freshAfterMs " +
            "AND p.source_valid = 1 AND p.schema_valid = 1 " +
            "AND substr(p.applicable_tool_schemas_wire, 1, 9) = 'EXACT_V1:' " +
            "AND substr(p.applicable_model_identity_wire, 1, 9) = 'EXACT_V1:' " +
            "AND substr(p.applicable_provider_identity_wire, 1, 9) = 'EXACT_V1:' " +
            "AND p.applicable_template_identity IS NOT NULL " +
            "AND p.applicable_configuration_identity IS NOT NULL " +
            "AND p.applicable_configuration_generation > 0 " +
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
        freshAfterMs: Long,
        limit: Int,
    ): List<LearningPolicyEntity>

    @Query(
        "SELECT p.* FROM learning_policies p WHERE p.scope_kind = :scopeKind " +
            "AND p.scope_id = :scopeId AND p.status IN ('CANDIDATE', 'SHADOW') " +
            "AND p.updated_at_ms >= :freshAfterMs " +
            "AND p.source_valid = 1 AND p.schema_valid = 1 " +
            "AND substr(p.applicable_tool_schemas_wire, 1, 9) = 'EXACT_V1:' " +
            "AND substr(p.applicable_model_identity_wire, 1, 9) = 'EXACT_V1:' " +
            "AND substr(p.applicable_provider_identity_wire, 1, 9) = 'EXACT_V1:' " +
            "AND p.applicable_template_identity IS NOT NULL " +
            "AND p.applicable_configuration_identity IS NOT NULL " +
            "AND p.applicable_configuration_generation > 0 " +
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
        freshAfterMs: Long,
        limit: Int,
    ): List<LearningPolicyEntity>

    /** Second-stage identity fetch for FTS rows; all hard gates are repeated after the FTS LIMIT. */
    @Query(
        "SELECT p.* FROM learning_policies p WHERE p.scope_kind = :scopeKind " +
            "AND p.scope_id = :scopeId AND p.id IN (:policyIds) " +
            "AND p.status IN ('CANDIDATE', 'SHADOW') AND p.source_valid = 1 " +
            "AND p.updated_at_ms >= :freshAfterMs " +
            "AND p.schema_valid = 1 " +
            "AND substr(p.applicable_tool_schemas_wire, 1, 9) = 'EXACT_V1:' " +
            "AND substr(p.applicable_model_identity_wire, 1, 9) = 'EXACT_V1:' " +
            "AND substr(p.applicable_provider_identity_wire, 1, 9) = 'EXACT_V1:' " +
            "AND p.applicable_template_identity IS NOT NULL " +
            "AND p.applicable_configuration_identity IS NOT NULL " +
            "AND p.applicable_configuration_generation > 0 " +
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
        freshAfterMs: Long,
    ): List<LearningPolicyEntity>

    /**
     * P2 provider-affecting read for one exact durable grant.
     *
     * This deliberately does not share the P1 CANDIDATE/SHADOW query. Every lifecycle, scope,
     * stream, content-revision, source/schema and reward-provenance fence is applied before the
     * SQL LIMIT so an ineligible row cannot consume a bounded result slot.
     */
    @Query(
        "SELECT p.* FROM learning_policies p WHERE p.id = :policyId " +
            "AND p.content_revision = :contentRevision " +
            "AND p.artifact_sha256 = :artifactSha256 " +
            "AND p.scope_kind = :scopeKind AND p.scope_id = :scopeId " +
            "AND p.task_signature = :taskSignature " +
            "AND p.status = 'ACTIVE' AND p.source_valid = 1 AND p.schema_valid = 1 " +
            "AND substr(p.applicable_tool_schemas_wire, 1, 9) = 'EXACT_V1:' " +
            "AND substr(p.applicable_model_identity_wire, 1, 9) = 'EXACT_V1:' " +
            "AND substr(p.applicable_provider_identity_wire, 1, 9) = 'EXACT_V1:' " +
            "AND p.applicable_template_identity IS NOT NULL " +
            "AND p.applicable_configuration_identity IS NOT NULL " +
            "AND p.applicable_configuration_generation > 0 " +
            "AND p.stale_reason IS NULL " +
            "AND EXISTS (SELECT 1 FROM policy_evidence pe " +
            "JOIN learning_episodes ep ON ep.id = pe.episode_id " +
            "LEFT JOIN learning_episode_lessons l ON l.episode_id = pe.episode_id " +
            "AND l.lesson_version = pe.lesson_version " +
            "LEFT JOIN learning_source_validity sv ON sv.stream_id = ep.stream_id " +
            "AND sv.replay_generation = ep.replay_generation " +
            "AND sv.scope_kind = ep.scope_kind AND sv.scope_id = ep.scope_id " +
            "AND sv.source_type = pe.source_type AND sv.source_id = pe.source_id " +
            "AND sv.source_revision = pe.source_revision WHERE pe.policy_id = p.id " +
            "AND ep.stream_id = :streamId AND " + VALID_POLICY_EVIDENCE_PREDICATE + ") " +
            "AND NOT EXISTS (SELECT 1 FROM policy_evidence pe " +
            "JOIN learning_episodes ep ON ep.id = pe.episode_id " +
            "LEFT JOIN learning_episode_lessons l ON l.episode_id = pe.episode_id " +
            "AND l.lesson_version = pe.lesson_version " +
            "LEFT JOIN learning_source_validity sv ON sv.stream_id = ep.stream_id " +
            "AND sv.replay_generation = ep.replay_generation " +
            "AND sv.scope_kind = ep.scope_kind AND sv.scope_id = ep.scope_id " +
            "AND sv.source_type = pe.source_type AND sv.source_id = pe.source_id " +
            "AND sv.source_revision = pe.source_revision WHERE pe.policy_id = p.id " +
            "AND (ep.stream_id != :streamId OR NOT (" + VALID_POLICY_EVIDENCE_PREDICATE + "))) " +
            "LIMIT 1",
    )
    suspend fun findExactGrantedActivePolicy(
        streamId: String,
        scopeKind: String,
        scopeId: String,
        taskSignature: String,
        policyId: String,
        contentRevision: Long,
        artifactSha256: String,
    ): LearningPolicyEntity?

    /**
     * Actual-use accounting after an exposure reached HOST_DISPATCHED. Every content/lifecycle
     * fence is repeated in SQL so a stale bundle cannot increment a replacement Policy.
     */
    @Query(
        "UPDATE learning_policies SET usage_count = usage_count + 1, " +
            "last_used_at_ms = :usedAtMs, updated_at_ms = CASE " +
            "WHEN updated_at_ms < :usedAtMs THEN :usedAtMs ELSE updated_at_ms END " +
            "WHERE id = :policyId AND content_revision = :contentRevision " +
            "AND artifact_sha256 = :artifactSha256 AND scope_kind = :scopeKind " +
            "AND scope_id = :scopeId AND status = 'ACTIVE' AND source_valid = 1 " +
            "AND schema_valid = 1 " +
            "AND substr(applicable_tool_schemas_wire, 1, 9) = 'EXACT_V1:' " +
            "AND substr(applicable_model_identity_wire, 1, 9) = 'EXACT_V1:' " +
            "AND substr(applicable_provider_identity_wire, 1, 9) = 'EXACT_V1:' " +
            "AND applicable_template_identity IS NOT NULL " +
            "AND applicable_configuration_identity IS NOT NULL " +
            "AND applicable_configuration_generation > 0 " +
            "AND stale_reason IS NULL AND usage_count < 9223372036854775807 " +
            "AND updated_at_ms <= :usedAtMs",
    )
    suspend fun recordExactActivePolicyUsage(
        policyId: String,
        contentRevision: Long,
        artifactSha256: String,
        scopeKind: String,
        scopeId: String,
        usedAtMs: Long,
    ): Int

    @Query(
        "UPDATE learning_policies SET task_signature = :taskSignature, policy_type = :policyType, " +
            "trigger_summary = :triggerSummary, procedure_summary = :procedureSummary, " +
            "verification_summary = :verificationSummary, boundary_summary = :boundarySummary, " +
            "failure_mode_summary = :failureModeSummary, state_version = state_version + 1, " +
            "content_revision = :newContentRevision, " +
            "artifact_sha256 = :newArtifactSha256, compiler_abi = :compilerAbi, " +
            "status = :status, source_valid = :sourceValid, schema_valid = :schemaValid, " +
            "applicable_tool_schemas_wire = :applicableToolSchemasWire, " +
            "applicable_model_identity_wire = :applicableModelIdentityWire, " +
            "applicable_provider_identity_wire = :applicableProviderIdentityWire, " +
            "applicable_template_identity = :applicableTemplateIdentity, " +
            "applicable_configuration_identity = :applicableConfigurationIdentity, " +
            "applicable_configuration_generation = :applicableConfigurationGeneration, " +
            "applicable_capability_digest = :applicableCapabilityDigest, " +
            "applicable_authority_digest = :applicableAuthorityDigest, " +
            "stale_reason = :staleReason, " +
            "distinct_episode_support = :distinctEpisodeSupport, " +
            "positive_episode_count = :positiveEpisodeCount, " +
            "negative_episode_count = :negativeEpisodeCount, confidence = :confidence, " +
            "updated_at_ms = :updatedAtMs WHERE id = :policyId " +
            "AND state_version = :expectedStateVersion " +
            "AND content_revision = :expectedContentRevision " +
            "AND artifact_sha256 = :expectedArtifactSha256 " +
            "AND applicable_tool_schemas_wire = :expectedApplicableToolSchemasWire " +
            "AND applicable_model_identity_wire = :expectedApplicableModelIdentityWire " +
            "AND applicable_provider_identity_wire = :expectedApplicableProviderIdentityWire " +
            "AND applicable_template_identity IS :expectedApplicableTemplateIdentity " +
            "AND applicable_configuration_identity IS :expectedApplicableConfigurationIdentity " +
            "AND applicable_configuration_generation IS :expectedApplicableConfigurationGeneration " +
            "AND applicable_capability_digest IS :expectedApplicableCapabilityDigest " +
            "AND applicable_authority_digest IS :expectedApplicableAuthorityDigest " +
            "AND usage_count = 0 AND last_used_at_ms IS NULL " +
            "AND observed_utility_delta IS NULL AND utility_uncertainty IS NULL " +
            "AND updated_at_ms <= :updatedAtMs",
    )
    suspend fun updatePolicyIfCurrent(
        policyId: String,
        expectedStateVersion: Long,
        expectedContentRevision: Long,
        expectedArtifactSha256: String,
        expectedApplicableToolSchemasWire: String,
        expectedApplicableModelIdentityWire: String,
        expectedApplicableProviderIdentityWire: String,
        expectedApplicableTemplateIdentity: String?,
        expectedApplicableConfigurationIdentity: String?,
        expectedApplicableConfigurationGeneration: Long?,
        expectedApplicableCapabilityDigest: String?,
        expectedApplicableAuthorityDigest: String?,
        taskSignature: String,
        policyType: String,
        triggerSummary: String,
        procedureSummary: String,
        verificationSummary: String,
        boundarySummary: String,
        failureModeSummary: String,
        newContentRevision: Long,
        newArtifactSha256: String,
        compilerAbi: String,
        status: String,
        sourceValid: Boolean,
        schemaValid: Boolean,
        applicableToolSchemasWire: String,
        applicableModelIdentityWire: String,
        applicableProviderIdentityWire: String,
        applicableTemplateIdentity: String?,
        applicableConfigurationIdentity: String?,
        applicableConfigurationGeneration: Long?,
        applicableCapabilityDigest: String?,
        applicableAuthorityDigest: String?,
        staleReason: String?,
        distinctEpisodeSupport: Long,
        positiveEpisodeCount: Long,
        negativeEpisodeCount: Long,
        confidence: Double,
        updatedAtMs: Long,
    ): Int

    /**
     * Curator-only exact head CAS. The runtime re-reads and validates the full before/after
     * documents in the same LearningDB transaction; this SQL additionally fences the canonical
     * scope, lifecycle/content revisions, artifact, status and read clock at the write boundary.
     */
    @Query(
        "UPDATE learning_policies SET trigger_summary = :triggerSummary, " +
            "procedure_summary = :procedureSummary, verification_summary = :verificationSummary, " +
            "boundary_summary = :boundarySummary, failure_mode_summary = :failureModeSummary, " +
            "state_version = :newStateVersion, content_revision = :newContentRevision, " +
            "artifact_sha256 = :newArtifactSha256, status = :newStatus, " +
            "applicable_tool_schemas_wire = :newApplicableToolSchemasWire, stale_reason = NULL, " +
            "usage_count = :newUsageCount, last_used_at_ms = :newLastUsedAtMs, " +
            "observed_utility_delta = :newObservedUtilityDelta, " +
            "utility_uncertainty = :newUtilityUncertainty, updated_at_ms = :updatedAtMs " +
            "WHERE id = :policyId AND scope_kind = :expectedScopeKind " +
            "AND scope_id = :expectedScopeId AND state_version = :expectedStateVersion " +
            "AND content_revision = :expectedContentRevision " +
            "AND artifact_sha256 = :expectedArtifactSha256 AND status = :expectedStatus " +
            "AND applicable_model_identity_wire = :expectedApplicableModelIdentityWire " +
            "AND applicable_provider_identity_wire = :expectedApplicableProviderIdentityWire " +
            "AND applicable_template_identity = :expectedApplicableTemplateIdentity " +
            "AND applicable_configuration_identity = :expectedApplicableConfigurationIdentity " +
            "AND applicable_configuration_generation = :expectedApplicableConfigurationGeneration " +
            "AND applicable_capability_digest IS :expectedApplicableCapabilityDigest " +
            "AND applicable_authority_digest IS :expectedApplicableAuthorityDigest " +
            "AND source_valid = 1 AND schema_valid = 1 " +
            "AND updated_at_ms = :expectedUpdatedAtMs " +
            "AND :expectedStateVersion < 9223372036854775807 " +
            "AND :newStateVersion = :expectedStateVersion + 1 " +
            "AND (:newContentRevision = :expectedContentRevision OR " +
            ":newContentRevision = :expectedContentRevision + 1) " +
            "AND :updatedAtMs >= :expectedUpdatedAtMs",
    )
    suspend fun updateCuratorPolicyHeadIfExact(
        policyId: String,
        expectedScopeKind: String,
        expectedScopeId: String,
        expectedStateVersion: Long,
        expectedContentRevision: Long,
        expectedArtifactSha256: String,
        expectedStatus: String,
        expectedUpdatedAtMs: Long,
        expectedApplicableModelIdentityWire: String,
        expectedApplicableProviderIdentityWire: String,
        expectedApplicableTemplateIdentity: String,
        expectedApplicableConfigurationIdentity: String,
        expectedApplicableConfigurationGeneration: Long,
        expectedApplicableCapabilityDigest: String?,
        expectedApplicableAuthorityDigest: String?,
        triggerSummary: String,
        procedureSummary: String,
        verificationSummary: String,
        boundarySummary: String,
        failureModeSummary: String,
        newStateVersion: Long,
        newContentRevision: Long,
        newArtifactSha256: String,
        newStatus: String,
        newApplicableToolSchemasWire: String,
        newUsageCount: Long,
        newLastUsedAtMs: Long?,
        newObservedUtilityDelta: Double?,
        newUtilityUncertainty: Double?,
        updatedAtMs: Long,
    ): Int

    /** User-selected historic content restore; evidence/provenance stay put, old usage does not. */
    @Query(
        "UPDATE learning_policies SET task_signature = :taskSignature, " +
            "policy_type = :policyType, trigger_summary = :triggerSummary, " +
            "procedure_summary = :procedureSummary, verification_summary = :verificationSummary, " +
            "boundary_summary = :boundarySummary, failure_mode_summary = :failureModeSummary, " +
            "state_version = state_version + 1, content_revision = content_revision + 1, " +
            "artifact_sha256 = :restoredArtifactSha256, status = 'SHADOW', " +
            "applicable_tool_schemas_wire = :applicableToolSchemasWire, " +
            "applicable_model_identity_wire = :applicableModelIdentityWire, " +
            "applicable_provider_identity_wire = :applicableProviderIdentityWire, " +
            "applicable_template_identity = :applicableTemplateIdentity, " +
            "applicable_configuration_identity = :applicableConfigurationIdentity, " +
            "applicable_configuration_generation = :applicableConfigurationGeneration, " +
            "applicable_capability_digest = :applicableCapabilityDigest, " +
            "applicable_authority_digest = :applicableAuthorityDigest, " +
            "stale_reason = NULL, usage_count = 0, last_used_at_ms = NULL, " +
            "observed_utility_delta = NULL, utility_uncertainty = NULL, updated_at_ms = :updatedAtMs " +
            "WHERE id = :policyId AND state_version = :expectedStateVersion " +
            "AND content_revision = :expectedContentRevision " +
            "AND artifact_sha256 = :expectedArtifactSha256 AND status = 'ARCHIVED' " +
            "AND source_valid = 1 AND schema_valid = 1 AND updated_at_ms <= :updatedAtMs",
    )
    suspend fun restoreHistoricPolicyContentIfCurrent(
        policyId: String,
        expectedStateVersion: Long,
        expectedContentRevision: Long,
        expectedArtifactSha256: String,
        taskSignature: String,
        policyType: String,
        triggerSummary: String,
        procedureSummary: String,
        verificationSummary: String,
        boundarySummary: String,
        failureModeSummary: String,
        restoredArtifactSha256: String,
        applicableToolSchemasWire: String,
        applicableModelIdentityWire: String,
        applicableProviderIdentityWire: String,
        applicableTemplateIdentity: String,
        applicableConfigurationIdentity: String,
        applicableConfigurationGeneration: Long,
        applicableCapabilityDigest: String?,
        applicableAuthorityDigest: String?,
        updatedAtMs: Long,
    ): Int

    /**
     * Lifecycle-only CAS. Policy content, content revision, evidence, usage and utility are never
     * rewritten by a status transition; the exact content tuple remains an immutable fence.
     */
    @Query(
        "UPDATE learning_policies SET state_version = state_version + 1, status = :status, " +
            "source_valid = :sourceValid, schema_valid = :schemaValid, " +
            "stale_reason = :staleReason, updated_at_ms = :updatedAtMs " +
            "WHERE id = :policyId AND state_version = :expectedStateVersion " +
            "AND content_revision = :expectedContentRevision " +
            "AND artifact_sha256 = :expectedArtifactSha256 " +
            "AND applicable_tool_schemas_wire = :expectedApplicableToolSchemasWire " +
            "AND applicable_model_identity_wire = :expectedApplicableModelIdentityWire " +
            "AND applicable_provider_identity_wire = :expectedApplicableProviderIdentityWire " +
            "AND applicable_template_identity IS :expectedApplicableTemplateIdentity " +
            "AND applicable_configuration_identity IS :expectedApplicableConfigurationIdentity " +
            "AND applicable_configuration_generation IS :expectedApplicableConfigurationGeneration " +
            "AND applicable_capability_digest IS :expectedApplicableCapabilityDigest " +
            "AND applicable_authority_digest IS :expectedApplicableAuthorityDigest " +
            "AND updated_at_ms <= :updatedAtMs",
    )
    suspend fun updatePolicyLifecycleIfCurrent(
        policyId: String,
        expectedStateVersion: Long,
        expectedContentRevision: Long,
        expectedArtifactSha256: String,
        expectedApplicableToolSchemasWire: String,
        expectedApplicableModelIdentityWire: String,
        expectedApplicableProviderIdentityWire: String,
        expectedApplicableTemplateIdentity: String?,
        expectedApplicableConfigurationIdentity: String?,
        expectedApplicableConfigurationGeneration: Long?,
        expectedApplicableCapabilityDigest: String?,
        expectedApplicableAuthorityDigest: String?,
        status: String,
        sourceValid: Boolean,
        schemaValid: Boolean,
        staleReason: String?,
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

    /** Bounded exact evidence capsules inherited by one Curator output Policy. */
    @Query(
        "SELECT * FROM policy_evidence WHERE policy_id = :policyId " +
            "ORDER BY episode_id ASC LIMIT CASE WHEN :limit BETWEEN 1 AND 257 THEN :limit ELSE 0 END",
    )
    suspend fun listEvidenceForCurator(
        policyId: String,
        limit: Int,
    ): List<PolicyEvidenceEntity>

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
        "SELECT p.id FROM learning_policies p WHERE (" +
            "(p.source_valid = 1 AND EXISTS (SELECT 1 FROM policy_evidence pe " +
            "JOIN learning_episodes e ON e.id = pe.episode_id " +
            "LEFT JOIN learning_episode_lessons l ON l.episode_id = pe.episode_id " +
            "AND l.lesson_version = pe.lesson_version " +
            "LEFT JOIN learning_source_validity s ON s.stream_id = e.stream_id " +
            "AND s.replay_generation = e.replay_generation " +
            "AND s.scope_kind = e.scope_kind AND s.scope_id = e.scope_id " +
            "AND s.source_type = pe.source_type AND s.source_id = pe.source_id " +
            "AND s.source_revision = pe.source_revision WHERE pe.policy_id = p.id " +
            "AND NOT (" + VALID_POLICY_EVIDENCE_PREDICATE_FOR_E_ALIAS + "))) OR " +
            "(p.source_valid = 0 AND (p.trigger_summary != 'SOURCE_REDACTED' OR " +
            "p.procedure_summary != 'SOURCE_REDACTED' OR " +
            "p.verification_summary != 'SOURCE_REDACTED' OR " +
            "p.boundary_summary != 'SOURCE_REDACTED' OR " +
            "p.failure_mode_summary != 'SOURCE_REDACTED' OR " +
            "p.task_signature NOT LIKE 'policy-source-redacted-v1:%'))) " +
            "ORDER BY p.id ASC LIMIT :limit",
    )
    suspend fun listLivePoliciesWithInvalidEvidence(limit: Int): List<String>

    /**
     * Exact content-changing privacy CAS. Every contributing-source invalidation makes the entire
     * Policy non-retrievable and replaces all prose; surviving evidence may update only counters.
     */
    @Query(REDACT_POLICY_SOURCE_SQL)
    suspend fun redactPolicySourceIfCurrent(
        policyId: String,
        expectedStateVersion: Long,
        expectedContentRevision: Long,
        expectedArtifactSha256: String,
        expectedApplicableToolSchemasWire: String,
        expectedApplicableModelIdentityWire: String,
        expectedApplicableProviderIdentityWire: String,
        expectedApplicableTemplateIdentity: String?,
        expectedApplicableConfigurationIdentity: String?,
        expectedApplicableConfigurationGeneration: Long?,
        expectedApplicableCapabilityDigest: String?,
        expectedApplicableAuthorityDigest: String?,
        newContentRevision: Long,
        redactedTaskSignature: String,
        redactedArtifactSha256: String,
        remainingSupport: Long,
        remainingPositive: Long,
        remainingNegative: Long,
        remainingConfidence: Double,
        updatedAtMs: Long,
    ): Int

    /** Removes every historic source-derived snapshot while retaining digest/enums columns. */
    @Query(
        "UPDATE policy_revisions SET " +
            "before_snapshot = CASE WHEN before_snapshot IS NULL THEN NULL " +
            "ELSE 'policy-source-history-redacted-v1' END, " +
            "after_snapshot = 'policy-source-history-redacted-v1' " +
            "WHERE policy_id = :policyId AND (" +
            "(before_snapshot IS NOT NULL AND " +
            "before_snapshot != 'policy-source-history-redacted-v1') OR " +
            "after_snapshot != 'policy-source-history-redacted-v1')",
    )
    suspend fun redactPolicyRevisionSnapshots(policyId: String): Int

    /**
     * Returns only never-user-reviewed dormant candidates for an exact canonical lifecycle CAS.
     *
     * Maintenance must never raw-delete a Policy. In particular ACTIVE/PROBATION/SUSPENDED,
     * stale and archived rows may be the target of durable AppDatabase grant/review history. A
     * restored SHADOW row is also protected by its USER/GRANT_BINDER audit revision.
     */
    @Query(
        "SELECT p.* FROM learning_policies p WHERE p.status IN ('CANDIDATE', 'SHADOW') " +
            "AND p.updated_at_ms < :cutoffMs AND p.usage_count = 0 " +
            "AND p.last_used_at_ms IS NULL AND NOT EXISTS (" +
            "SELECT 1 FROM policy_revisions r WHERE r.policy_id = p.id AND (" +
            "r.actor IN ('USER', 'GRANT_BINDER') OR r.reason_code IN (" +
            "'USER_APPROVED_CONTEXTUAL_ADVICE', 'USER_RESTORED_REVISION', " +
            "'USER_SUSPENDED', 'USER_ARCHIVED'))) " +
            "ORDER BY p.updated_at_ms ASC, p.id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 128 THEN :limit ELSE 0 END",
    )
    suspend fun listExpiredUnreviewedCandidates(
        cutoffMs: Long,
        limit: Int,
    ): List<LearningPolicyEntity>

    @Query(
        "DELETE FROM policy_revisions WHERE rowid IN (SELECT r.rowid FROM policy_revisions r " +
            "JOIN learning_policies p ON p.id = r.policy_id " +
            "WHERE r.created_at_ms < :cutoffMs AND r.revision < p.state_version " +
            "AND r.actor NOT IN ('USER', 'GRANT_BINDER') " +
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

/** Minimal P2 final-applicability read; it never exposes Policy text or provider credentials. */
data class LearningPolicyApplicabilityProjection(
    @ColumnInfo(name = "policy_id") val policyId: String,
    @ColumnInfo(name = "content_revision") val contentRevision: Long,
    @ColumnInfo(name = "artifact_sha256") val artifactSha256: String,
    val status: String,
    @ColumnInfo(name = "schema_valid") val schemaValid: Boolean,
    @ColumnInfo(name = "applicable_tool_schemas_wire") val applicableToolSchemasWire: String,
    @ColumnInfo(name = "applicable_model_identity_wire") val applicableModelIdentityWire: String,
    @ColumnInfo(name = "applicable_provider_identity_wire") val applicableProviderIdentityWire: String,
    @ColumnInfo(name = "applicable_template_identity") val applicableTemplateIdentity: String?,
    @ColumnInfo(name = "applicable_configuration_identity") val applicableConfigurationIdentity: String?,
    @ColumnInfo(name = "applicable_configuration_generation") val applicableConfigurationGeneration: Long?,
    @ColumnInfo(name = "applicable_capability_digest") val applicableCapabilityDigest: String?,
    @ColumnInfo(name = "applicable_authority_digest") val applicableAuthorityDigest: String?,
) {
    init {
        requireLearningStorageId(policyId, "Policy applicability projection ID")
        require(contentRevision > 0L) { "Invalid Policy applicability content revision" }
        requireSha256(artifactSha256, "Policy applicability artifact")
        require(StoredLearningPolicyStatus.entries.any { it.name == status }) {
            "Invalid Policy applicability status"
        }
        val tools = PolicyApplicabilityWire.decodeToolSchemasOrNull(applicableToolSchemasWire)
        if (tools == null) {
            require(!schemaValid && status !in FINAL_POLICY_RETRIEVABLE_STATUSES) {
                "Unproven Policy applicability projection is not fail-closed"
            }
        }
        val model = PolicyApplicabilityWire.decodeIdentity(applicableModelIdentityWire)
        val provider = PolicyApplicabilityWire.decodeIdentity(applicableProviderIdentityWire)
        applicableTemplateIdentity?.let {
            requireSha256(it, "Policy applicability template")
        }
        require((applicableConfigurationIdentity == null) ==
            (applicableConfigurationGeneration == null))
        applicableConfigurationIdentity?.let {
            requireSha256(it, "Policy applicability configuration")
        }
        applicableConfigurationGeneration?.let { require(it > 0L) }
        applicableCapabilityDigest?.let { requireSha256(it, "Policy capability baseline") }
        applicableAuthorityDigest?.let { requireSha256(it, "Policy authority baseline") }
        if (status in FINAL_POLICY_RETRIEVABLE_STATUSES) {
            require(model is PolicyIdentityApplicability.Exact &&
                provider is PolicyIdentityApplicability.Exact &&
                applicableTemplateIdentity != null &&
                applicableConfigurationIdentity != null
            ) { "Retrievable projection has unproven applicability" }
        }
    }

    /** Pure final-binding filter; callers pass only non-secret frozen provider/model/schema IDs. */
    fun matchesFinalBinding(
        modelIdentity: String,
        providerIdentity: String,
        templateIdentity: String,
        configurationIdentity: String,
        configurationGeneration: Long,
        availableToolSchemas: Set<String>,
        capabilityDigest: String? = null,
        authorityDigest: String? = null,
    ): Boolean {
        requireLearningIdentity(modelIdentity, "final Policy model identity")
        requireLearningIdentity(providerIdentity, "final Policy provider identity")
        requireSha256(templateIdentity, "final Policy template identity")
        requireSha256(configurationIdentity, "final Policy configuration identity")
        require(configurationGeneration > 0L)
        capabilityDigest?.let { requireSha256(it, "final Policy capability baseline") }
        authorityDigest?.let { requireSha256(it, "final Policy authority baseline") }
        require(availableToolSchemas.size <= MAX_FINAL_POLICY_TOOL_SCHEMAS) {
            "Unbounded final Policy tool schema set"
        }
        require(availableToolSchemas.all(FINAL_POLICY_LOWER_SHA256::matches)) {
            "Invalid final Policy tool schema"
        }
        if (status != StoredLearningPolicyStatus.ACTIVE.name || !schemaValid) return false
        val requiredTools = PolicyApplicabilityWire.decodeToolSchemasOrNull(
            applicableToolSchemasWire,
        ) ?: return false
        return requiredTools.all { it in availableToolSchemas } &&
            applicableTemplateIdentity == templateIdentity &&
            applicableConfigurationIdentity == configurationIdentity &&
            applicableConfigurationGeneration == configurationGeneration &&
            applicableCapabilityDigest == capabilityDigest &&
            applicableAuthorityDigest == authorityDigest &&
            PolicyApplicabilityWire.decodeIdentity(applicableModelIdentityWire)
                .matches(modelIdentity) &&
            PolicyApplicabilityWire.decodeIdentity(applicableProviderIdentityWire)
                .matches(providerIdentity)
    }
}

/** Avoids a second DB read when an exact granted Policy row has already been materialized. */
fun LearningPolicyEntity.toApplicabilityProjection(): LearningPolicyApplicabilityProjection =
    LearningPolicyApplicabilityProjection(
        policyId = id,
        contentRevision = contentRevision,
        artifactSha256 = artifactSha256,
        status = status,
        schemaValid = schemaValid,
        applicableToolSchemasWire = applicableToolSchemasWire,
        applicableModelIdentityWire = applicableModelIdentityWire,
        applicableProviderIdentityWire = applicableProviderIdentityWire,
        applicableTemplateIdentity = applicableTemplateIdentity,
        applicableConfigurationIdentity = applicableConfigurationIdentity,
        applicableConfigurationGeneration = applicableConfigurationGeneration,
        applicableCapabilityDigest = applicableCapabilityDigest,
        applicableAuthorityDigest = applicableAuthorityDigest,
    )

private fun PolicyIdentityApplicability.matches(actual: String): Boolean = when (this) {
    PolicyIdentityApplicability.Any -> false
    is PolicyIdentityApplicability.Exact -> identity == actual
}

private const val MAX_FINAL_POLICY_TOOL_SCHEMAS = 256
private val FINAL_POLICY_LOWER_SHA256 = Regex("[0-9a-f]{64}")
private val FINAL_POLICY_RETRIEVABLE_STATUSES = setOf(
    StoredLearningPolicyStatus.CANDIDATE.name,
    StoredLearningPolicyStatus.SHADOW.name,
    StoredLearningPolicyStatus.PROBATION.name,
    StoredLearningPolicyStatus.ACTIVE.name,
)

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
        "OR all_sv.state != 'VALID' OR all_sv.integrity_sha256 IS NULL)) " +
        "AND EXISTS (SELECT 1 FROM policy_reward_evidence pre " +
        "JOIN learning_reward_signals rs ON rs.episode_id = pre.episode_id " +
        "AND rs.id = pre.reward_signal_id LEFT JOIN learning_source_validity rsv " +
        "ON rsv.stream_id = ep.stream_id AND rsv.replay_generation = ep.replay_generation " +
        "AND rsv.scope_kind = ep.scope_kind AND rsv.scope_id = ep.scope_id " +
        "AND rsv.source_type = pre.source_type AND rsv.source_id = pre.source_id " +
        "AND rsv.source_revision = pre.source_revision WHERE pre.policy_id = pe.policy_id " +
        "AND pre.episode_id = pe.episode_id AND rsv.state = 'VALID' " +
        "AND rsv.integrity_sha256 = pre.source_integrity_sha256 " +
        "AND rs.source_type = pre.source_type AND rs.source_id = pre.source_id " +
        "AND rs.source_revision = pre.source_revision " +
        "AND rs.source_integrity_sha256 = pre.source_integrity_sha256) " +
        "AND NOT EXISTS (SELECT 1 FROM policy_reward_evidence bad_pre " +
        "LEFT JOIN learning_reward_signals bad_rs ON bad_rs.episode_id = bad_pre.episode_id " +
        "AND bad_rs.id = bad_pre.reward_signal_id " +
        "LEFT JOIN learning_source_validity bad_rsv ON bad_rsv.stream_id = ep.stream_id " +
        "AND bad_rsv.replay_generation = ep.replay_generation " +
        "AND bad_rsv.scope_kind = ep.scope_kind AND bad_rsv.scope_id = ep.scope_id " +
        "AND bad_rsv.source_type = bad_pre.source_type AND bad_rsv.source_id = bad_pre.source_id " +
        "AND bad_rsv.source_revision = bad_pre.source_revision " +
        "WHERE bad_pre.policy_id = pe.policy_id AND bad_pre.episode_id = pe.episode_id " +
        "AND (bad_rs.id IS NULL OR bad_rs.source_type != bad_pre.source_type " +
        "OR bad_rs.source_id != bad_pre.source_id " +
        "OR bad_rs.source_revision != bad_pre.source_revision " +
        "OR bad_rs.source_integrity_sha256 != bad_pre.source_integrity_sha256 " +
        "OR bad_rsv.source_id IS NULL OR bad_rsv.state != 'VALID' " +
        "OR bad_rsv.integrity_sha256 IS NULL " +
        "OR bad_rsv.integrity_sha256 != bad_pre.source_integrity_sha256))"

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
        "OR all_s.state != 'VALID' OR all_s.integrity_sha256 IS NULL)) " +
        "AND EXISTS (SELECT 1 FROM policy_reward_evidence pre " +
        "JOIN learning_reward_signals rs ON rs.episode_id = pre.episode_id " +
        "AND rs.id = pre.reward_signal_id LEFT JOIN learning_source_validity rsv " +
        "ON rsv.stream_id = e.stream_id AND rsv.replay_generation = e.replay_generation " +
        "AND rsv.scope_kind = e.scope_kind AND rsv.scope_id = e.scope_id " +
        "AND rsv.source_type = pre.source_type AND rsv.source_id = pre.source_id " +
        "AND rsv.source_revision = pre.source_revision WHERE pre.policy_id = pe.policy_id " +
        "AND pre.episode_id = pe.episode_id AND rsv.state = 'VALID' " +
        "AND rsv.integrity_sha256 = pre.source_integrity_sha256 " +
        "AND rs.source_type = pre.source_type AND rs.source_id = pre.source_id " +
        "AND rs.source_revision = pre.source_revision " +
        "AND rs.source_integrity_sha256 = pre.source_integrity_sha256) " +
        "AND NOT EXISTS (SELECT 1 FROM policy_reward_evidence bad_pre " +
        "LEFT JOIN learning_reward_signals bad_rs ON bad_rs.episode_id = bad_pre.episode_id " +
        "AND bad_rs.id = bad_pre.reward_signal_id " +
        "LEFT JOIN learning_source_validity bad_rsv ON bad_rsv.stream_id = e.stream_id " +
        "AND bad_rsv.replay_generation = e.replay_generation " +
        "AND bad_rsv.scope_kind = e.scope_kind AND bad_rsv.scope_id = e.scope_id " +
        "AND bad_rsv.source_type = bad_pre.source_type AND bad_rsv.source_id = bad_pre.source_id " +
        "AND bad_rsv.source_revision = bad_pre.source_revision " +
        "WHERE bad_pre.policy_id = pe.policy_id AND bad_pre.episode_id = pe.episode_id " +
        "AND (bad_rs.id IS NULL OR bad_rs.source_type != bad_pre.source_type " +
        "OR bad_rs.source_id != bad_pre.source_id " +
        "OR bad_rs.source_revision != bad_pre.source_revision " +
        "OR bad_rs.source_integrity_sha256 != bad_pre.source_integrity_sha256 " +
        "OR bad_rsv.source_id IS NULL OR bad_rsv.state != 'VALID' " +
        "OR bad_rsv.integrity_sha256 IS NULL " +
        "OR bad_rsv.integrity_sha256 != bad_pre.source_integrity_sha256))"

internal const val REDACT_POLICY_SOURCE_SQL =
    "UPDATE learning_policies SET " +
        "task_signature = :redactedTaskSignature, " +
        "trigger_summary = 'SOURCE_REDACTED', procedure_summary = 'SOURCE_REDACTED', " +
        "verification_summary = 'SOURCE_REDACTED', boundary_summary = 'SOURCE_REDACTED', " +
        "failure_mode_summary = 'SOURCE_REDACTED', state_version = state_version + 1, " +
        "content_revision = :newContentRevision, artifact_sha256 = :redactedArtifactSha256, " +
        "status = 'STALE_SOURCE', source_valid = 0, stale_reason = 'SOURCE_INVALIDATED', " +
        "distinct_episode_support = :remainingSupport, " +
        "positive_episode_count = :remainingPositive, " +
        "negative_episode_count = :remainingNegative, confidence = :remainingConfidence, " +
        "updated_at_ms = :updatedAtMs WHERE id = :policyId " +
        "AND state_version = :expectedStateVersion " +
        "AND content_revision = :expectedContentRevision " +
        "AND artifact_sha256 = :expectedArtifactSha256 " +
        "AND applicable_tool_schemas_wire = :expectedApplicableToolSchemasWire " +
        "AND applicable_model_identity_wire = :expectedApplicableModelIdentityWire " +
        "AND applicable_provider_identity_wire = :expectedApplicableProviderIdentityWire " +
        "AND applicable_template_identity IS :expectedApplicableTemplateIdentity " +
        "AND applicable_configuration_identity IS :expectedApplicableConfigurationIdentity " +
        "AND applicable_configuration_generation IS :expectedApplicableConfigurationGeneration " +
        "AND applicable_capability_digest IS :expectedApplicableCapabilityDigest " +
        "AND applicable_authority_digest IS :expectedApplicableAuthorityDigest " +
        "AND (source_valid = 1 OR trigger_summary != 'SOURCE_REDACTED' OR " +
        "procedure_summary != 'SOURCE_REDACTED' OR verification_summary != 'SOURCE_REDACTED' OR " +
        "boundary_summary != 'SOURCE_REDACTED' OR failure_mode_summary != 'SOURCE_REDACTED' OR " +
        "task_signature NOT LIKE 'policy-source-redacted-v1:%') " +
        "AND :expectedStateVersion < 9223372036854775807 " +
        "AND :expectedContentRevision < 9223372036854775807 " +
        "AND :newContentRevision = :expectedContentRevision + 1 " +
        "AND length(:redactedTaskSignature) = 90 " +
        "AND substr(:redactedTaskSignature, 1, 26) = 'policy-source-redacted-v1:' " +
        "AND :updatedAtMs >= updated_at_ms"
