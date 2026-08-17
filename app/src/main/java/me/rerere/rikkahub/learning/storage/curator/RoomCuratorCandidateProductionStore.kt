package me.rerere.rikkahub.learning.storage.curator

import androidx.room.withTransaction
import me.rerere.rikkahub.learning.curator.CURATOR_PRODUCTION_REVIEWED_STATES
import me.rerere.rikkahub.learning.curator.CuratorApplyResult
import me.rerere.rikkahub.learning.curator.CuratorCandidateProductionConflict
import me.rerere.rikkahub.learning.curator.CuratorCandidateProductionRequest
import me.rerere.rikkahub.learning.curator.CuratorCandidateProductionResult
import me.rerere.rikkahub.learning.curator.CuratorCandidateProductionStore
import me.rerere.rikkahub.learning.curator.CuratorProductionSourceFence
import me.rerere.rikkahub.learning.curator.CuratorProductionSourceProjection
import me.rerere.rikkahub.learning.curator.CuratorConflictReason
import me.rerere.rikkahub.learning.curator.CuratorDeltaCandidate
import me.rerere.rikkahub.learning.curator.CuratorEvidenceRef
import me.rerere.rikkahub.learning.curator.CuratorV1Canonicalizer
import me.rerere.rikkahub.learning.curator.DeterministicCuratorDeltaApplier
import me.rerere.rikkahub.learning.policy.PolicyCandidateType
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.storage.PolicyEvidenceEntity

/**
 * Validates current Room heads and evidence in the same transaction as insertProposed. A caller
 * supplies a reviewed delta, never a model wire; every source/content/evidence fence is re-read.
 */
