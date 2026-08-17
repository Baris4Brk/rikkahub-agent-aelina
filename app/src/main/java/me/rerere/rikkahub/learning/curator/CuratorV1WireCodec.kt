package me.rerere.rikkahub.learning.curator

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.learning.model.LearningScope

/**
 * Canonical, bounded persistence wire for reviewed Curator v1 deltas and their immutable plans.
 * The wire contains sanitized Policy text, so storage must replace it with [CURATOR_REDACTED_WIRE]
 * when an exact source-policy privacy invalidation is observed.
 */
object CuratorV1WireCodec {
    fun encodeCandidate(candidate: CuratorDeltaCandidate): String =
        WIRE_JSON.encodeToString(CandidateWire.serializer(), candidate.toWire())
            .also { requireUtf8Bound(it, MAX_CURATOR_CANDIDATE_WIRE_BYTES, "candidate") }

    fun decodeCandidateOrNull(wire: String): CuratorDeltaCandidate? {
        if (wire == CURATOR_REDACTED_WIRE) return null
        return runCatching {
            requireUtf8Bound(wire, MAX_CURATOR_CANDIDATE_WIRE_BYTES, "candidate")
            WIRE_JSON.decodeFromString(CandidateWire.serializer(), wire).toDomain()
        }.getOrNull()
    }

    fun encodeApplyPlan(plan: CuratorApplyPlan): String =
        WIRE_JSON.encodeToString(ApplyPlanWire.serializer(), plan.toWire())
            .also { requireUtf8Bound(it, MAX_CURATOR_APPLY_PLAN_WIRE_BYTES, "apply plan") }

    fun decodeApplyPlanOrNull(wire: String): CuratorApplyPlan? {
        if (wire == CURATOR_REDACTED_WIRE) return null
        return runCatching {
            requireUtf8Bound(wire, MAX_CURATOR_APPLY_PLAN_WIRE_BYTES, "apply plan")
            WIRE_JSON.decodeFromString(ApplyPlanWire.serializer(), wire).toDomain()
        }.getOrNull()
    }

    fun candidateSha256(wire: String): String = CuratorV1Canonicalizer.digest(
        domain = "curator-delta-candidate-wire-v1",
        fields = listOf(wire),
    )

    fun applyPlanSha256(wire: String): String = CuratorV1Canonicalizer.digest(
        domain = "curator-delta-apply-plan-wire-v1",
        fields = listOf(wire),
    )
}

/** Exact token set: `|` is deliberately excluded by Curator's identifier grammar. */
object CuratorSourcePolicyKey {
    fun encode(policyIds: List<String>): String {
        require(policyIds.isNotEmpty() && policyIds.size <= MAX_CURATOR_SOURCES)
        require(policyIds.all(String::isSafeCuratorId))
        require(policyIds.distinct().size == policyIds.size)
        return policyIds.sorted().joinToString(separator = "|", prefix = "|", postfix = "|")
    }

    fun token(policyId: String): String {
        require(policyId.isSafeCuratorId())
        return "|$policyId|"
    }

    fun contains(encoded: String, policyId: String): Boolean = token(policyId) in encoded
}

private fun CuratorDeltaCandidate.toWire(): CandidateWire {
    val outputs = when (this) {
        is CuratorDeltaCandidate.Update -> emptyList()
        is CuratorDeltaCandidate.Merge -> listOf(OutputWire(outputPolicyId, outputDocument.toWire()))
        is CuratorDeltaCandidate.Split -> outputs.sortedBy(CuratorDeltaCandidate.SplitOutput::policyId)
            .map { OutputWire(it.policyId, it.document.toWire()) }
        is CuratorDeltaCandidate.Supersede ->
            listOf(OutputWire(replacementPolicyId, replacementDocument.toWire()))
    }
    return CandidateWire(
        schema = CURATOR_DELTA_SCHEMA_IDENTITY,
        candidateId = candidateId,
        operation = operation.name,
        sources = sources.sortedBy(CuratorSourceFence::policyId).map(CuratorSourceFence::toWire),
        evidence = evidence.sortedBy(CuratorEvidenceRef::evidenceId).map(CuratorEvidenceRef::toWire),
        diffs = diffs.sortedBy(CuratorTargetDiff::targetPolicyId).map(CuratorTargetDiff::toWire),
        outputs = outputs,
    )
}

