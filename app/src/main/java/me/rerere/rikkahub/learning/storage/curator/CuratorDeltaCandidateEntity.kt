package me.rerere.rikkahub.learning.storage.curator

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import me.rerere.rikkahub.learning.curator.CURATOR_DELTA_SCHEMA_IDENTITY
import me.rerere.rikkahub.learning.curator.CURATOR_REDACTED_WIRE
import me.rerere.rikkahub.learning.curator.CuratorApplyPlan
import me.rerere.rikkahub.learning.curator.CuratorDeltaCandidate
import me.rerere.rikkahub.learning.curator.CuratorDeltaOperation
import me.rerere.rikkahub.learning.curator.CuratorSourcePolicyKey
import me.rerere.rikkahub.learning.curator.CuratorV1Canonicalizer
import me.rerere.rikkahub.learning.curator.CuratorV1WireCodec
import me.rerere.rikkahub.learning.curator.isCuratorSha256
import me.rerere.rikkahub.learning.curator.isSafeCuratorId
import me.rerere.rikkahub.learning.curator.isSafeCuratorPlanId
import me.rerere.rikkahub.learning.model.LearningScope

@Entity(
    tableName = "curator_delta_candidates",
    indices = [
        Index(value = ["scope_kind", "scope_id", "state", "updated_at_ms", "id"]),
        Index(value = ["state", "updated_at_ms", "id"]),
        Index(value = ["candidate_sha256"]),
    ],
)
data class CuratorDeltaCandidateEntity(
    @PrimaryKey
    val id: String,
    val operation: String,
    @ColumnInfo(name = "state_version") val stateVersion: Long,
    val state: String,
    @ColumnInfo(name = "scope_kind") val scopeKind: String,
    @ColumnInfo(name = "scope_id") val scopeId: String,
    @ColumnInfo(name = "source_policy_ids_key") val sourcePolicyIdsKey: String,
    @ColumnInfo(name = "candidate_wire") val candidateWire: String,
    @ColumnInfo(name = "candidate_sha256") val candidateSha256: String,
    @ColumnInfo(name = "input_set_sha256") val inputSetSha256: String,
    @ColumnInfo(name = "producer_identity_sha256") val producerIdentitySha256: String,
    @ColumnInfo(name = "curator_schema_identity") val curatorSchemaIdentity: String,
    @ColumnInfo(name = "apply_plan_id") val applyPlanId: String?,
    @ColumnInfo(name = "apply_plan_wire") val applyPlanWire: String?,
    @ColumnInfo(name = "apply_plan_sha256") val applyPlanSha256: String?,
    @ColumnInfo(name = "conflict_code") val conflictCode: String?,
    @ColumnInfo(name = "redacted_at_ms") val redactedAtMs: Long?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
    init {
        require(id.isSafeCuratorId())
        require(CuratorDeltaOperation.entries.any { it.name == operation })
        require(stateVersion > 0L)
        val storedState = CuratorDeltaStoredState.entries.singleOrNull { it.name == state }
        requireNotNull(storedState)
        requireNotNull(LearningScope.parseOrNull(scopeKind, scopeId))
        require(candidateSha256.isCuratorSha256())
        require(inputSetSha256.isCuratorSha256() && producerIdentitySha256.isCuratorSha256())
        require(curatorSchemaIdentity == CURATOR_DELTA_SCHEMA_IDENTITY)
        require(listOf(applyPlanId, applyPlanWire, applyPlanSha256).all { it == null } ||
            listOf(applyPlanId, applyPlanWire, applyPlanSha256).all { it != null })
        applyPlanId?.let { require(it.isSafeCuratorPlanId()) }
        applyPlanSha256?.let { require(it.isCuratorSha256()) }
        if (storedState in PLAN_REQUIRED_STATES) require(applyPlanId != null)
        if (storedState in PRE_PLAN_STATES) require(applyPlanId == null)
        require((storedState in CONFLICT_STATES) == (conflictCode != null))
        conflictCode?.let { code -> require(isCuratorConflictCode(code)) }
        require(createdAtMs >= 0L && updatedAtMs >= createdAtMs)
        require((storedState == CuratorDeltaStoredState.REDACTED_SOURCE) ==
            (redactedAtMs != null))
        if (storedState == CuratorDeltaStoredState.REDACTED_SOURCE) {
            require(sourcePolicyIdsKey == CURATOR_REDACTED_WIRE)
            require(candidateWire == CURATOR_REDACTED_WIRE)
            require(applyPlanWire == null || applyPlanWire == CURATOR_REDACTED_WIRE)
            require(requireNotNull(redactedAtMs) in createdAtMs..updatedAtMs)
        } else {
            val candidate = requireNotNull(CuratorV1WireCodec.decodeCandidateOrNull(candidateWire))
            require(CuratorV1WireCodec.candidateSha256(candidateWire) == candidateSha256)
            require(candidate.candidateId == id && candidate.operation.name == operation)
            val scope = candidate.sources.map { it.scope }.distinct().single()
            require(scope.kind.name == scopeKind && scope.storageId == scopeId)
            require(sourcePolicyIdsKey ==
                CuratorSourcePolicyKey.encode(candidate.sources.map { it.policyId }))
            applyPlanWire?.let { wire ->
                val plan = requireNotNull(CuratorV1WireCodec.decodeApplyPlanOrNull(wire))
                require(CuratorV1WireCodec.encodeApplyPlan(plan) == wire)
                require(CuratorV1WireCodec.applyPlanSha256(wire) == applyPlanSha256)
                require(plan.planId == applyPlanId && plan.candidateId == id)
                require(plan.operation.name == operation)
                require(
                    CuratorV1Canonicalizer.planId(candidate, plan.mutations, plan.lineage) ==
                        plan.planId,
                )
            }
        }
    }

    fun decodeCandidateOrNull(): CuratorDeltaCandidate? =
        CuratorV1WireCodec.decodeCandidateOrNull(candidateWire)

    fun decodeApplyPlanOrNull(): CuratorApplyPlan? =
        applyPlanWire?.let(CuratorV1WireCodec::decodeApplyPlanOrNull)

    override fun toString(): String =
        "CuratorDeltaCandidateEntity(operation=$operation, state=$state, " +
            "stateVersion=$stateVersion, plan=${applyPlanId != null}, redacted=${redactedAtMs != null}, " +
            "content=<redacted>, ids=<redacted>)"
}

