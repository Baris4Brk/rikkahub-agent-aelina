package me.rerere.rikkahub.learning.curator

/**
 * Pure deterministic delta planner. It reads immutable heads/evidence and returns an atomic plan;
 * it has no DAO, PolicyMutationStore, clock, model, network or delete capability.
 */
fun interface CuratorArtifactIdentity {
    /** Computes the exact artifact identity of [document] using [source]'s canonical type. */
    fun sha256(source: CuratorPolicyHead, document: CuratorPolicyDocument): String
}

class DeterministicCuratorDeltaApplier(
    private val artifactIdentity: CuratorArtifactIdentity = CuratorArtifactIdentity { _, document ->
        document.contentSha256
    },
) {
    fun plan(
        candidate: CuratorDeltaCandidate,
        heads: CuratorPolicyHeadReader,
        evidence: CuratorEvidenceReader,
    ): CuratorApplyResult {
        validateCandidateShape(candidate)?.let { return conflict(it) }
        val sources = mutableListOf<CuratorPolicyHead>()
        candidate.sources.sortedBy(CuratorSourceFence::policyId).forEach { fence ->
            val current = heads.find(fence.policyId)
                ?: return conflict(CuratorConflictReason.SOURCE_MISSING)
            when {
                current.scope != fence.scope -> return conflict(CuratorConflictReason.SCOPE_CONFLICT)
                current.state in setOf(
                    CuratorPolicyState.ARCHIVED,
                    CuratorPolicyState.SUPERSEDED,
                ) -> return conflict(CuratorConflictReason.SOURCE_STATE_CONFLICT)
                current.revision != fence.expectedRevision ->
                    return conflict(CuratorConflictReason.REVISION_CONFLICT)
                current.artifactSha256 != fence.baseHash ->
                    return conflict(CuratorConflictReason.BASE_HASH_CONFLICT)
                current.revision >= Long.MAX_VALUE - 1L ->
                    return conflict(CuratorConflictReason.REVISION_OVERFLOW)
            }
            sources += current
        }
        candidate.evidence.forEach { claimed ->
            if (claimed.scope !in sources.map(CuratorPolicyHead::scope)) {
                return conflict(CuratorConflictReason.SCOPE_CONFLICT)
            }
            val current = evidence.find(claimed.evidenceId)
                ?: return conflict(CuratorConflictReason.EVIDENCE_MISSING)
            if (current != claimed) return conflict(CuratorConflictReason.EVIDENCE_CONFLICT)
        }
        val built = when (candidate) {
            is CuratorDeltaCandidate.Update -> planUpdate(candidate, sources.single())
            is CuratorDeltaCandidate.Merge -> planMerge(candidate, sources, heads)
            is CuratorDeltaCandidate.Split -> planSplit(candidate, sources.single(), heads)
            is CuratorDeltaCandidate.Supersede -> planSupersede(candidate, sources.single(), heads)
        }
        if (built is BuiltPlan.Conflict) return conflict(built.reason)
        built as BuiltPlan.Ready
        val planId = CuratorV1Canonicalizer.planId(candidate, built.mutations, built.lineage)
        val rollback = buildRollback(planId, built.mutations, built.lineage)
        return CuratorApplyResult.Ready(
            CuratorApplyPlan(
                planId = planId,
                candidateId = candidate.candidateId,
                operation = candidate.operation,
                sourceFences = candidate.sources.sortedBy(CuratorSourceFence::policyId),
                evidence = candidate.evidence,
                diffs = candidate.diffs.sortedBy(CuratorTargetDiff::targetPolicyId),
                mutations = built.mutations,
                lineage = built.lineage,
                rollback = rollback,
            ),
        )
    }

    fun validateRollback(
        rollback: CuratorRollbackPlan,
        currentHeads: CuratorRollbackHeadReader,
    ): CuratorApplyResult {
        rollback.expectedAppliedHeads.forEach { fence ->
            val current = currentHeads.find(fence.policyId)
                ?: return conflict(CuratorConflictReason.ROLLBACK_FENCE_CONFLICT)
            if (current.scope != fence.scope || current.revision != fence.expectedRevision ||
                current.artifactSha256 != fence.baseHash
            ) return conflict(CuratorConflictReason.ROLLBACK_FENCE_CONFLICT)
        }
        return CuratorApplyResult.RollbackReady(rollback)
    }

    private fun planUpdate(
        candidate: CuratorDeltaCandidate.Update,
        source: CuratorPolicyHead,
    ): BuiltPlan {
        val diff = candidate.diffs.singleOrNull()?.takeIf { it.targetPolicyId == source.policyId }
            ?: return BuiltPlan.Conflict(CuratorConflictReason.DIFF_CONFLICT)
        val updated = applyDiff(source.document, diff.fields)
            ?: return BuiltPlan.Conflict(CuratorConflictReason.DIFF_CONFLICT)
        if (updated.contentSha256 == source.artifactSha256) {
            return BuiltPlan.Conflict(CuratorConflictReason.DIFF_CONFLICT)
        }
        return BuiltPlan.Ready(
            mutations = listOf(
                CuratorPlannedMutation(
                    CuratorMutationKind.UPDATE,
                    before = source,
                    after = source.copy(
                        revision = source.revision + 1L,
                        document = updated,
                        artifactSha256 = artifactIdentity.sha256(source, updated),
                    ),
                ),
            ),
            lineage = emptyList(),
        )
    }

    private fun planMerge(
        candidate: CuratorDeltaCandidate.Merge,
        sources: List<CuratorPolicyHead>,
        heads: CuratorPolicyHeadReader,
    ): BuiltPlan {
        val scope = sources.map(CuratorPolicyHead::scope).distinct().singleOrNull()
            ?: return BuiltPlan.Conflict(CuratorConflictReason.SCOPE_CONFLICT)
        if (candidate.outputPolicyId in sources.map(CuratorPolicyHead::policyId) ||
            heads.find(candidate.outputPolicyId) != null
        ) return BuiltPlan.Conflict(CuratorConflictReason.OUTPUT_ID_CONFLICT)
        val primary = sources.minBy(CuratorPolicyHead::policyId)
        val diff = candidate.diffs.singleOrNull()?.takeIf {
            it.targetPolicyId == candidate.outputPolicyId
        } ?: return BuiltPlan.Conflict(CuratorConflictReason.DIFF_CONFLICT)
        if (applyDiff(primary.document, diff.fields) != candidate.outputDocument) {
            return BuiltPlan.Conflict(CuratorConflictReason.DIFF_CONFLICT)
        }
        val output = CuratorPolicyHead(
            candidate.outputPolicyId,
            scope,
            revision = 1L,
            state = CuratorPolicyState.CANDIDATE,
            document = candidate.outputDocument,
            artifactSha256 = artifactIdentity.sha256(primary, candidate.outputDocument),
            storageStateCode = primary.storageStateCode?.let { PRODUCTION_CANDIDATE_STATE },
        )
        val archive = sources.sortedBy(CuratorPolicyHead::policyId).map { source ->
            CuratorPlannedMutation(
                CuratorMutationKind.ARCHIVE,
                before = source,
                after = source.copy(
                    revision = source.revision + 1L,
                    state = CuratorPolicyState.ARCHIVED,
                    storageStateCode = source.storageStateCode?.let { PRODUCTION_ARCHIVED_STATE },
                ),
            )
        }
        return BuiltPlan.Ready(
            mutations = listOf(CuratorPlannedMutation(CuratorMutationKind.INSERT, null, output)) + archive,
            lineage = sources.sortedBy(CuratorPolicyHead::policyId).map { source ->
                CuratorLineageEdge(source.policyId, output.policyId, CuratorLineageRelation.MERGED_FROM)
            },
        )
    }

    private fun planSplit(
        candidate: CuratorDeltaCandidate.Split,
        source: CuratorPolicyHead,
        heads: CuratorPolicyHeadReader,
    ): BuiltPlan {
        if (candidate.outputs.any { it.policyId == source.policyId || heads.find(it.policyId) != null }) {
            return BuiltPlan.Conflict(CuratorConflictReason.OUTPUT_ID_CONFLICT)
        }
        val outputs = candidate.outputs.sortedBy(CuratorDeltaCandidate.SplitOutput::policyId).map {
            val diff = candidate.diffs.singleOrNull { target -> target.targetPolicyId == it.policyId }
                ?: return BuiltPlan.Conflict(CuratorConflictReason.DIFF_CONFLICT)
            if (applyDiff(source.document, diff.fields) != it.document) {
                return BuiltPlan.Conflict(CuratorConflictReason.DIFF_CONFLICT)
            }
            CuratorPolicyHead(
                it.policyId,
                source.scope,
                1L,
                CuratorPolicyState.CANDIDATE,
                it.document,
                artifactSha256 = artifactIdentity.sha256(source, it.document),
                storageStateCode = source.storageStateCode?.let { PRODUCTION_CANDIDATE_STATE },
            )
        }
        return BuiltPlan.Ready(
            mutations = outputs.map { output ->
                CuratorPlannedMutation(CuratorMutationKind.INSERT, null, output)
            } + CuratorPlannedMutation(
                CuratorMutationKind.ARCHIVE,
                source,
                source.copy(
                    revision = source.revision + 1L,
                    state = CuratorPolicyState.ARCHIVED,
                    storageStateCode = source.storageStateCode?.let { PRODUCTION_ARCHIVED_STATE },
                ),
            ),
            lineage = outputs.map { output ->
                CuratorLineageEdge(source.policyId, output.policyId, CuratorLineageRelation.SPLIT_FROM)
            },
        )
    }

    private fun planSupersede(
        candidate: CuratorDeltaCandidate.Supersede,
        source: CuratorPolicyHead,
        heads: CuratorPolicyHeadReader,
    ): BuiltPlan {
        if (candidate.replacementPolicyId == source.policyId ||
            heads.find(candidate.replacementPolicyId) != null
        ) return BuiltPlan.Conflict(CuratorConflictReason.OUTPUT_ID_CONFLICT)
        val diff = candidate.diffs.singleOrNull()?.takeIf {
            it.targetPolicyId == candidate.replacementPolicyId
        } ?: return BuiltPlan.Conflict(CuratorConflictReason.DIFF_CONFLICT)
        if (applyDiff(source.document, diff.fields) != candidate.replacementDocument) {
            return BuiltPlan.Conflict(CuratorConflictReason.DIFF_CONFLICT)
        }
        val replacement = CuratorPolicyHead(
            candidate.replacementPolicyId,
            source.scope,
            1L,
            CuratorPolicyState.CANDIDATE,
            candidate.replacementDocument,
            artifactSha256 = artifactIdentity.sha256(source, candidate.replacementDocument),
            storageStateCode = source.storageStateCode?.let { PRODUCTION_CANDIDATE_STATE },
        )
        return BuiltPlan.Ready(
            mutations = listOf(
                CuratorPlannedMutation(CuratorMutationKind.INSERT, null, replacement),
                CuratorPlannedMutation(
                    CuratorMutationKind.ARCHIVE,
                    source,
                    source.copy(
                        revision = source.revision + 1L,
                        state = CuratorPolicyState.SUPERSEDED,
                        storageStateCode = source.storageStateCode?.let {
                            PRODUCTION_ARCHIVED_STATE
                        },
                    ),
                ),
            ),
            lineage = listOf(
                CuratorLineageEdge(
                    source.policyId,
                    replacement.policyId,
                    CuratorLineageRelation.SUPERSEDES,
                ),
            ),
        )
    }

    private fun validateCandidateShape(candidate: CuratorDeltaCandidate): CuratorConflictReason? {
        if (!candidate.candidateId.isSafeCuratorId()) return CuratorConflictReason.INVALID_CANDIDATE
        if (candidate.sources.isEmpty() || candidate.sources.size > MAX_CURATOR_SOURCES ||
            candidate.sources.map(CuratorSourceFence::policyId).distinct().size != candidate.sources.size
        ) return CuratorConflictReason.DUPLICATE_SOURCE
        if (candidate.evidence.isEmpty() || candidate.evidence.size > MAX_CURATOR_EVIDENCE ||
            candidate.evidence != candidate.evidence.sortedBy(CuratorEvidenceRef::evidenceId) ||
            candidate.evidence.map(CuratorEvidenceRef::evidenceId).distinct().size != candidate.evidence.size
        ) return CuratorConflictReason.INVALID_CANDIDATE
        if (candidate.diffs.isEmpty() || candidate.diffs.size > MAX_CURATOR_SPLIT_OUTPUTS ||
            candidate.diffs != candidate.diffs.sortedBy(CuratorTargetDiff::targetPolicyId) ||
            candidate.diffs.map(CuratorTargetDiff::targetPolicyId).distinct().size !=
            candidate.diffs.size
        ) return CuratorConflictReason.DIFF_CONFLICT
        return when (candidate) {
            is CuratorDeltaCandidate.Update -> if (
                candidate.diffs.size != 1 || candidate.diffs.single().targetPolicyId !=
                candidate.source.policyId
            ) CuratorConflictReason.DIFF_CONFLICT else null
            is CuratorDeltaCandidate.Merge -> when {
                candidate.sources.size !in 2..MAX_CURATOR_SOURCES ->
                    CuratorConflictReason.INVALID_CANDIDATE
                !candidate.outputPolicyId.isSafeCuratorId() -> CuratorConflictReason.INVALID_CANDIDATE
                candidate.diffs.size != 1 ||
                    candidate.diffs.single().targetPolicyId != candidate.outputPolicyId ->
                    CuratorConflictReason.DIFF_CONFLICT
                else -> null
            }
            is CuratorDeltaCandidate.Split -> when {
                candidate.outputs.size !in 2..MAX_CURATOR_SPLIT_OUTPUTS ->
                    CuratorConflictReason.INVALID_CANDIDATE
                candidate.outputs.map(CuratorDeltaCandidate.SplitOutput::policyId)
                    .distinct().size != candidate.outputs.size -> CuratorConflictReason.OUTPUT_ID_CONFLICT
                candidate.diffs.map(CuratorTargetDiff::targetPolicyId).toSet() !=
                    candidate.outputs.map(CuratorDeltaCandidate.SplitOutput::policyId).toSet() ->
                    CuratorConflictReason.DIFF_CONFLICT
                else -> null
            }
            is CuratorDeltaCandidate.Supersede -> if (
                !candidate.replacementPolicyId.isSafeCuratorId()
            ) CuratorConflictReason.INVALID_CANDIDATE else if (
                candidate.diffs.size != 1 || candidate.diffs.single().targetPolicyId !=
                candidate.replacementPolicyId
            ) CuratorConflictReason.DIFF_CONFLICT else null
        }
    }

    private fun applyDiff(
        source: CuratorPolicyDocument,
        diffs: List<CuratorFieldDiff>,
    ): CuratorPolicyDocument? {
        var result = source
        diffs.forEach { diff ->
            if (CuratorV1Canonicalizer.fieldSha256(diff.field, source.value(diff.field)) !=
                diff.beforeSha256
            ) return null
            result = runCatching { result.replace(diff.field, diff.afterValue) }.getOrNull()
                ?: return null
        }
        return result
    }

    private fun buildRollback(
        planId: String,
        mutations: List<CuratorPlannedMutation>,
        lineage: List<CuratorLineageEdge>,
    ): CuratorRollbackPlan {
        val expected = mutations.mapNotNull { mutation ->
            mutation.after?.let { after ->
                CuratorSourceFence(after.policyId, after.scope, after.revision, after.artifactSha256)
            }
        }.sortedBy(CuratorSourceFence::policyId)
        val reverse = mutations.asReversed().map { mutation ->
            when (mutation.kind) {
                CuratorMutationKind.INSERT -> {
                    val inserted = requireNotNull(mutation.after)
                    CuratorPlannedMutation(
                        CuratorMutationKind.ARCHIVE,
                        before = inserted,
                        after = inserted.copy(
                            revision = incrementRollbackRevision(inserted.revision),
                            state = CuratorPolicyState.ARCHIVED,
                            storageStateCode = inserted.storageStateCode?.let {
                                PRODUCTION_ARCHIVED_STATE
                            },
                        ),
                    )
                }
                CuratorMutationKind.UPDATE,
                CuratorMutationKind.ARCHIVE,
                CuratorMutationKind.RESTORE,
                -> {
                    val applied = requireNotNull(mutation.after)
                    val original = requireNotNull(mutation.before)
                    CuratorPlannedMutation(
                        CuratorMutationKind.RESTORE,
                        before = applied,
                        after = original.copy(revision = incrementRollbackRevision(applied.revision)),
                    )
                }
            }
        }
        return CuratorRollbackPlan(
            applyPlanId = planId,
            expectedAppliedHeads = expected,
            mutations = reverse,
            lineageToRemove = lineage,
        )
    }

    private fun incrementRollbackRevision(revision: Long): Long {
        require(revision < Long.MAX_VALUE) { "Rollback revision overflow" }
        return revision + 1L
    }

    private sealed interface BuiltPlan {
        data class Ready(
            val mutations: List<CuratorPlannedMutation>,
            val lineage: List<CuratorLineageEdge>,
        ) : BuiltPlan

        data class Conflict(val reason: CuratorConflictReason) : BuiltPlan
    }

    private fun conflict(reason: CuratorConflictReason) = CuratorApplyResult.Conflict(reason)
}

private const val PRODUCTION_CANDIDATE_STATE = "CANDIDATE"
private const val PRODUCTION_ARCHIVED_STATE = "ARCHIVED"