private fun CandidateWire.toDomain(): CuratorDeltaCandidate {
    require(schema == CURATOR_DELTA_SCHEMA_IDENTITY)
    require(candidateId.isSafeCuratorId())
    val operation = CuratorDeltaOperation.valueOf(operation)
    val sources = sources.map(SourceFenceWire::toDomain)
    val evidence = evidence.map(EvidenceWire::toDomain)
    val diffs = diffs.map(TargetDiffWire::toDomain)
    val outputs = outputs.map(OutputWire::toDomain)
    require(sources == sources.sortedBy(CuratorSourceFence::policyId))
    require(evidence == evidence.sortedBy(CuratorEvidenceRef::evidenceId))
    require(diffs == diffs.sortedBy(CuratorTargetDiff::targetPolicyId))
    require(sources.map(CuratorSourceFence::policyId).distinct().size == sources.size)
    require(evidence.map(CuratorEvidenceRef::evidenceId).distinct().size == evidence.size)
    require(outputs.map(CuratorDeltaCandidate.SplitOutput::policyId).distinct().size == outputs.size)
    require(sources.size in 1..MAX_CURATOR_SOURCES)
    require(evidence.size in 1..MAX_CURATOR_EVIDENCE)
    require(diffs.size in 1..MAX_CURATOR_SPLIT_OUTPUTS)
    return when (operation) {
        CuratorDeltaOperation.UPDATE_CANDIDATE -> {
            require(outputs.isEmpty() && sources.size == 1 && diffs.size == 1)
            require(diffs.single().targetPolicyId == sources.single().policyId)
            CuratorDeltaCandidate.Update(candidateId, sources.single(), evidence, diffs)
        }
        CuratorDeltaOperation.MERGE_CANDIDATE -> {
            require(sources.size in 2..MAX_CURATOR_SOURCES && outputs.size == 1 && diffs.size == 1)
            val output = outputs.single()
            require(diffs.single().targetPolicyId == output.policyId)
            CuratorDeltaCandidate.Merge(
                candidateId,
                sources,
                output.policyId,
                output.document,
                evidence,
                diffs,
            )
        }
        CuratorDeltaOperation.SPLIT_CANDIDATE -> {
            require(sources.size == 1 && outputs.size in 2..MAX_CURATOR_SPLIT_OUTPUTS)
            require(outputs == outputs.sortedBy(CuratorDeltaCandidate.SplitOutput::policyId))
            require(diffs.map(CuratorTargetDiff::targetPolicyId).toSet() ==
                outputs.map(CuratorDeltaCandidate.SplitOutput::policyId).toSet())
            CuratorDeltaCandidate.Split(candidateId, sources.single(), outputs, evidence, diffs)
        }
        CuratorDeltaOperation.SUPERSEDE_CANDIDATE -> {
            require(sources.size == 1 && outputs.size == 1 && diffs.size == 1)
            val output = outputs.single()
            require(diffs.single().targetPolicyId == output.policyId)
            CuratorDeltaCandidate.Supersede(
                candidateId,
                sources.single(),
                output.policyId,
                output.document,
                evidence,
                diffs,
            )
        }
    }
}

private fun CuratorApplyPlan.toWire(): ApplyPlanWire = ApplyPlanWire(
    schema = CURATOR_APPLY_PLAN_SCHEMA_IDENTITY,
    planId = planId,
    candidateId = candidateId,
    operation = operation.name,
    sourceFences = sourceFences.sortedBy(CuratorSourceFence::policyId).map(CuratorSourceFence::toWire),
    evidence = evidence.sortedBy(CuratorEvidenceRef::evidenceId).map(CuratorEvidenceRef::toWire),
    diffs = diffs.sortedBy(CuratorTargetDiff::targetPolicyId).map(CuratorTargetDiff::toWire),
    mutations = mutations.map(CuratorPlannedMutation::toWire),
    lineage = lineage.sortedLineage().map(CuratorLineageEdge::toWire),
    rollback = rollback.toWire(),
)

