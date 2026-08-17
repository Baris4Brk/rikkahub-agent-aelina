package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Canonical derived Policy artifact. Durable user authority remains in AppDatabase grants. */
@Entity(
    tableName = "learning_policies",
    indices = [
        Index(
            value = [
                "scope_kind",
                "scope_id",
                "status",
                "source_valid",
                "schema_valid",
                "task_signature",
                "updated_at_ms",
            ],
        ),
        Index(value = ["scope_kind", "scope_id", "task_signature", "artifact_sha256"], unique = true),
        Index(value = ["status", "updated_at_ms"]),
    ],
)
data class LearningPolicyEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "scope_kind")
    val scopeKind: String,
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    @ColumnInfo(name = "task_signature")
    val taskSignature: String,
    @ColumnInfo(name = "policy_type")
    val policyType: String,
    @ColumnInfo(name = "trigger_summary")
    val triggerSummary: String,
    @ColumnInfo(name = "procedure_summary")
    val procedureSummary: String,
    @ColumnInfo(name = "verification_summary")
    val verificationSummary: String,
    @ColumnInfo(name = "boundary_summary")
    val boundarySummary: String,
    @ColumnInfo(name = "failure_mode_summary")
    val failureModeSummary: String,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    /**
     * Revision of the canonical Policy content, independent from lifecycle/evidence mutations.
     * Durable approval grants bind this value together with [artifactSha256]; changing status must
     * never invalidate an otherwise exact grant merely by incrementing [stateVersion].
     */
    @ColumnInfo(name = "content_revision")
    val contentRevision: Long,
    @ColumnInfo(name = "artifact_sha256")
    val artifactSha256: String,
    @ColumnInfo(name = "compiler_abi")
    val compilerAbi: String,
    val status: String,
    @ColumnInfo(name = "source_valid")
    val sourceValid: Boolean,
    @ColumnInfo(name = "schema_valid")
    val schemaValid: Boolean,
    /**
     * Canonical, lossless applicability captured from PolicyCandidateDraft.applicableToolSchemas.
     * `EXACT_V1:` followed by a sorted comma-separated SHA-256 set is the only live encoding.
     * `UNPROVEN_V5` exists solely for fail-closed pre-v6 rows and is never written by P2.
     */
    @ColumnInfo(name = "applicable_tool_schemas_wire")
    val applicableToolSchemasWire: String,
    /** Exact final-provider/model applicability. ANY_V1 is migration/audit-only and never live. */
    @ColumnInfo(name = "applicable_model_identity_wire")
    val applicableModelIdentityWire: String,
    /** Exact final-provider/model applicability. ANY_V1 is migration/audit-only and never live. */
    @ColumnInfo(name = "applicable_provider_identity_wire")
    val applicableProviderIdentityWire: String,
    @ColumnInfo(name = "applicable_template_identity")
    val applicableTemplateIdentity: String?,
    @ColumnInfo(name = "applicable_configuration_identity")
    val applicableConfigurationIdentity: String?,
    @ColumnInfo(name = "applicable_configuration_generation")
    val applicableConfigurationGeneration: Long?,
    @ColumnInfo(name = "applicable_capability_digest")
    val applicableCapabilityDigest: String?,
    @ColumnInfo(name = "applicable_authority_digest")
    val applicableAuthorityDigest: String?,
    @ColumnInfo(name = "stale_reason")
    val staleReason: String?,
    @ColumnInfo(name = "distinct_episode_support")
    val distinctEpisodeSupport: Long,
    @ColumnInfo(name = "positive_episode_count")
    val positiveEpisodeCount: Long,
    @ColumnInfo(name = "negative_episode_count")
    val negativeEpisodeCount: Long,
    @ColumnInfo(name = "usage_count")
    val usageCount: Long,
    val confidence: Double,
    @ColumnInfo(name = "observed_utility_delta")
    val observedUtilityDelta: Double?,
    @ColumnInfo(name = "utility_uncertainty")
    val utilityUncertainty: Double?,
    @ColumnInfo(name = "producer_model_identity")
    val producerModelIdentity: String,
    @ColumnInfo(name = "producer_provider_identity")
    val producerProviderIdentity: String,
    @ColumnInfo(name = "producer_provider_kind")
    val producerProviderKind: String,
    @ColumnInfo(name = "producer_configuration_identity")
    val producerConfigurationIdentity: String,
    @ColumnInfo(name = "producer_config_generation")
    val producerConfigGeneration: Long,
    @ColumnInfo(name = "producer_prompt_identity")
    val producerPromptIdentity: String,
    @ColumnInfo(name = "producer_template_identity")
    val producerTemplateIdentity: String,
    @ColumnInfo(name = "producer_schema_identity")
    val producerSchemaIdentity: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "last_used_at_ms")
    val lastUsedAtMs: Long?,
) {
    init {
        requireLearningStorageId(id, "policy ID")
        requireLearningScope(scopeKind, scopeId)
        requireLearningIdentity(taskSignature, "policy task signature")
        requireLearningCode(policyType, "policy type")
        requireBoundedRedactedText(triggerSummary, "policy trigger")
        requireBoundedRedactedText(procedureSummary, "policy procedure")
        requireBoundedRedactedText(verificationSummary, "policy verification")
        requireBoundedRedactedText(boundarySummary, "policy boundary")
        requireBoundedRedactedText(failureModeSummary, "policy failure mode")
        require(stateVersion > 0L) { "Invalid policy state version" }
        require(contentRevision > 0L) { "Invalid policy content revision" }
        requireSha256(artifactSha256, "policy artifact")
        requireLearningIdentity(compilerAbi, "policy compiler ABI")
        require(StoredLearningPolicyStatus.entries.any { it.name == status }) {
            "Invalid policy status"
        }
        val reasonRequired = status in setOf(
            StoredLearningPolicyStatus.SUSPENDED.name,
            StoredLearningPolicyStatus.SUSPENDED_PENDING_REVIEW.name,
            StoredLearningPolicyStatus.STALE_SCHEMA.name,
            StoredLearningPolicyStatus.STALE_SOURCE.name,
            StoredLearningPolicyStatus.STALE_AUTHORITY.name,
        )
        require(reasonRequired == (staleReason != null)) {
            "Policy reason disagrees with status"
        }
        staleReason?.let { requireLearningCode(it, "policy stale reason") }
        if (status in setOf(
                StoredLearningPolicyStatus.CANDIDATE.name,
                StoredLearningPolicyStatus.SHADOW.name,
                StoredLearningPolicyStatus.PROBATION.name,
                StoredLearningPolicyStatus.ACTIVE.name,
            )
        ) {
            require(sourceValid && schemaValid) { "Retrievable policy is source/schema stale" }
        }
        if (status == StoredLearningPolicyStatus.STALE_SOURCE.name) {
            require(!sourceValid) { "STALE_SOURCE policy has a valid source" }
        }
        if (status == StoredLearningPolicyStatus.STALE_SCHEMA.name) {
            require(!schemaValid) { "STALE_SCHEMA policy has a valid schema" }
        }
        val decodedToolApplicability = PolicyApplicabilityWire.decodeToolSchemasOrNull(
            applicableToolSchemasWire,
        )
        if (decodedToolApplicability == null) {
            require(
                !schemaValid && status !in POLICY_SCHEMA_RETRIEVABLE_STATUSES
            ) { "Unproven legacy Policy applicability is not fail-closed" }
        }
        val modelApplicability = PolicyApplicabilityWire.decodeIdentity(applicableModelIdentityWire)
        val providerApplicability = PolicyApplicabilityWire.decodeIdentity(applicableProviderIdentityWire)
        applicableTemplateIdentity?.let {
            requireSha256(it, "Policy applicability template")
        }
        require((applicableConfigurationIdentity == null) ==
            (applicableConfigurationGeneration == null))
        applicableConfigurationIdentity?.let {
            requireSha256(it, "Policy applicability configuration")
        }
        applicableConfigurationGeneration?.let {
            require(it > 0L) { "Invalid Policy applicability configuration generation" }
        }
        applicableCapabilityDigest?.let { requireSha256(it, "Policy capability baseline") }
        applicableAuthorityDigest?.let { requireSha256(it, "Policy authority baseline") }
        if (status in POLICY_SCHEMA_RETRIEVABLE_STATUSES) {
            require(modelApplicability is PolicyIdentityApplicability.Exact &&
                providerApplicability is PolicyIdentityApplicability.Exact &&
                applicableTemplateIdentity != null &&
                applicableConfigurationIdentity != null &&
                applicableConfigurationGeneration != null
            ) { "Retrievable Policy has wildcard/unproven applicability" }
        }
        require(
            distinctEpisodeSupport >= 0L &&
                positiveEpisodeCount >= 0L &&
                negativeEpisodeCount >= 0L
        ) { "Negative policy evidence count" }
        require(usageCount >= 0L) { "Negative policy usage" }
        require(confidence.isFinite() && confidence in 0.0..1.0) { "Invalid policy confidence" }
        require((observedUtilityDelta == null) == (utilityUncertainty == null)) {
            "Policy utility and uncertainty must be recorded together"
        }
        observedUtilityDelta?.let { require(it.isFinite()) { "Invalid policy utility" } }
        utilityUncertainty?.let {
            require(it.isFinite() && it >= 0.0) { "Invalid policy utility uncertainty" }
        }
        listOf(
            producerModelIdentity,
            producerProviderIdentity,
            producerConfigurationIdentity,
            producerPromptIdentity,
            producerTemplateIdentity,
            producerSchemaIdentity,
        ).forEach { requireLearningIdentity(it, "policy producer identity") }
        require(producerProviderKind in setOf("local_litert", "remote")) {
            "Invalid policy provider kind"
        }
        requireSha256(producerModelIdentity, "policy model identity")
        requireSha256(producerProviderIdentity, "policy provider identity")
        requireSha256(producerConfigurationIdentity, "policy configuration identity")
        require(producerConfigGeneration >= 0L) { "Negative policy config generation" }
        require(createdAtMs >= 0L && updatedAtMs >= createdAtMs) { "Invalid policy clock" }
        require((usageCount == 0L) == (lastUsedAtMs == null)) {
            "Policy usage and last-used clock disagree"
        }
        lastUsedAtMs?.let { require(it in createdAtMs..updatedAtMs) { "Invalid last-used clock" } }
    }

    override fun toString(): String =
        "LearningPolicyEntity(status=$status, stateVersion=$stateVersion, " +
            "contentRevision=$contentRevision, support=$distinctEpisodeSupport, " +
            "text=<redacted>, ids=<redacted>)"
}

