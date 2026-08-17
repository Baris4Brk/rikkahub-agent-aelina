package me.rerere.rikkahub.learning.storage.curator

import androidx.room.withTransaction
import me.rerere.rikkahub.learning.curator.CuratorApplyPlan
import me.rerere.rikkahub.learning.curator.CuratorApplyResult
import me.rerere.rikkahub.learning.curator.CuratorApplyRuntimeStore
import me.rerere.rikkahub.learning.curator.CuratorArtifactIdentity
import me.rerere.rikkahub.learning.curator.CuratorConflictReason
import me.rerere.rikkahub.learning.curator.CuratorDeltaCandidate
import me.rerere.rikkahub.learning.curator.CuratorDeltaOperation
import me.rerere.rikkahub.learning.curator.CuratorEvidenceRef
import me.rerere.rikkahub.learning.curator.CuratorLineageEdge
import me.rerere.rikkahub.learning.curator.CuratorMutationKind
import me.rerere.rikkahub.learning.curator.CuratorPlannedMutation
import me.rerere.rikkahub.learning.curator.CuratorPolicyDocument
import me.rerere.rikkahub.learning.curator.CuratorPolicyHead
import me.rerere.rikkahub.learning.curator.CuratorPolicyState
import me.rerere.rikkahub.learning.curator.CuratorRuntimeApplyRequest
import me.rerere.rikkahub.learning.curator.CuratorRuntimeConflict
import me.rerere.rikkahub.learning.curator.CuratorRuntimeMutationResult
import me.rerere.rikkahub.learning.curator.CuratorRuntimeRollbackRequest
import me.rerere.rikkahub.learning.curator.CuratorRuntimeTerminalState
import me.rerere.rikkahub.learning.curator.DeterministicCuratorDeltaApplier
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.PolicyCandidateType
import me.rerere.rikkahub.learning.policy.policyArtifactSha256
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.storage.LearningPolicyRevisionActor
import me.rerere.rikkahub.learning.storage.LearningPolicyRevisionReason
import me.rerere.rikkahub.learning.storage.PolicyEvidenceEntity
import me.rerere.rikkahub.learning.storage.PolicyApplicabilityWire
import me.rerere.rikkahub.learning.storage.PolicyRevisionEntity
import me.rerere.rikkahub.learning.storage.PolicyRewardEvidenceEntity

/**
 * Canonical production Curator writer.
 *
 * Planning, exact Policy-head re-read, every before/after mutation, Policy audit revisions,
 * candidate CAS and Curator lineage/revision all execute in one LearningDatabase transaction.
 * Typed conflicts happen before the first write; a late fence failure throws [RuntimeAbort] so Room
 * rolls back every earlier statement. There is no delete path.
 */