class RoomCuratorCandidateProductionStore(
    private val database: LearningDatabase,
) : CuratorCandidateProductionStore {
    override suspend fun listExactReviewedSources(
        consumingAssistantId: kotlin.uuid.Uuid,
        limit: Int,
    ): List<CuratorProductionSourceProjection> {
        require(limit in 1..80)
        return database.withTransaction {
            database.policyDao().listForBoundedReview(consumingAssistantId.toString(), limit)
                .filter { policy ->
                    policy.scopeKind == "ASSISTANT" &&
                        policy.scopeId == consumingAssistantId.toString()
                }
                .mapNotNull { policy -> toProductionProjectionOrNull(policy) }
        }
    }

    override suspend fun propose(
        request: CuratorCandidateProductionRequest,
    ): CuratorCandidateProductionResult = database.withTransaction {
        val policyDao = database.policyDao()
        val sources = linkedMapOf<String, LearningPolicyEntity>()
        request.exactSources.forEach { expected ->
            val current = policyDao.findPolicy(expected.source.policyId)
                ?: return@withTransaction conflict(CuratorCandidateProductionConflict.SOURCE_MISSING)
            if (current.scopeKind != expected.source.scope.kind.name ||
                current.scopeId != expected.source.scope.storageId
            ) return@withTransaction conflict(CuratorCandidateProductionConflict.SOURCE_SCOPE_CONFLICT)
            if (current.stateVersion != expected.source.expectedRevision ||
                current.contentRevision != expected.expectedContentRevision ||
                current.artifactSha256 != expected.source.baseHash ||
                current.status != expected.expectedStorageState ||
                current.updatedAtMs != expected.expectedUpdatedAtMs
            ) return@withTransaction conflict(CuratorCandidateProductionConflict.SOURCE_FENCE_CONFLICT)
            if (current.status !in CURATOR_PRODUCTION_REVIEWED_STATES ||
                !current.sourceValid || !current.schemaValid
            ) return@withTransaction conflict(CuratorCandidateProductionConflict.SOURCE_NOT_REVIEWED)
            val head = current.toProductionCuratorHeadOrNull()
                ?: return@withTransaction conflict(CuratorCandidateProductionConflict.IDENTITY_CONFLICT)
            if (head.state.name != "REVIEWED") {
                return@withTransaction conflict(CuratorCandidateProductionConflict.SOURCE_NOT_REVIEWED)
            }
            sources[current.id] = current
        }
        val primary = sources.values.first()
        if (sources.values.any { source ->
                source.scopeKind != primary.scopeKind || source.scopeId != primary.scopeId ||
                    source.taskSignature != primary.taskSignature ||
                    source.policyType != primary.policyType ||
                    source.applicableModelIdentityWire != primary.applicableModelIdentityWire ||
                    source.applicableProviderIdentityWire != primary.applicableProviderIdentityWire
            }
        ) return@withTransaction conflict(
            CuratorCandidateProductionConflict.SOURCE_COMPATIBILITY_CONFLICT,
        )

        val evidenceCapsules = mutableListOf<PolicyEvidenceEntity>()
        sources.values.forEach { source ->
            val raw = policyDao.listEvidenceForCurator(source.id, MAX_PRODUCTION_EVIDENCE + 1)
            if (raw.isEmpty()) {
                return@withTransaction conflict(CuratorCandidateProductionConflict.EVIDENCE_MISSING)
            }
            if (raw.size > MAX_PRODUCTION_EVIDENCE) {
                return@withTransaction conflict(
                    CuratorCandidateProductionConflict.EVIDENCE_FENCE_CONFLICT,
                )
            }
            val validity = policyDao.listEvidenceValidity(
                source.id,
                MAX_PRODUCTION_EVIDENCE + 1,
            ).associateBy { it.episodeId }
            if (validity.size != raw.size || raw.any { validity[it.episodeId]?.sourceValid != true }) {
                return@withTransaction conflict(
                    CuratorCandidateProductionConflict.EVIDENCE_FENCE_CONFLICT,
                )
            }
            evidenceCapsules += raw
        }
        val claims = request.candidate.evidence.associateBy(CuratorEvidenceRef::evidenceId)
        if (claims.size != request.candidate.evidence.size || claims.isEmpty()) {
            return@withTransaction conflict(CuratorCandidateProductionConflict.EVIDENCE_MISSING)
        }
        request.candidate.evidence.forEach { claim ->
            val belongsToExactSource = evidenceCapsules.any { capsule ->
                capsule.sourceId == claim.evidenceId &&
                    capsule.sourceRevision == claim.sourceRevision &&
                    capsule.sourceIntegritySha256 == claim.integritySha256
            }
            val exactValidityCount = database.curatorDeltaDao().countExactValidEvidenceFence(
                claim.evidenceId,
                claim.scope.kind.name,
                claim.scope.storageId,
                claim.sourceRevision,
                claim.integritySha256,
            )
            if (!belongsToExactSource || exactValidityCount != 1) {
                return@withTransaction conflict(
                    CuratorCandidateProductionConflict.EVIDENCE_FENCE_CONFLICT,
                )
            }
        }

        val heads = sources.mapValues { (_, entity) ->
            requireNotNull(entity.toProductionCuratorHeadOrNull())
        }
        val type = runCatching { PolicyCandidateType.valueOf(primary.policyType) }.getOrNull()
            ?: return@withTransaction conflict(CuratorCandidateProductionConflict.IDENTITY_CONFLICT)
        val outputIds = when (val candidate = request.candidate) {
            is CuratorDeltaCandidate.Update -> emptyList()
            is CuratorDeltaCandidate.Merge -> listOf(candidate.outputPolicyId)
            is CuratorDeltaCandidate.Split -> candidate.outputs.map { it.policyId }
            is CuratorDeltaCandidate.Supersede -> listOf(candidate.replacementPolicyId)
        }
        if (outputIds.any { policyDao.findPolicy(it) != null }) {
            return@withTransaction conflict(CuratorCandidateProductionConflict.OUTPUT_ID_CONFLICT)
        }
        val plan = DeterministicCuratorDeltaApplier { _, document ->
            document.canonicalPolicyArtifact(primary, type)
        }.plan(
            request.candidate,
            { policyId -> heads[policyId] },
            { evidenceId -> claims[evidenceId] },
        )
        if (plan is CuratorApplyResult.Conflict) {
            return@withTransaction conflict(plan.reason.toProductionConflict())
        }

        val candidateWire = me.rerere.rikkahub.learning.curator.CuratorV1WireCodec
            .encodeCandidate(request.candidate)
        val inputSetSha256 = CuratorV1Canonicalizer.digest(
            domain = "curator-reviewed-input-set-v1",
            fields = request.exactSources.flatMap { source ->
                listOf(
                    source.source.policyId,
                    source.source.scope.kind.name,
                    source.source.scope.storageId,
                    source.source.expectedRevision.toString(),
                    source.expectedContentRevision.toString(),
                    source.source.baseHash,
                    source.expectedStorageState,
                    source.expectedUpdatedAtMs.toString(),
                )
            } + request.candidate.evidence.flatMap { evidence ->
                listOf(
                    evidence.evidenceId,
                    evidence.scope.kind.name,
                    evidence.scope.storageId,
                    evidence.sourceRevision.toString(),
                    evidence.integritySha256,
                )
            } + candidateWire,
        )
        val producerIdentity = CuratorV1Canonicalizer.digest(
            domain = "curator-production-producer-v1",
            fields = listOf("EXPLICIT_USER_REVIEW", "ROOM_EXACT_FENCES", "NO_AUTO_APPLY"),
        )
        val entity = request.candidate.toProposedEntity(
            CuratorDeltaProvenance(inputSetSha256, producerIdentity),
            request.proposedAtMs,
        )
        when (val inserted = database.curatorDeltaDao().insertProposed(
            entity,
            CuratorDeltaRevisionActor.USER,
        )) {
            CuratorDeltaInsertResult.Inserted -> CuratorCandidateProductionResult.Proposed(
                entity.id,
                entity.candidateSha256,
                entity.stateVersion,
                entity.createdAtMs,
            )
            is CuratorDeltaInsertResult.Duplicate -> CuratorCandidateProductionResult.Duplicate(
                entity.id,
                inserted.currentState,
                inserted.currentStateVersion,
            )
            is CuratorDeltaInsertResult.Conflict -> conflict(
                CuratorCandidateProductionConflict.IDENTITY_CONFLICT,
            )
        }
    }
    private suspend fun toProductionProjectionOrNull(
        policy: LearningPolicyEntity,
    ): CuratorProductionSourceProjection? {
        if (policy.status !in CURATOR_PRODUCTION_REVIEWED_STATES ||
            !policy.sourceValid || !policy.schemaValid
        ) return null
        val head = policy.toProductionCuratorHeadOrNull() ?: return null
        val raw = database.policyDao().listEvidenceForCurator(policy.id, MAX_PRODUCTION_EVIDENCE + 1)
        if (raw.isEmpty() || raw.size > MAX_PRODUCTION_EVIDENCE) return null
        val validity = database.policyDao().listEvidenceValidity(
            policy.id,
            MAX_PRODUCTION_EVIDENCE + 1,
        ).associateBy { it.episodeId }
        if (validity.size != raw.size || raw.any { validity[it.episodeId]?.sourceValid != true }) {
            return null
        }
        val refsWithValidity = raw.map { capsule ->
            val count = database.curatorDeltaDao().countExactValidEvidenceFence(
                capsule.sourceId,
                head.scope.kind.name,
                head.scope.storageId,
                capsule.sourceRevision,
                capsule.sourceIntegritySha256,
            )
            count to CuratorEvidenceRef(
                capsule.sourceId,
                head.scope,
                capsule.sourceRevision,
                capsule.sourceIntegritySha256,
            )
        }
        if (refsWithValidity.any { it.first != 1 }) return null
        if (refsWithValidity.map { it.second }.groupBy(CuratorEvidenceRef::evidenceId)
                .any { (_, sameId) -> sameId.distinct().size != 1 }
        ) return null
        val refs = refsWithValidity.map { it.second }
            .distinctBy(CuratorEvidenceRef::evidenceId).sortedBy(CuratorEvidenceRef::evidenceId)
        if (refs.isEmpty() || refs.size > MAX_PRODUCTION_EVIDENCE) return null
        return CuratorProductionSourceProjection(
            exact = CuratorProductionSourceFence(
                source = me.rerere.rikkahub.learning.curator.CuratorSourceFence(
                    policy.id,
                    head.scope,
                    policy.stateVersion,
                    policy.artifactSha256,
                ),
                expectedContentRevision = policy.contentRevision,
                expectedStorageState = policy.status,
                expectedUpdatedAtMs = policy.updatedAtMs,
            ),
            document = head.document,
            policyType = policy.policyType,
            taskSignature = policy.taskSignature,
            evidence = refs,
        )
    }
}