private fun ApplyPlanWire.toDomain(): CuratorApplyPlan {
    require(schema == CURATOR_APPLY_PLAN_SCHEMA_IDENTITY)
    val sourceFences = sourceFences.map(SourceFenceWire::toDomain)
    val evidence = evidence.map(EvidenceWire::toDomain)
    val diffs = diffs.map(TargetDiffWire::toDomain)
    val mutations = mutations.map(MutationWire::toDomain)
    val lineage = lineage.map(LineageWire::toDomain)
    require(sourceFences == sourceFences.sortedBy(CuratorSourceFence::policyId))
    require(evidence == evidence.sortedBy(CuratorEvidenceRef::evidenceId))
    require(diffs == diffs.sortedBy(CuratorTargetDiff::targetPolicyId))
    require(lineage == lineage.sortedLineage())
    val rollback = rollback.toDomain()
    require(rollback.applyPlanId == planId)
    return CuratorApplyPlan(
        planId = planId,
        candidateId = candidateId,
        operation = CuratorDeltaOperation.valueOf(operation),
        sourceFences = sourceFences,
        evidence = evidence,
        diffs = diffs,
        mutations = mutations,
        lineage = lineage,
        rollback = rollback,
    )
}

private fun CuratorRollbackPlan.toWire(): RollbackWire = RollbackWire(
    applyPlanId = applyPlanId,
    expectedAppliedHeads = expectedAppliedHeads.sortedBy(CuratorSourceFence::policyId)
        .map(CuratorSourceFence::toWire),
    mutations = mutations.map(CuratorPlannedMutation::toWire),
    lineageToRemove = lineageToRemove.sortedLineage().map(CuratorLineageEdge::toWire),
)

private fun RollbackWire.toDomain(): CuratorRollbackPlan {
    val expected = expectedAppliedHeads.map(SourceFenceWire::toDomain)
    val lineage = lineageToRemove.map(LineageWire::toDomain)
    require(expected == expected.sortedBy(CuratorSourceFence::policyId))
    require(lineage == lineage.sortedLineage())
    return CuratorRollbackPlan(
        applyPlanId,
        expected,
        mutations.map(MutationWire::toDomain),
        lineage,
    )
}

private fun CuratorSourceFence.toWire() = SourceFenceWire(
    policyId,
    scope.toWire(),
    expectedRevision,
    baseHash,
)

private fun SourceFenceWire.toDomain() = CuratorSourceFence(
    policyId,
    scope.toDomain(),
    expectedRevision,
    baseHash,
)

private fun CuratorEvidenceRef.toWire() = EvidenceWire(
    evidenceId,
    scope.toWire(),
    sourceRevision,
    integritySha256,
)

private fun EvidenceWire.toDomain() = CuratorEvidenceRef(
    evidenceId,
    scope.toDomain(),
    sourceRevision,
    integritySha256,
)

private fun CuratorTargetDiff.toWire() = TargetDiffWire(
    targetPolicyId,
    fields.map { FieldDiffWire(it.field.name, it.beforeSha256, it.afterValue) },
)

private fun TargetDiffWire.toDomain() = CuratorTargetDiff(
    targetPolicyId,
    fields.map {
        CuratorFieldDiff(CuratorPolicyField.valueOf(it.field), it.beforeSha256, it.afterValue)
    },
)

private fun CuratorPolicyDocument.toWire() = DocumentWire(
    trigger,
    procedure,
    verification,
    boundary,
    failureMode,
    applicableToolSchemaSha256,
)

private fun DocumentWire.toDomain() = CuratorPolicyDocument(
    trigger,
    procedure,
    verification,
    boundary,
    failureMode,
    applicableToolSchemaSha256,
)

private fun OutputWire.toDomain() = CuratorDeltaCandidate.SplitOutput(policyId, document.toDomain())

private fun CuratorPolicyHead.toWire() = PolicyHeadWire(
    policyId,
    scope.toWire(),
    revision,
    state.name,
    artifactSha256,
    storageStateCode,
    document.toWire(),
)

private fun PolicyHeadWire.toDomain() = CuratorPolicyHead(
    policyId,
    scope.toDomain(),
    revision,
    CuratorPolicyState.valueOf(state),
    document.toDomain(),
    artifactSha256,
    storageStateCode,
)

private fun CuratorPlannedMutation.toWire() = MutationWire(
    kind.name,
    before?.toWire(),
    after?.toWire(),
)