class RoomCuratorApplyRuntimeStore(
    private val database: LearningDatabase,
) : CuratorApplyRuntimeStore {
    override suspend fun applyApproved(
        request: CuratorRuntimeApplyRequest,
    ): CuratorRuntimeMutationResult = runRuntimeTransaction(request.expectedOperation) {
        database.withTransaction { applyInOpenTransaction(request) }
    }

    override suspend fun rollbackApplied(
        request: CuratorRuntimeRollbackRequest,
    ): CuratorRuntimeMutationResult = runRuntimeTransaction(request.expectedOperation) {
        database.withTransaction { rollbackInOpenTransaction(request) }
    }

    private suspend fun applyInOpenTransaction(
        request: CuratorRuntimeApplyRequest,
    ): CuratorRuntimeMutationResult {
        val curatorDao = database.curatorDeltaDao()
        val current = curatorDao.find(request.candidateId)
            ?: abort(CuratorRuntimeConflict.CANDIDATE_MISSING)
        requireCandidateIdentity(current, request)

        exactAppliedDuplicateOrNull(current, request)?.let { return it }
        if (current.state !in setOf(
                CuratorDeltaStoredState.APPROVED.name,
                CuratorDeltaStoredState.APPLYING.name,
            )
        ) abort(CuratorRuntimeConflict.CANDIDATE_STATE_CONFLICT)
        val candidate = current.decodeCandidateOrNull()
            ?: abort(CuratorRuntimeConflict.CANDIDATE_STATE_CONFLICT)
        val policySnapshot = materializePlanningSnapshot(candidate, request.committedAtMs)
        val canonicalPlan = planExact(candidate, policySnapshot)

        val applying = when (CuratorDeltaStoredState.valueOf(current.state)) {
            CuratorDeltaStoredState.APPROVED -> {
                requireApprovedFence(current, request)
                val next = current.withApplyPlan(canonicalPlan, request.committedAtMs)
                val result = curatorDao.transitionFenced(
                    current,
                    next,
                    CuratorDeltaRevisionReason.APPLY_STARTED,
                    CuratorDeltaRevisionActor.APPLY_ENGINE,
                )
                if (result !is CuratorDeltaMutationResult.Applied) {
                    abort(CuratorRuntimeConflict.CANDIDATE_FENCE_CONFLICT)
                }
                next
            }

            CuratorDeltaStoredState.APPLYING -> {
                if (current.stateVersion != request.expectedCandidateStateVersion + 1L ||
                    current.updatedAtMs != request.committedAtMs ||
                    current.decodeApplyPlanOrNull() != canonicalPlan ||
                    !hasExactCandidateRevision(
                        current.id,
                        current.stateVersion,
                        CuratorDeltaStoredState.APPLYING,
                        CuratorDeltaRevisionReason.APPLY_STARTED,
                        CuratorDeltaRevisionActor.APPLY_ENGINE,
                        request.committedAtMs,
                    )
                ) abort(CuratorRuntimeConflict.CANDIDATE_FENCE_CONFLICT)
                current
            }

            else -> abort(CuratorRuntimeConflict.CANDIDATE_STATE_CONFLICT)
        }

        // The candidate CAS above owns the SQLite write transaction. Re-read every involved head
        // after that CAS and before the first Policy mutation; output IDs must still be absent.
        requireExactApplyHeads(canonicalPlan)
        canonicalPlan.mutations.forEach { mutation ->
            applyPolicyMutation(
                mutation = mutation,
                operation = canonicalPlan.operation,
                primary = policySnapshot.primary,
                inheritedEvidence = policySnapshot.inheritedEvidence,
                committedAtMs = request.committedAtMs,
            )
        }

        val applied = applying.copy(
            stateVersion = applying.stateVersion + 1L,
            state = CuratorDeltaStoredState.APPLIED.name,
            conflictCode = null,
            updatedAtMs = request.committedAtMs,
        )
        if (curatorDao.markAppliedWithLineageFenced(applying, applied) !is
            CuratorDeltaMutationResult.Applied
        ) abort(CuratorRuntimeConflict.CANDIDATE_FENCE_CONFLICT)

        requireExactAppliedState(applied, canonicalPlan)
        return CuratorRuntimeMutationResult.Applied(
            operation = canonicalPlan.operation,
            candidateStateVersion = applied.stateVersion,
            applyPlanId = canonicalPlan.planId,
            applyPlanSha256 = requireNotNull(applied.applyPlanSha256),
            mutatedPolicyCount = canonicalPlan.mutations.size,
        )
    }

    private suspend fun rollbackInOpenTransaction(
        request: CuratorRuntimeRollbackRequest,
    ): CuratorRuntimeMutationResult {
        val curatorDao = database.curatorDeltaDao()
        val current = curatorDao.find(request.candidateId)
            ?: abort(CuratorRuntimeConflict.CANDIDATE_MISSING)
        requireCandidateIdentity(current, request)

        exactRolledBackDuplicateOrNull(current, request)?.let { return it }
        if (current.state != CuratorDeltaStoredState.APPLIED.name ||
            current.stateVersion != request.expectedCandidateStateVersion ||
            current.updatedAtMs != request.expectedCandidateUpdatedAtMs ||
            current.applyPlanId != request.expectedApplyPlanId ||
            current.applyPlanSha256 != request.expectedApplyPlanSha256
        ) abort(CuratorRuntimeConflict.CANDIDATE_FENCE_CONFLICT)
        val plan = current.decodeApplyPlanOrNull()
            ?: abort(CuratorRuntimeConflict.PLAN_INVALID)
        requirePlanMatchesCandidate(plan, current.decodeCandidateOrNull())
        requireExactAppliedState(current, plan)

        val rollingBack = current.copy(
            stateVersion = current.stateVersion + 1L,
            state = CuratorDeltaStoredState.ROLLING_BACK.name,
            conflictCode = null,
            updatedAtMs = request.committedAtMs,
        )
        if (curatorDao.transitionFenced(
                current,
                rollingBack,
                CuratorDeltaRevisionReason.ROLLBACK_STARTED,
                CuratorDeltaRevisionActor.ROLLBACK_ENGINE,
            ) !is CuratorDeltaMutationResult.Applied
        ) abort(CuratorRuntimeConflict.CANDIDATE_FENCE_CONFLICT)

        requireExactRollbackHeads(plan)
        plan.rollback.mutations.forEach { mutation ->
            applyPolicyMutation(
                mutation = mutation,
                operation = plan.operation,
                primary = null,
                inheritedEvidence = null,
                committedAtMs = request.committedAtMs,
                rollback = true,
            )
        }
        val rolledBack = rollingBack.copy(
            stateVersion = rollingBack.stateVersion + 1L,
            state = CuratorDeltaStoredState.ROLLED_BACK.name,
            conflictCode = null,
            updatedAtMs = request.committedAtMs,
        )
        if (curatorDao.markRolledBackWithLineageFenced(rollingBack, rolledBack) !is
            CuratorDeltaMutationResult.Applied
        ) abort(CuratorRuntimeConflict.CANDIDATE_FENCE_CONFLICT)

        requireExactRolledBackState(rolledBack, plan)
        return CuratorRuntimeMutationResult.RolledBack(
            operation = plan.operation,
            candidateStateVersion = rolledBack.stateVersion,
            applyPlanId = plan.planId,
            applyPlanSha256 = requireNotNull(rolledBack.applyPlanSha256),
            mutatedPolicyCount = plan.rollback.mutations.size,
        )
    }

    private suspend fun materializePlanningSnapshot(
        candidate: CuratorDeltaCandidate,
        committedAtMs: Long,
    ): PlanningSnapshot {
        val policyDao = database.policyDao()
        val sourceEntities = candidate.sources.sortedBy { it.policyId }.associate { fence ->
            val entity = policyDao.findPolicy(fence.policyId)
                ?: abort(CuratorRuntimeConflict.POLICY_HEAD_CONFLICT)
            if (entity.updatedAtMs > committedAtMs) abort(CuratorRuntimeConflict.CLOCK_CONFLICT)
            val audit = policyDao.findRevision(entity.id, entity.stateVersion)
                ?: abort(CuratorRuntimeConflict.POLICY_IDENTITY_CONFLICT)
            if (audit.afterArtifactSha256 != entity.artifactSha256) {
                abort(CuratorRuntimeConflict.POLICY_IDENTITY_CONFLICT)
            }
            fence.policyId to entity
        }
        val heads = sourceEntities.mapValues { (_, entity) ->
            entity.toProductionCuratorHeadOrNull()
                ?: abort(CuratorRuntimeConflict.POLICY_IDENTITY_CONFLICT)
        }.toMutableMap()
        candidate.outputPolicyIds().forEach { outputId ->
            if (policyDao.findPolicy(outputId) != null) {
                abort(CuratorRuntimeConflict.POLICY_HEAD_CONFLICT)
            }
        }
        val primary = sourceEntities.values.minBy(LearningPolicyEntity::id)
        if (sourceEntities.values.any { source ->
                source.scopeKind != primary.scopeKind || source.scopeId != primary.scopeId ||
                    source.taskSignature != primary.taskSignature ||
                    source.policyType != primary.policyType ||
                    source.applicableModelIdentityWire != primary.applicableModelIdentityWire ||
                    source.applicableProviderIdentityWire != primary.applicableProviderIdentityWire ||
                    source.applicableTemplateIdentity != primary.applicableTemplateIdentity ||
                    source.applicableConfigurationIdentity !=
                    primary.applicableConfigurationIdentity ||
                    source.applicableConfigurationGeneration !=
                    primary.applicableConfigurationGeneration ||
                    source.applicableCapabilityDigest != primary.applicableCapabilityDigest ||
                    source.applicableAuthorityDigest != primary.applicableAuthorityDigest ||
                    source.producerModelIdentity != primary.producerModelIdentity ||
                    source.producerProviderIdentity != primary.producerProviderIdentity ||
                    source.producerConfigurationIdentity !=
                    primary.producerConfigurationIdentity ||
                    source.producerConfigGeneration != primary.producerConfigGeneration ||
                    !source.sourceValid || !source.schemaValid ||
                    source.status !in CURATOR_MUTABLE_POLICY_STATES
            }
        ) abort(CuratorRuntimeConflict.POLICY_IDENTITY_CONFLICT)

        val inheritedPolicyByEpisode = linkedMapOf<String, PolicyEvidenceEntity>()
        val inheritedRewardByKey = linkedMapOf<Pair<String, String>, PolicyRewardEvidenceEntity>()
        sourceEntities.values.sortedBy(LearningPolicyEntity::id).forEach { source ->
            requireExactPolicyEvidence(source.id)
            val raw = policyDao.listEvidenceForCurator(source.id, CURATOR_EVIDENCE_LIMIT + 1)
            if (raw.size > CURATOR_EVIDENCE_LIMIT) {
                abort(CuratorRuntimeConflict.EVIDENCE_CONFLICT)
            }
            val validity = policyDao.listEvidenceValidity(
                source.id,
                CURATOR_EVIDENCE_LIMIT + 1,
            ).associateBy { it.episodeId }
            if (validity.size != raw.size || raw.any { validity[it.episodeId]?.sourceValid != true }) {
                abort(CuratorRuntimeConflict.EVIDENCE_CONFLICT)
            }
            raw.forEach { capsule ->
                val prior = inheritedPolicyByEpisode[capsule.episodeId]
                if (prior != null && !prior.sameInheritedCapsule(capsule)) {
                    abort(CuratorRuntimeConflict.EVIDENCE_CONFLICT)
                }
                inheritedPolicyByEpisode.putIfAbsent(capsule.episodeId, capsule)
                val rewards = database.rewardSignalDao().listPolicyRewardEvidence(
                    source.id,
                    capsule.episodeId,
                    CURATOR_REWARD_EVIDENCE_PER_EPISODE_LIMIT + 1,
                )
                if (rewards.isEmpty() ||
                    rewards.size > CURATOR_REWARD_EVIDENCE_PER_EPISODE_LIMIT
                ) abort(CuratorRuntimeConflict.EVIDENCE_CONFLICT)
                rewards.forEach { reward ->
                    val key = reward.episodeId to reward.rewardSignalId
                    val priorReward = inheritedRewardByKey[key]
                    if (priorReward != null && !priorReward.sameInheritedCapsule(reward)) {
                        abort(CuratorRuntimeConflict.EVIDENCE_CONFLICT)
                    }
                    inheritedRewardByKey.putIfAbsent(key, reward)
                    if (inheritedRewardByKey.size > CURATOR_REWARD_EVIDENCE_LIMIT) {
                        abort(CuratorRuntimeConflict.EVIDENCE_CONFLICT)
                    }
                }
            }
        }
        if (inheritedPolicyByEpisode.isEmpty() ||
            inheritedPolicyByEpisode.size > CURATOR_EVIDENCE_LIMIT
        ) abort(CuratorRuntimeConflict.EVIDENCE_CONFLICT)
        val inheritedEvidence = InheritedEvidenceBundle(
            policyEvidence = inheritedPolicyByEpisode.values.sortedBy { it.episodeId },
            rewardEvidence = inheritedRewardByKey.values.sortedWith(
                compareBy(PolicyRewardEvidenceEntity::episodeId)
                    .thenBy(PolicyRewardEvidenceEntity::rewardSignalId),
            ),
        )

        val curatorDao = database.curatorDeltaDao()
        val evidence = candidate.evidence.associateBy(CuratorEvidenceRef::evidenceId).mapValues {
            (_, claim) ->
            val exactCount = curatorDao.countExactValidEvidenceFence(
                evidenceId = claim.evidenceId,
                scopeKind = claim.scope.kind.name,
                scopeId = claim.scope.storageId,
                sourceRevision = claim.sourceRevision,
                integritySha256 = claim.integritySha256,
            )
            if (exactCount != 1) abort(CuratorRuntimeConflict.EVIDENCE_CONFLICT)
            claim
        }
        return PlanningSnapshot(sourceEntities, heads, evidence, primary, inheritedEvidence)
    }

    private fun planExact(
        candidate: CuratorDeltaCandidate,
        snapshot: PlanningSnapshot,
    ): CuratorApplyPlan {
        val primaryType = runCatching { PolicyCandidateType.valueOf(snapshot.primary.policyType) }
            .getOrElse { abort(CuratorRuntimeConflict.POLICY_IDENTITY_CONFLICT) }
        val applier = DeterministicCuratorDeltaApplier(
            CuratorArtifactIdentity { _, document ->
                document.canonicalPolicyArtifact(snapshot.primary, primaryType)
            },
        )
        return when (val result = applier.plan(
            candidate,
            { policyId -> snapshot.heads[policyId] },
            { evidenceId -> snapshot.evidence[evidenceId] },
        )) {
            is CuratorApplyResult.Ready -> result.plan.also {
                requirePlanMatchesCandidate(it, candidate)
            }
            is CuratorApplyResult.Conflict -> abort(result.reason.toRuntimeConflict())
            is CuratorApplyResult.RollbackReady -> abort(CuratorRuntimeConflict.PLAN_INVALID)
        }
    }

    private suspend fun applyPolicyMutation(
        mutation: CuratorPlannedMutation,
        operation: CuratorDeltaOperation,
        primary: LearningPolicyEntity?,
        inheritedEvidence: InheritedEvidenceBundle?,
        committedAtMs: Long,
        rollback: Boolean = false,
    ) {
        val policyDao = database.policyDao()
        when (mutation.kind) {
            CuratorMutationKind.INSERT -> {
                if (rollback) abort(CuratorRuntimeConflict.PLAN_INVALID)
                val after = requireNotNull(mutation.after)
                val template = primary ?: abort(CuratorRuntimeConflict.POLICY_IDENTITY_CONFLICT)
                if (policyDao.findPolicy(after.policyId) != null ||
                    policyDao.findPoliciesByArtifact(
                        after.scope.kind.name,
                        after.scope.storageId,
                        template.taskSignature,
                        after.artifactSha256,
                    ).isNotEmpty()
                ) abort(CuratorRuntimeConflict.POLICY_HEAD_CONFLICT)
                val inherited = inheritedEvidence
                    ?: abort(CuratorRuntimeConflict.EVIDENCE_CONFLICT)
                val inserted = template.newCuratorOutput(after, inherited, committedAtMs)
                policyDao.insertPolicy(inserted)
                inherited.policyEvidence.forEach { evidence ->
                    if (policyDao.insertEvidenceIgnore(
                            evidence.copy(policyId = inserted.id, createdAtMs = committedAtMs),
                        ) == -1L
                    ) abort(CuratorRuntimeConflict.EVIDENCE_CONFLICT)
                }
                inherited.rewardEvidence.forEach { evidence ->
                    if (database.rewardSignalDao().insertPolicyRewardEvidenceIgnore(
                            evidence.copy(policyId = inserted.id, createdAtMs = committedAtMs),
                        ) == -1L
                    ) abort(CuratorRuntimeConflict.EVIDENCE_CONFLICT)
                }
                policyDao.insertRevision(
                    inserted.curatorRevision(
                        before = null,
                        operation = operation,
                        committedAtMs = committedAtMs,
                        rollback = false,
                    ),
                )
            }

            CuratorMutationKind.UPDATE,
            CuratorMutationKind.ARCHIVE,
            CuratorMutationKind.RESTORE,
            -> {
                val before = requireNotNull(mutation.before)
                val after = requireNotNull(mutation.after)
                val current = policyDao.findPolicy(before.policyId)
                    ?: abort(CuratorRuntimeConflict.POLICY_HEAD_CONFLICT)
                if (!current.exactlyMatches(before) ||
                    policyDao.findRevision(current.id, after.revision) != null
                ) abort(CuratorRuntimeConflict.POLICY_HEAD_CONFLICT)
                val next = current.nextCuratorHead(after, committedAtMs)
                val affected = policyDao.updateCuratorPolicyHeadIfExact(
                    policyId = current.id,
                    expectedScopeKind = current.scopeKind,
                    expectedScopeId = current.scopeId,
                    expectedStateVersion = current.stateVersion,
                    expectedContentRevision = current.contentRevision,
                    expectedArtifactSha256 = current.artifactSha256,
                    expectedStatus = current.status,
                    expectedUpdatedAtMs = current.updatedAtMs,
                    expectedApplicableModelIdentityWire = current.applicableModelIdentityWire,
                    expectedApplicableProviderIdentityWire = current.applicableProviderIdentityWire,
                    expectedApplicableTemplateIdentity =
                        requireNotNull(current.applicableTemplateIdentity),
                    expectedApplicableConfigurationIdentity =
                        requireNotNull(current.applicableConfigurationIdentity),
                    expectedApplicableConfigurationGeneration =
                        requireNotNull(current.applicableConfigurationGeneration),
                    expectedApplicableCapabilityDigest = current.applicableCapabilityDigest,
                    expectedApplicableAuthorityDigest = current.applicableAuthorityDigest,
                    triggerSummary = next.triggerSummary,
                    procedureSummary = next.procedureSummary,
                    verificationSummary = next.verificationSummary,
                    boundarySummary = next.boundarySummary,
                    failureModeSummary = next.failureModeSummary,
                    newStateVersion = next.stateVersion,
                    newContentRevision = next.contentRevision,
                    newArtifactSha256 = next.artifactSha256,
                    newStatus = next.status,
                    newApplicableToolSchemasWire = next.applicableToolSchemasWire,
                    newUsageCount = next.usageCount,
                    newLastUsedAtMs = next.lastUsedAtMs,
                    newObservedUtilityDelta = next.observedUtilityDelta,
                    newUtilityUncertainty = next.utilityUncertainty,
                    updatedAtMs = next.updatedAtMs,
                )
                if (affected != 1) abort(CuratorRuntimeConflict.POLICY_HEAD_CONFLICT)
                policyDao.insertRevision(
                    next.curatorRevision(current, operation, committedAtMs, rollback),
                )
            }
        }
    }

    private suspend fun requireExactApplyHeads(plan: CuratorApplyPlan) {
        val policyDao = database.policyDao()
        plan.mutations.forEach { mutation ->
            when (mutation.kind) {
                CuratorMutationKind.INSERT -> if (
                    policyDao.findPolicy(requireNotNull(mutation.after).policyId) != null
                ) abort(CuratorRuntimeConflict.POLICY_HEAD_CONFLICT)
                else -> {
                    val before = requireNotNull(mutation.before)
                    if (policyDao.findPolicy(before.policyId)?.exactlyMatches(before) != true) {
                        abort(CuratorRuntimeConflict.POLICY_HEAD_CONFLICT)
                    }
                }
            }
        }
    }

    private suspend fun requireExactRollbackHeads(plan: CuratorApplyPlan) {
        plan.rollback.mutations.forEach { mutation ->
            val before = requireNotNull(mutation.before)
            if (database.policyDao().findPolicy(before.policyId)?.exactlyMatches(before) != true) {
                abort(CuratorRuntimeConflict.POLICY_HEAD_CONFLICT)
            }
        }
        requireExactLineage(plan, active = true)
    }

    private suspend fun requireExactAppliedState(
        candidate: CuratorDeltaCandidateEntity,
        plan: CuratorApplyPlan,
    ) {
        if (candidate.state != CuratorDeltaStoredState.APPLIED.name ||
            candidate.applyPlanId != plan.planId || candidate.decodeApplyPlanOrNull() != plan
        ) abort(CuratorRuntimeConflict.CANDIDATE_FENCE_CONFLICT)
        plan.mutations.forEach { mutation ->
            val after = requireNotNull(mutation.after)
            if (database.policyDao().findPolicy(after.policyId)
                    ?.exactlyMatches(after, candidate.updatedAtMs) != true
            ) {
                abort(CuratorRuntimeConflict.POLICY_HEAD_CONFLICT)
            }
            requireExactPolicyEvidence(after.policyId)
        }
        requireExactLineage(plan, active = true)
    }

    private suspend fun requireExactRolledBackState(
        candidate: CuratorDeltaCandidateEntity,
        plan: CuratorApplyPlan,
    ) {
        if (candidate.state != CuratorDeltaStoredState.ROLLED_BACK.name ||
            candidate.applyPlanId != plan.planId
        ) abort(CuratorRuntimeConflict.CANDIDATE_FENCE_CONFLICT)
        plan.rollback.mutations.forEach { mutation ->
            val after = requireNotNull(mutation.after)
            if (database.policyDao().findPolicy(after.policyId)
                    ?.exactlyMatches(after, candidate.updatedAtMs) != true
            ) {
                abort(CuratorRuntimeConflict.POLICY_HEAD_CONFLICT)
            }
            requireExactPolicyEvidence(after.policyId)
        }
        requireExactLineage(plan, active = false)
    }

    private suspend fun requireExactLineage(plan: CuratorApplyPlan, active: Boolean) {
        val rows = database.curatorDeltaDao().listLineagePage(
            candidateId = plan.candidateId,
            afterParentPolicyId = "",
            afterChildPolicyId = "",
            afterRelationType = "",
            limit = 100,
        )
        if (rows.size != plan.lineage.size) abort(CuratorRuntimeConflict.LINEAGE_CONFLICT)
        val byEdge = rows.associateBy { row ->
            CuratorLineageEdge(
                row.parentPolicyId,
                row.childPolicyId,
                me.rerere.rikkahub.learning.curator.CuratorLineageRelation.valueOf(
                    row.relationType,
                ),
            )
        }
        plan.toLineageEntities(rows.firstOrNull()?.createdAtMs ?: 0L).forEach { expected ->
            val row = byEdge[CuratorLineageEdge(
                expected.parentPolicyId,
                expected.childPolicyId,
                me.rerere.rikkahub.learning.curator.CuratorLineageRelation.valueOf(
                    expected.relationType,
                ),
            )] ?: abort(CuratorRuntimeConflict.LINEAGE_CONFLICT)
            if (row.applyPlanId != plan.planId || row.parentRevision != expected.parentRevision ||
                row.parentArtifactSha256 != expected.parentArtifactSha256 ||
                row.childRevision != expected.childRevision ||
                row.childArtifactSha256 != expected.childArtifactSha256 || row.active != active ||
                row.stateVersion != if (active) 1L else 2L
            ) abort(CuratorRuntimeConflict.LINEAGE_CONFLICT)
        }
    }

    private suspend fun requireExactPolicyEvidence(policyId: String) {
        val policyDao = database.policyDao()
        val policy = policyDao.findPolicy(policyId)
            ?: abort(CuratorRuntimeConflict.POLICY_HEAD_CONFLICT)
        if (policy.distinctEpisodeSupport > CURATOR_EVIDENCE_LIMIT) {
            abort(CuratorRuntimeConflict.EVIDENCE_CONFLICT)
        }
        val validity = policyDao.listEvidenceValidity(policyId, CURATOR_EVIDENCE_LIMIT + 1)
        if (validity.size.toLong() != policy.distinctEpisodeSupport ||
            validity.any { !it.sourceValid } ||
            policyDao.countDistinctEpisodeSupportByPolarity(policyId, "POSITIVE") !=
            policy.positiveEpisodeCount ||
            policyDao.countDistinctEpisodeSupportByPolarity(policyId, "NEGATIVE") !=
            policy.negativeEpisodeCount
        ) abort(CuratorRuntimeConflict.EVIDENCE_CONFLICT)
        val rewards = database.rewardSignalDao().listPolicyRewardEvidenceValidity(
            policyId,
            CURATOR_REWARD_EVIDENCE_LIMIT + 1,
        )
        if (validity.isNotEmpty() &&
            (rewards.isEmpty() || rewards.size > CURATOR_REWARD_EVIDENCE_LIMIT ||
                rewards.any { !it.sourceValid } ||
                validity.any { evidence -> rewards.none { it.episodeId == evidence.episodeId } })
        ) abort(CuratorRuntimeConflict.EVIDENCE_CONFLICT)
    }

    private suspend fun exactAppliedDuplicateOrNull(
        current: CuratorDeltaCandidateEntity,
        request: CuratorRuntimeApplyRequest,
    ): CuratorRuntimeMutationResult.Duplicate? {
        if (current.state != CuratorDeltaStoredState.APPLIED.name) return null
        if (current.stateVersion != request.expectedCandidateStateVersion + 2L ||
            current.candidateSha256 != request.expectedCandidateSha256 ||
            current.operation != request.expectedOperation.name ||
            current.updatedAtMs != request.committedAtMs
        ) abort(CuratorRuntimeConflict.CANDIDATE_FENCE_CONFLICT)
        val plan = current.decodeApplyPlanOrNull()
            ?: abort(CuratorRuntimeConflict.PLAN_INVALID)
        requirePlanMatchesCandidate(plan, current.decodeCandidateOrNull())
        if (!hasExactCandidateRevision(
                current.id,
                current.stateVersion - 1L,
                CuratorDeltaStoredState.APPLYING,
                CuratorDeltaRevisionReason.APPLY_STARTED,
                CuratorDeltaRevisionActor.APPLY_ENGINE,
                request.committedAtMs,
            ) || !hasExactCandidateRevision(
                current.id,
                current.stateVersion,
                CuratorDeltaStoredState.APPLIED,
                CuratorDeltaRevisionReason.APPLY_COMMITTED,
                CuratorDeltaRevisionActor.APPLY_ENGINE,
                request.committedAtMs,
            )
        ) abort(CuratorRuntimeConflict.CANDIDATE_FENCE_CONFLICT)
        requireExactAppliedState(current, plan)
        return CuratorRuntimeMutationResult.Duplicate(
            plan.operation,
            current.stateVersion,
            plan.planId,
            CuratorRuntimeTerminalState.APPLIED,
        )
    }

    private suspend fun exactRolledBackDuplicateOrNull(
        current: CuratorDeltaCandidateEntity,
        request: CuratorRuntimeRollbackRequest,
    ): CuratorRuntimeMutationResult.Duplicate? {
        if (current.state != CuratorDeltaStoredState.ROLLED_BACK.name) return null
        if (current.stateVersion != request.expectedCandidateStateVersion + 2L ||
            current.candidateSha256 != request.expectedCandidateSha256 ||
            current.operation != request.expectedOperation.name ||
            current.applyPlanId != request.expectedApplyPlanId ||
            current.applyPlanSha256 != request.expectedApplyPlanSha256 ||
            current.updatedAtMs != request.committedAtMs
        ) abort(CuratorRuntimeConflict.CANDIDATE_FENCE_CONFLICT)
        val plan = current.decodeApplyPlanOrNull()
            ?: abort(CuratorRuntimeConflict.PLAN_INVALID)
        requirePlanMatchesCandidate(plan, current.decodeCandidateOrNull())
        if (!hasExactCandidateRevision(
                current.id,
                current.stateVersion - 1L,
                CuratorDeltaStoredState.ROLLING_BACK,
                CuratorDeltaRevisionReason.ROLLBACK_STARTED,
                CuratorDeltaRevisionActor.ROLLBACK_ENGINE,
                request.committedAtMs,
            ) || !hasExactCandidateRevision(
                current.id,
                current.stateVersion,
                CuratorDeltaStoredState.ROLLED_BACK,
                CuratorDeltaRevisionReason.ROLLBACK_COMMITTED,
                CuratorDeltaRevisionActor.ROLLBACK_ENGINE,
                request.committedAtMs,
            )
        ) abort(CuratorRuntimeConflict.CANDIDATE_FENCE_CONFLICT)
        requireExactRolledBackState(current, plan)
        return CuratorRuntimeMutationResult.Duplicate(
            plan.operation,
            current.stateVersion,
            plan.planId,
            CuratorRuntimeTerminalState.ROLLED_BACK,
        )
    }

    private suspend fun hasExactCandidateRevision(
        candidateId: String,
        version: Long,
        state: CuratorDeltaStoredState,
        reason: CuratorDeltaRevisionReason,
        actor: CuratorDeltaRevisionActor,
        createdAtMs: Long,
    ): Boolean {
        val revision = database.curatorDeltaDao().findRevision(candidateId, version) ?: return false
        return revision.state == state.name && revision.reasonCode == reason.name &&
            revision.actor == actor.name && revision.createdAtMs == createdAtMs
    }
}