data class CuratorDeltaProvenance(
    val inputSetSha256: String,
    val producerIdentitySha256: String,
) {
    init {
        require(inputSetSha256.isCuratorSha256())
        require(producerIdentitySha256.isCuratorSha256())
    }

    override fun toString(): String = "CuratorDeltaProvenance(digests=<redacted>)"
}

fun CuratorDeltaCandidate.toProposedEntity(
    provenance: CuratorDeltaProvenance,
    createdAtMs: Long,
): CuratorDeltaCandidateEntity {
    val scope = sources.map { it.scope }.distinct().single()
    val wire = CuratorV1WireCodec.encodeCandidate(this)
    return CuratorDeltaCandidateEntity(
        id = candidateId,
        operation = operation.name,
        stateVersion = 1L,
        state = CuratorDeltaStoredState.PROPOSED.name,
        scopeKind = scope.kind.name,
        scopeId = scope.storageId,
        sourcePolicyIdsKey = CuratorSourcePolicyKey.encode(sources.map { it.policyId }),
        candidateWire = wire,
        candidateSha256 = CuratorV1WireCodec.candidateSha256(wire),
        inputSetSha256 = provenance.inputSetSha256,
        producerIdentitySha256 = provenance.producerIdentitySha256,
        curatorSchemaIdentity = CURATOR_DELTA_SCHEMA_IDENTITY,
        applyPlanId = null,
        applyPlanWire = null,
        applyPlanSha256 = null,
        conflictCode = null,
        redactedAtMs = null,
        createdAtMs = createdAtMs,
        updatedAtMs = createdAtMs,
    )
}

fun CuratorDeltaCandidateEntity.withApplyPlan(
    plan: CuratorApplyPlan,
    updatedAtMs: Long,
): CuratorDeltaCandidateEntity {
    require(state == CuratorDeltaStoredState.APPROVED.name)
    require(plan.candidateId == id && plan.operation.name == operation)
    val candidate = requireNotNull(decodeCandidateOrNull())
    require(CuratorV1Canonicalizer.planId(candidate, plan.mutations, plan.lineage) == plan.planId)
    val wire = CuratorV1WireCodec.encodeApplyPlan(plan)
    return copy(
        stateVersion = stateVersion + 1L,
        state = CuratorDeltaStoredState.APPLYING.name,
        applyPlanId = plan.planId,
        applyPlanWire = wire,
        applyPlanSha256 = CuratorV1WireCodec.applyPlanSha256(wire),
        conflictCode = null,
        updatedAtMs = updatedAtMs,
    )
}

enum class CuratorDeltaStoredState {
    PROPOSED,
    APPROVED,
    REJECTED,
    APPLYING,
    APPLIED,
    APPLY_CONFLICT,
    ROLLING_BACK,
    ROLLED_BACK,
    ROLLBACK_CONFLICT,
    ARCHIVED,
    REDACTED_SOURCE,
}

internal val PLAN_REQUIRED_STATES = setOf(
    CuratorDeltaStoredState.APPLYING,
    CuratorDeltaStoredState.APPLIED,
    CuratorDeltaStoredState.APPLY_CONFLICT,
    CuratorDeltaStoredState.ROLLING_BACK,
    CuratorDeltaStoredState.ROLLED_BACK,
    CuratorDeltaStoredState.ROLLBACK_CONFLICT,
)

internal val PRE_PLAN_STATES = setOf(
    CuratorDeltaStoredState.PROPOSED,
    CuratorDeltaStoredState.APPROVED,
    CuratorDeltaStoredState.REJECTED,
)

internal val CONFLICT_STATES = setOf(
    CuratorDeltaStoredState.APPLY_CONFLICT,
    CuratorDeltaStoredState.ROLLBACK_CONFLICT,
)

internal fun isCuratorConflictCode(code: String): Boolean =
    me.rerere.rikkahub.learning.curator.CuratorConflictReason.entries.any { it.name == code }
