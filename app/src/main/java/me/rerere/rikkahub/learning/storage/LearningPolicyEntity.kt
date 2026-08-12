package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Canonical P1 policy candidate/shadow artifact. P1 cannot represent an active policy. */
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
    @ColumnInfo(name = "artifact_sha256")
    val artifactSha256: String,
    @ColumnInfo(name = "compiler_abi")
    val compilerAbi: String,
    val status: String,
    @ColumnInfo(name = "source_valid")
    val sourceValid: Boolean,
    @ColumnInfo(name = "schema_valid")
    val schemaValid: Boolean,
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
        requireSha256(artifactSha256, "policy artifact")
        requireLearningIdentity(compilerAbi, "policy compiler ABI")
        require(StoredLearningPolicyStatus.entries.any { it.name == status }) { "Invalid P1 policy status" }
        require((status == StoredLearningPolicyStatus.STALE.name) == (staleReason != null)) {
            "Policy stale reason disagrees with status"
        }
        staleReason?.let { requireLearningCode(it, "policy stale reason") }
        if (status == StoredLearningPolicyStatus.CANDIDATE.name || status == StoredLearningPolicyStatus.SHADOW.name) {
            require(sourceValid && schemaValid) { "Retrievable policy is source/schema stale" }
        }
        if (status == StoredLearningPolicyStatus.STALE.name) {
            require(!sourceValid || !schemaValid) { "STALE policy has no invalid dependency" }
        }
        require(
            distinctEpisodeSupport >= 0L &&
                positiveEpisodeCount >= 0L &&
                negativeEpisodeCount >= 0L
        ) { "Negative policy evidence count" }
        require(usageCount == 0L) { "P1 policy usage must remain zero" }
        require(confidence.isFinite() && confidence in 0.0..1.0) { "Invalid policy confidence" }
        require(observedUtilityDelta == null && utilityUncertainty == null) {
            "P1 policy utility must remain UNKNOWN"
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
        require(lastUsedAtMs == null) { "P1 policy last_used must remain empty" }
    }

    override fun toString(): String =
        "LearningPolicyEntity(status=$status, revision=$stateVersion, support=$distinctEpisodeSupport, text=<redacted>, ids=<redacted>)"
}