private data class PlanningSnapshot(
    val sourceEntities: Map<String, LearningPolicyEntity>,
    val heads: Map<String, CuratorPolicyHead>,
    val evidence: Map<String, CuratorEvidenceRef>,
    val primary: LearningPolicyEntity,
    val inheritedEvidence: InheritedEvidenceBundle,
)

private data class InheritedEvidenceBundle(
    val policyEvidence: List<PolicyEvidenceEntity>,
    val rewardEvidence: List<PolicyRewardEvidenceEntity>,
) {
    init {
        require(policyEvidence.isNotEmpty() && policyEvidence.size <= CURATOR_EVIDENCE_LIMIT)
        require(policyEvidence.map(PolicyEvidenceEntity::episodeId).distinct().size ==
            policyEvidence.size)
        require(rewardEvidence.isNotEmpty() && rewardEvidence.size <= CURATOR_REWARD_EVIDENCE_LIMIT)
        require(rewardEvidence.all { reward ->
            policyEvidence.any { it.episodeId == reward.episodeId }
        })
    }
}

private fun requireCandidateIdentity(
    entity: CuratorDeltaCandidateEntity,
    request: CuratorRuntimeApplyRequest,
) {
    if (entity.operation != request.expectedOperation.name ||
        entity.candidateSha256 != request.expectedCandidateSha256
    ) abort(CuratorRuntimeConflict.CANDIDATE_FENCE_CONFLICT)
}