/** In-memory result of parsing an explicit model/provider applicability wire. */
sealed interface PolicyIdentityApplicability {
    data object Any : PolicyIdentityApplicability

    data class Exact(val identity: String) : PolicyIdentityApplicability {
        init {
            requireLearningIdentity(identity, "Policy applicability identity")
        }
    }
}

/**
 * Single canonical codec for durable Policy applicability. This deliberately avoids JSON parser
 * leniency: ordering, duplicates, casing and bounds are part of the storage contract.
 */
object PolicyApplicabilityWire {
    fun encodeToolSchemas(schemas: Set<String>): String {
        require(schemas.size <= MAX_POLICY_APPLICABLE_TOOL_SCHEMAS) {
            "Too many applicable Policy tool schemas"
        }
        require(schemas.all(LOWER_SHA256::matches)) { "Invalid applicable Policy tool schema" }
        return POLICY_TOOL_APPLICABILITY_EXACT_PREFIX + schemas.sorted().joinToString(",")
    }

    /** Returns null only for the v5 migration sentinel; malformed encodings are rejected. */
    fun decodeToolSchemasOrNull(wire: String): Set<String>? {
        if (wire == POLICY_TOOL_APPLICABILITY_UNPROVEN_V5) return null
        require(wire.startsWith(POLICY_TOOL_APPLICABILITY_EXACT_PREFIX)) {
            "Unknown Policy tool applicability wire"
        }
        val payload = wire.removePrefix(POLICY_TOOL_APPLICABILITY_EXACT_PREFIX)
        val ordered = if (payload.isEmpty()) emptyList() else payload.split(',')
        require(ordered.size <= MAX_POLICY_APPLICABLE_TOOL_SCHEMAS) {
            "Too many applicable Policy tool schemas"
        }
        require(ordered.all(LOWER_SHA256::matches)) { "Invalid applicable Policy tool schema" }
        require(ordered == ordered.sorted() && ordered.distinct().size == ordered.size) {
            "Non-canonical applicable Policy tool schema set"
        }
        val decoded = ordered.toCollection(linkedSetOf())
        require(encodeToolSchemas(decoded) == wire) { "Non-canonical Policy tool applicability wire" }
        return decoded
    }