private fun MutationWire.toDomain() = CuratorPlannedMutation(
    CuratorMutationKind.valueOf(kind),
    before?.toDomain(),
    after?.toDomain(),
)

private fun CuratorLineageEdge.toWire() = LineageWire(parentPolicyId, childPolicyId, relation.name)

private fun LineageWire.toDomain() = CuratorLineageEdge(
    parentPolicyId,
    childPolicyId,
    CuratorLineageRelation.valueOf(relation),
)

private fun LearningScope.toWire() = ScopeWire(kind.name, storageId)

private fun ScopeWire.toDomain(): LearningScope =
    requireNotNull(LearningScope.parseOrNull(kind, id)) { "Invalid Curator scope" }

private fun List<CuratorLineageEdge>.sortedLineage(): List<CuratorLineageEdge> = sortedWith(
    compareBy(CuratorLineageEdge::parentPolicyId)
        .thenBy(CuratorLineageEdge::childPolicyId)
        .thenBy { it.relation.ordinal },
)

private fun requireUtf8Bound(wire: String, maxBytes: Int, label: String) {
    require(wire.toByteArray(Charsets.UTF_8).size in 2..maxBytes) { "Invalid $label wire size" }
}

@Serializable
private data class CandidateWire(
    val schema: String,
    val candidateId: String,
    val operation: String,
    val sources: List<SourceFenceWire>,
    val evidence: List<EvidenceWire>,
    val diffs: List<TargetDiffWire>,
    val outputs: List<OutputWire>,
)

@Serializable
private data class ApplyPlanWire(
    val schema: String,
    val planId: String,
    val candidateId: String,
    val operation: String,
    val sourceFences: List<SourceFenceWire>,
    val evidence: List<EvidenceWire>,
    val diffs: List<TargetDiffWire>,
    val mutations: List<MutationWire>,
    val lineage: List<LineageWire>,
    val rollback: RollbackWire,
)

@Serializable
private data class RollbackWire(
    val applyPlanId: String,
    val expectedAppliedHeads: List<SourceFenceWire>,
    val mutations: List<MutationWire>,
    val lineageToRemove: List<LineageWire>,
)

@Serializable
private data class ScopeWire(val kind: String, val id: String)

@Serializable
private data class SourceFenceWire(
    val policyId: String,
    val scope: ScopeWire,
    val expectedRevision: Long,
    val baseHash: String,
)

@Serializable
private data class EvidenceWire(
    val evidenceId: String,
    val scope: ScopeWire,
    val sourceRevision: Long,
    val integritySha256: String,
)

@Serializable
private data class TargetDiffWire(val targetPolicyId: String, val fields: List<FieldDiffWire>)

@Serializable
private data class FieldDiffWire(
    val field: String,
    val beforeSha256: String,
    val afterValue: String,
)

@Serializable
private data class OutputWire(val policyId: String, val document: DocumentWire)

@Serializable
private data class DocumentWire(
    val trigger: String,
    val procedure: String,
    val verification: String,
    val boundary: String,
    val failureMode: String,
    val applicableToolSchemaSha256: List<String>,
)

@Serializable
private data class PolicyHeadWire(
    val policyId: String,
    val scope: ScopeWire,
    val revision: Long,
    val state: String,
    val artifactSha256: String,
    val storageStateCode: String?,
    val document: DocumentWire,
)

@Serializable
private data class MutationWire(
    val kind: String,
    val before: PolicyHeadWire?,
    val after: PolicyHeadWire?,
)

@Serializable
private data class LineageWire(
    val parentPolicyId: String,
    val childPolicyId: String,
    val relation: String,
)

private val WIRE_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
}

const val CURATOR_DELTA_SCHEMA_IDENTITY: String = "curator-delta-storage-v1"
const val CURATOR_APPLY_PLAN_SCHEMA_IDENTITY: String = "curator-apply-plan-storage-v2"
const val CURATOR_REDACTED_WIRE: String = "REDACTED_V1"
const val MAX_CURATOR_CANDIDATE_WIRE_BYTES: Int = 128 * 1_024
const val MAX_CURATOR_APPLY_PLAN_WIRE_BYTES: Int = 512 * 1_024