private fun requireCandidateIdentity(
    entity: CuratorDeltaCandidateEntity,
    request: CuratorRuntimeRollbackRequest,
) {
    if (entity.operation != request.expectedOperation.name ||
        entity.candidateSha256 != request.expectedCandidateSha256
    ) abort(CuratorRuntimeConflict.CANDIDATE_FENCE_CONFLICT)
}

private fun requireApprovedFence(
    entity: CuratorDeltaCandidateEntity,
    request: CuratorRuntimeApplyRequest,
) {
    if (entity.stateVersion != request.expectedCandidateStateVersion ||
        entity.state != CuratorDeltaStoredState.APPROVED.name ||
        entity.updatedAtMs != request.expectedCandidateUpdatedAtMs || entity.applyPlanId != null
    ) abort(CuratorRuntimeConflict.CANDIDATE_FENCE_CONFLICT)
}

private fun requirePlanMatchesCandidate(
    plan: CuratorApplyPlan,
    candidate: CuratorDeltaCandidate?,
) {
    candidate ?: abort(CuratorRuntimeConflict.PLAN_INVALID)
    if (plan.candidateId != candidate.candidateId || plan.operation != candidate.operation ||
        plan.sourceFences != candidate.sources.sortedBy { it.policyId } ||
        plan.evidence != candidate.evidence ||
        plan.diffs != candidate.diffs.sortedBy { it.targetPolicyId }
    ) abort(CuratorRuntimeConflict.PLAN_INVALID)
}