private fun conflict(reason: CuratorCandidateProductionConflict) =
    CuratorCandidateProductionResult.Conflict(reason)

private fun CuratorConflictReason.toProductionConflict(): CuratorCandidateProductionConflict =
    when (this) {
        CuratorConflictReason.OUTPUT_ID_CONFLICT ->
            CuratorCandidateProductionConflict.OUTPUT_ID_CONFLICT
        CuratorConflictReason.SOURCE_MISSING -> CuratorCandidateProductionConflict.SOURCE_MISSING
        CuratorConflictReason.SOURCE_STATE_CONFLICT ->
            CuratorCandidateProductionConflict.SOURCE_NOT_REVIEWED
        CuratorConflictReason.SCOPE_CONFLICT ->
            CuratorCandidateProductionConflict.SOURCE_SCOPE_CONFLICT
        CuratorConflictReason.REVISION_CONFLICT,
        CuratorConflictReason.BASE_HASH_CONFLICT,
        -> CuratorCandidateProductionConflict.SOURCE_FENCE_CONFLICT
        CuratorConflictReason.EVIDENCE_MISSING -> CuratorCandidateProductionConflict.EVIDENCE_MISSING
        CuratorConflictReason.EVIDENCE_CONFLICT ->
            CuratorCandidateProductionConflict.EVIDENCE_FENCE_CONFLICT
        else -> CuratorCandidateProductionConflict.INVALID_DELTA
    }

private const val MAX_PRODUCTION_EVIDENCE = 32
