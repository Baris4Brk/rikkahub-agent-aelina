package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import me.rerere.rikkahub.learning.policy.P1_SHADOW_ADMISSION_GATE_ID
import me.rerere.rikkahub.learning.retrieval.PolicyRetrievalDropReason

private val POLICY_SHADOW_REQUEST_ID = Regex("policy-shadow-request-v1:[0-9a-f]{64}")

/**
 * Content-free Stage-D request receipt. It intentionally contains no query, prompt, Policy body,
 * provider/model identity, response, outcome, or user content. One request identity is one durable
 * observation regardless of process retry or crash replay.
 */
@Entity(
    tableName = "learning_policy_shadow_observations",
    indices = [
        Index(value = ["scope_kind", "scope_id", "observed_at_ms", "request_identity"]),
        Index(value = ["task_signature", "observed_at_ms"]),
    ],
)
data class LearningPolicyShadowObservationEntity(
    @androidx.room.PrimaryKey
    @ColumnInfo(name = "request_identity")
    val requestIdentity: String,
    @ColumnInfo(name = "scope_kind")
    val scopeKind: String,
    @ColumnInfo(name = "scope_id")
    val scopeId: String,
    @ColumnInfo(name = "task_signature")
    val taskSignature: String,
    @ColumnInfo(name = "gate_identity")
    val gateIdentity: String,
    @ColumnInfo(name = "query_term_count")
    val queryTermCount: Int,
    @ColumnInfo(name = "exact_candidate_count")
    val exactCandidateCount: Int,
    @ColumnInfo(name = "lexical_candidate_count")
    val lexicalCandidateCount: Int,
    @ColumnInfo(name = "selected_count")
    val selectedCount: Int,
    @ColumnInfo(name = "estimated_tokens")
    val estimatedTokens: Int,
    @ColumnInfo(name = "latency_micros")
    val latencyMicros: Long,
    @ColumnInfo(name = "drop_reason_counts_wire")
    val dropReasonCountsWire: String,
    @ColumnInfo(name = "observed_at_ms")
    val observedAtMs: Long,
) {
    init {
        require(requestIdentity.matches(POLICY_SHADOW_REQUEST_ID))
        requireLearningScope(scopeKind, scopeId)
        requireLearningIdentity(taskSignature, "shadow task signature")
        require(gateIdentity == P1_SHADOW_ADMISSION_GATE_ID)
        require(queryTermCount in 0..64)
        require(exactCandidateCount >= 0 && lexicalCandidateCount >= 0)
        require(selectedCount in 0..20)
        require(estimatedTokens in 0..8_192)
        require(latencyMicros >= 0L)
        decodePolicyShadowDropReasonCounts(dropReasonCountsWire)
        require(observedAtMs >= 0L)
    }

    override fun toString(): String =
        "LearningPolicyShadowObservationEntity(selected=$selectedCount, terms=$queryTermCount, " +
            "scope=$scopeKind, request=<redacted>)"
}

/** Exact Policy head observed by one content-free Stage-D request. */
@Entity(
    tableName = "learning_policy_shadow_observation_items",
    primaryKeys = ["request_identity", "policy_id"],
    foreignKeys = [
        ForeignKey(
            entity = LearningPolicyShadowObservationEntity::class,
            parentColumns = ["request_identity"],
            childColumns = ["request_identity"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LearningPolicyEntity::class,
            parentColumns = ["id"],
            childColumns = ["policy_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["policy_id"])],
)
data class LearningPolicyShadowObservationItemEntity(
    @ColumnInfo(name = "request_identity")
    val requestIdentity: String,
    @ColumnInfo(name = "policy_id")
    val policyId: String,
    /** Lifecycle CAS revision after admission; never confused with immutable content revision. */
    @ColumnInfo(name = "policy_state_version")
    val policyStateVersion: Long,
    @ColumnInfo(name = "policy_content_revision")
    val policyContentRevision: Long,
    @ColumnInfo(name = "artifact_sha256")
    val artifactSha256: String,
    @ColumnInfo(name = "lifecycle_status")
    val lifecycleStatus: String,
    val rank: Int,
    @ColumnInfo(name = "exact_task_match")
    val exactTaskMatch: Boolean,
    @ColumnInfo(name = "lexical_score_micros")
    val lexicalScoreMicros: Int,
    @ColumnInfo(name = "estimated_tokens")
    val estimatedTokens: Int,
) {
    init {
        require(requestIdentity.matches(POLICY_SHADOW_REQUEST_ID))
        requireLearningStorageId(policyId, "shadow Policy ID")
        require(policyStateVersion > 0L && policyContentRevision > 0L)
        requireSha256(artifactSha256, "shadow Policy artifact")
        require(lifecycleStatus == StoredLearningPolicyStatus.SHADOW.name)
        require(rank in 1..20)
        require(lexicalScoreMicros in 0..1_000_000)
        require(estimatedTokens in 0..4_096)
    }

    override fun toString(): String =
        "LearningPolicyShadowObservationItemEntity(state=$lifecycleStatus, rank=$rank, " +
            "revisions=<redacted>, ids=<redacted>)"
}

internal fun encodePolicyShadowDropReasonCounts(
    counts: Map<PolicyRetrievalDropReason, Int>,
): String {
    require(counts.values.all { it >= 0 })
    return counts.filterValues { it > 0 }
        .toSortedMap(compareBy(PolicyRetrievalDropReason::name))
        .entries.joinToString(",") { (reason, count) -> "${reason.name}:$count" }
        .ifEmpty { "NONE" }
}

internal fun decodePolicyShadowDropReasonCounts(
    wire: String,
): Map<PolicyRetrievalDropReason, Int> {
    if (wire == "NONE") return emptyMap()
    require(wire.length in 1..1_024)
    val decoded = linkedMapOf<PolicyRetrievalDropReason, Int>()
    wire.split(',').forEach { entry ->
        val parts = entry.split(':')
        require(parts.size == 2)
        val reason = PolicyRetrievalDropReason.entries.singleOrNull { it.name == parts[0] }
            ?: throw IllegalArgumentException("Unknown shadow drop reason")
        val count = parts[1].toIntOrNull()?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Invalid shadow drop count")
        require(decoded.put(reason, count) == null)
    }
    require(encodePolicyShadowDropReasonCounts(decoded) == wire)
    return decoded
}