private fun CuratorDeltaCandidate.outputPolicyIds(): List<String> = when (this) {
    is CuratorDeltaCandidate.Update -> emptyList()
    is CuratorDeltaCandidate.Merge -> listOf(outputPolicyId)
    is CuratorDeltaCandidate.Split -> outputs.map { it.policyId }
    is CuratorDeltaCandidate.Supersede -> listOf(replacementPolicyId)
}

internal fun LearningPolicyEntity.toProductionCuratorHeadOrNull(): CuratorPolicyHead? = runCatching {
    if (!sourceValid || !schemaValid || status !in CURATOR_MUTABLE_POLICY_STATES) return null
    val scope = requireNotNull(LearningScope.parseOrNull(scopeKind, scopeId))
    val document = CuratorPolicyDocument(
        trigger = triggerSummary,
        procedure = procedureSummary,
        verification = verificationSummary,
        boundary = boundarySummary,
        failureMode = failureModeSummary,
        applicableToolSchemaSha256 = requireNotNull(
            PolicyApplicabilityWire.decodeToolSchemasOrNull(applicableToolSchemasWire),
        ).sorted(),
    )
    val type = PolicyCandidateType.valueOf(policyType)
    require(policyArtifactSha256(this) == artifactSha256)
    CuratorPolicyHead(
        policyId = id,
        scope = scope,
        revision = stateVersion,
        state = if (status == CURATOR_CANDIDATE_STATUS) {
            CuratorPolicyState.CANDIDATE
        } else {
            CuratorPolicyState.REVIEWED
        },
        document = document,
        artifactSha256 = artifactSha256,
        storageStateCode = status,
    )
}.getOrNull()