    fun encodeExactIdentity(identity: String): String {
        requireLearningIdentity(identity, "Policy applicability identity")
        return POLICY_IDENTITY_APPLICABILITY_EXACT_PREFIX + identity
    }

    fun decodeIdentity(wire: String): PolicyIdentityApplicability = when {
        wire == POLICY_IDENTITY_APPLICABILITY_ANY -> PolicyIdentityApplicability.Any
        wire.startsWith(POLICY_IDENTITY_APPLICABILITY_EXACT_PREFIX) -> {
            val identity = wire.removePrefix(POLICY_IDENTITY_APPLICABILITY_EXACT_PREFIX)
            require(identity.isNotEmpty()) { "Empty exact Policy applicability identity" }
            val decoded = PolicyIdentityApplicability.Exact(identity)
            require(encodeExactIdentity(decoded.identity) == wire) {
                "Non-canonical Policy identity applicability wire"
            }
            decoded
        }
        else -> throw IllegalArgumentException("Unknown Policy identity applicability wire")
    }
}

const val POLICY_TOOL_APPLICABILITY_EXACT_PREFIX: String = "EXACT_V1:"
const val POLICY_TOOL_APPLICABILITY_UNPROVEN_V5: String = "UNPROVEN_V5"
const val POLICY_IDENTITY_APPLICABILITY_ANY: String = "ANY_V1"
const val POLICY_IDENTITY_APPLICABILITY_EXACT_PREFIX: String = "EXACT_V1:"
const val POLICY_APPLICABILITY_UNPROVEN_V5_REASON: String = "P2_APPLICABILITY_UNPROVEN_V5"
const val POLICY_APPLICABILITY_UNPROVEN_V7_REASON: String = "P2_APPLICABILITY_UNPROVEN_V7"
const val MAX_POLICY_APPLICABLE_TOOL_SCHEMAS: Int = 16

private val LOWER_SHA256 = Regex("[0-9a-f]{64}")
private val POLICY_SCHEMA_RETRIEVABLE_STATUSES = setOf(
    StoredLearningPolicyStatus.CANDIDATE.name,
    StoredLearningPolicyStatus.SHADOW.name,
    StoredLearningPolicyStatus.PROBATION.name,
    StoredLearningPolicyStatus.ACTIVE.name,
)