private fun LearningPolicyEntity.exactlyMatches(
    head: CuratorPolicyHead,
    expectedUpdatedAtMs: Long? = null,
): Boolean {
    if (id != head.policyId || scopeKind != head.scope.kind.name ||
        scopeId != head.scope.storageId || stateVersion != head.revision ||
        artifactSha256 != head.artifactSha256 || status != head.storageStateCode ||
        (expectedUpdatedAtMs != null && updatedAtMs != expectedUpdatedAtMs)
    ) return false
    val conceptualStateMatches = when (head.state) {
        CuratorPolicyState.CANDIDATE -> status == CURATOR_CANDIDATE_STATUS
        CuratorPolicyState.REVIEWED -> status in CURATOR_REVIEWED_POLICY_STATES
        CuratorPolicyState.ARCHIVED,
        CuratorPolicyState.SUPERSEDED,
        -> status == CURATOR_ARCHIVED_STATUS
    }
    if (!conceptualStateMatches) return false
    val tools = runCatching {
        PolicyApplicabilityWire.decodeToolSchemasOrNull(applicableToolSchemasWire)?.sorted()
    }.getOrNull() ?: return false
    if (triggerSummary != head.document.trigger || procedureSummary != head.document.procedure ||
        verificationSummary != head.document.verification ||
        boundarySummary != head.document.boundary ||
        failureModeSummary != head.document.failureMode ||
        tools != head.document.applicableToolSchemaSha256
    ) return false
    val type = runCatching { PolicyCandidateType.valueOf(policyType) }.getOrNull() ?: return false
    return policyArtifactSha256(this) == artifactSha256
}

private fun LearningPolicyEntity.newCuratorOutput(
    after: CuratorPolicyHead,
    inherited: InheritedEvidenceBundle,
    committedAtMs: Long,
): LearningPolicyEntity {
    require(after.revision == 1L && after.storageStateCode == CURATOR_CANDIDATE_STATUS)
    val type = PolicyCandidateType.valueOf(policyType)
    require(after.document.canonicalPolicyArtifact(this, type) == after.artifactSha256)
    return copy(
        id = after.policyId,
        scopeKind = after.scope.kind.name,
        scopeId = after.scope.storageId,
        triggerSummary = after.document.trigger,
        procedureSummary = after.document.procedure,
        verificationSummary = after.document.verification,
        boundarySummary = after.document.boundary,
        failureModeSummary = after.document.failureMode,
        stateVersion = 1L,
        contentRevision = 1L,
        artifactSha256 = after.artifactSha256,
        status = CURATOR_CANDIDATE_STATUS,
        sourceValid = true,
        schemaValid = true,
        applicableToolSchemasWire = PolicyApplicabilityWire.encodeToolSchemas(
            after.document.applicableToolSchemaSha256.toSet(),
        ),
        // Output keeps the exact applicability/cohort frozen by its reviewed primary source.
        applicableModelIdentityWire = applicableModelIdentityWire,
        applicableProviderIdentityWire = applicableProviderIdentityWire,
        applicableTemplateIdentity = applicableTemplateIdentity,
        applicableConfigurationIdentity = applicableConfigurationIdentity,
        applicableConfigurationGeneration = applicableConfigurationGeneration,
        applicableCapabilityDigest = applicableCapabilityDigest,
        applicableAuthorityDigest = applicableAuthorityDigest,
        staleReason = null,
        distinctEpisodeSupport = inherited.policyEvidence.size.toLong(),
        positiveEpisodeCount = inherited.policyEvidence.count {
            it.polarity == "POSITIVE"
        }.toLong(),
        negativeEpisodeCount = inherited.policyEvidence.count {
            it.polarity == "NEGATIVE"
        }.toLong(),
        usageCount = 0L,
        confidence = 0.0,
        observedUtilityDelta = null,
        utilityUncertainty = null,
        createdAtMs = committedAtMs,
        updatedAtMs = committedAtMs,
        lastUsedAtMs = null,
    )
}

private fun LearningPolicyEntity.nextCuratorHead(
    after: CuratorPolicyHead,
    committedAtMs: Long,
): LearningPolicyEntity {
    require(after.policyId == id && after.scope.kind.name == scopeKind &&
        after.scope.storageId == scopeId && after.revision == stateVersion + 1L)
    require(after.storageStateCode in CURATOR_WRITABLE_POLICY_STATES)
    val type = PolicyCandidateType.valueOf(policyType)
    require(after.document.canonicalPolicyArtifact(this, type) == after.artifactSha256)
    val contentChanged = artifactSha256 != after.artifactSha256
    if (contentChanged && contentRevision == Long.MAX_VALUE) {
        abort(CuratorRuntimeConflict.REVISION_OVERFLOW)
    }
    return copy(
        triggerSummary = after.document.trigger,
        procedureSummary = after.document.procedure,
        verificationSummary = after.document.verification,
        boundarySummary = after.document.boundary,
        failureModeSummary = after.document.failureMode,
        stateVersion = after.revision,
        contentRevision = contentRevision + if (contentChanged) 1L else 0L,
        artifactSha256 = after.artifactSha256,
        status = requireNotNull(after.storageStateCode),
        applicableToolSchemasWire = PolicyApplicabilityWire.encodeToolSchemas(
            after.document.applicableToolSchemaSha256.toSet(),
        ),
        staleReason = null,
        usageCount = if (contentChanged) 0L else usageCount,
        lastUsedAtMs = if (contentChanged) null else lastUsedAtMs,
        observedUtilityDelta = if (contentChanged) null else observedUtilityDelta,
        utilityUncertainty = if (contentChanged) null else utilityUncertainty,
        updatedAtMs = committedAtMs,
    )
}

private fun LearningPolicyEntity.curatorRevision(
    before: LearningPolicyEntity?,
    operation: CuratorDeltaOperation,
    committedAtMs: Long,
    rollback: Boolean,
): PolicyRevisionEntity = PolicyRevisionEntity(
    policyId = id,
    revision = stateVersion,
    beforeSnapshot = before?.curatorSnapshot(),
    afterSnapshot = curatorSnapshot(),
    beforeArtifactSha256 = before?.artifactSha256,
    afterArtifactSha256 = artifactSha256,
    reasonCode = if (rollback) {
        LearningPolicyRevisionReason.CURATOR_ROLLBACK.name
    } else {
        operation.policyRevisionReason().name
    },
    actor = LearningPolicyRevisionActor.CURATOR_REVIEW.name,
    createdAtMs = committedAtMs,
)

private fun LearningPolicyEntity.curatorSnapshot(): String = listOf(
    "curator-policy-snapshot-v1",
    "state_version=$stateVersion",
    "content_revision=$contentRevision",
    "status=$status",
    "source_valid=$sourceValid",
    "schema_valid=$schemaValid",
    "artifact=$artifactSha256",
    "applicable_tools=$applicableToolSchemasWire",
    "content=redacted",
).joinToString("\n")

private fun CuratorDeltaOperation.policyRevisionReason(): LearningPolicyRevisionReason = when (this) {
    CuratorDeltaOperation.UPDATE_CANDIDATE -> LearningPolicyRevisionReason.CURATOR_UPDATE
    CuratorDeltaOperation.MERGE_CANDIDATE -> LearningPolicyRevisionReason.MERGE
    CuratorDeltaOperation.SPLIT_CANDIDATE -> LearningPolicyRevisionReason.CURATOR_SPLIT
    CuratorDeltaOperation.SUPERSEDE_CANDIDATE -> LearningPolicyRevisionReason.SUPERSEDE
}

internal fun CuratorPolicyDocument.canonicalPolicyArtifact(
    source: LearningPolicyEntity,
    type: PolicyCandidateType,
): String =
    policyArtifactSha256(
        type = type,
        trigger = trigger,
        procedure = procedure,
        verification = verification,
        boundary = boundary,
        failureMode = failureMode,
        applicableToolSchemas = applicableToolSchemaSha256.toSet(),
        applicableModelIdentity = (PolicyApplicabilityWire.decodeIdentity(
            source.applicableModelIdentityWire,
        ) as me.rerere.rikkahub.learning.storage.PolicyIdentityApplicability.Exact).identity,
        applicableProviderIdentity = (PolicyApplicabilityWire.decodeIdentity(
            source.applicableProviderIdentityWire,
        ) as me.rerere.rikkahub.learning.storage.PolicyIdentityApplicability.Exact).identity,
        applicableTemplateIdentity = requireNotNull(source.applicableTemplateIdentity),
        applicableConfigurationIdentity = requireNotNull(source.applicableConfigurationIdentity),
        applicableConfigurationGeneration =
            requireNotNull(source.applicableConfigurationGeneration),
        applicableCapabilityDigest = source.applicableCapabilityDigest,
        applicableAuthorityDigest = source.applicableAuthorityDigest,
    )

private fun PolicyEvidenceEntity.sameInheritedCapsule(other: PolicyEvidenceEntity): Boolean =
    copy(policyId = "comparison", createdAtMs = 0L) ==
        other.copy(policyId = "comparison", createdAtMs = 0L)

private fun PolicyRewardEvidenceEntity.sameInheritedCapsule(
    other: PolicyRewardEvidenceEntity,
): Boolean = copy(policyId = "comparison", createdAtMs = 0L) ==
    other.copy(policyId = "comparison", createdAtMs = 0L)

private fun CuratorConflictReason.toRuntimeConflict(): CuratorRuntimeConflict = when (this) {
    CuratorConflictReason.EVIDENCE_MISSING,
    CuratorConflictReason.EVIDENCE_CONFLICT,
    -> CuratorRuntimeConflict.EVIDENCE_CONFLICT
    CuratorConflictReason.REVISION_OVERFLOW -> CuratorRuntimeConflict.REVISION_OVERFLOW
    CuratorConflictReason.POLICY_IDENTITY_CONFLICT ->
        CuratorRuntimeConflict.POLICY_IDENTITY_CONFLICT
    CuratorConflictReason.INVALID_CANDIDATE,
    CuratorConflictReason.DUPLICATE_SOURCE,
    CuratorConflictReason.DIFF_CONFLICT,
    -> CuratorRuntimeConflict.PLAN_INVALID
    CuratorConflictReason.SOURCE_MISSING,
    CuratorConflictReason.SOURCE_STATE_CONFLICT,
    CuratorConflictReason.SCOPE_CONFLICT,
    CuratorConflictReason.REVISION_CONFLICT,
    CuratorConflictReason.BASE_HASH_CONFLICT,
    CuratorConflictReason.OUTPUT_ID_CONFLICT,
    CuratorConflictReason.ROLLBACK_FENCE_CONFLICT,
    -> CuratorRuntimeConflict.POLICY_HEAD_CONFLICT
}

private inline fun runRuntimeTransaction(
    operation: CuratorDeltaOperation,
    block: () -> CuratorRuntimeMutationResult,
): CuratorRuntimeMutationResult = try {
    block()
} catch (abort: RuntimeAbort) {
    CuratorRuntimeMutationResult.Conflict(abort.reason, operation)
}

private class RuntimeAbort(val reason: CuratorRuntimeConflict) : RuntimeException(
    "Curator runtime transaction aborted: ${reason.name}",
)

private fun abort(reason: CuratorRuntimeConflict): Nothing = throw RuntimeAbort(reason)

private const val CURATOR_CANDIDATE_STATUS = "CANDIDATE"
private const val CURATOR_ARCHIVED_STATUS = "ARCHIVED"
private val CURATOR_REVIEWED_POLICY_STATES = setOf("SHADOW", "PROBATION", "ACTIVE")
private val CURATOR_MUTABLE_POLICY_STATES = CURATOR_REVIEWED_POLICY_STATES + CURATOR_CANDIDATE_STATUS
private val CURATOR_WRITABLE_POLICY_STATES = CURATOR_MUTABLE_POLICY_STATES + CURATOR_ARCHIVED_STATUS
private const val CURATOR_EVIDENCE_LIMIT = 256
private const val CURATOR_REWARD_EVIDENCE_PER_EPISODE_LIMIT = 64
private const val CURATOR_REWARD_EVIDENCE_LIMIT = 512
