package me.rerere.rikkahub.learning.storage

import androidx.room.withTransaction
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.policy.PolicyLifecycle
import me.rerere.rikkahub.learning.policy.PolicyLifecycleFailure
import me.rerere.rikkahub.learning.policy.PolicyLifecycleReason
import me.rerere.rikkahub.learning.policy.PolicyLifecycleResult
import me.rerere.rikkahub.learning.policy.PolicyLifecycleState
import me.rerere.rikkahub.learning.policy.PolicyMutationConflict
import me.rerere.rikkahub.learning.policy.PolicyMutationRequest
import me.rerere.rikkahub.learning.policy.PolicyMutationResult
import me.rerere.rikkahub.learning.policy.PolicyMutationStore
import me.rerere.rikkahub.learning.policy.PolicyMutationTransaction
import me.rerere.rikkahub.learning.policy.ValidatingPolicyMutationStore

/**
 * Production Room adapter for the P1 local lifecycle seam. The caller supplies a frozen time and
 * revision/artifact fence; lookup, CAS, and immutable audit revision share exactly one transaction.
 * Candidate creation remains job-bound because its evidence and job DONE fence must commit together.
 */
class RoomPolicyLifecycleMutationStore(
    private val database: LearningDatabase,
) : PolicyMutationStore {
    override suspend fun mutate(request: PolicyMutationRequest): PolicyMutationResult =
        database.withTransaction {
            ValidatingPolicyMutationStore(
                PolicyMutationTransaction(::applyInOpenTransaction),
            ).mutate(request)
        }

    private suspend fun applyInOpenTransaction(
        request: PolicyMutationRequest,
    ): PolicyMutationResult {
        if (request !is PolicyMutationRequest.Transition ||
            request.target != LearningPolicyStatus.SHADOW
        ) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.INVALID_TRANSITION)
        }
        val fence = request.fence
        val dao = database.policyDao()
        val current = dao.findPolicy(fence.policyId)
            ?: return PolicyMutationResult.Conflict(PolicyMutationConflict.IDENTITY_CONFLICT)
        if (current.scopeKind != fence.scope.kind.name || current.scopeId != fence.scope.storageId) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.IDENTITY_CONFLICT)
        }

        // A replay after a committed promotion is a deterministic duplicate, not a second revision.
        if (current.status == StoredLearningPolicyStatus.SHADOW.name &&
            current.stateVersion > 1L &&
            current.stateVersion - 1L == fence.expectedRevision &&
            current.artifactSha256 == fence.expectedArtifactHash &&
            current.updatedAtMs == request.frozenNowMs
        ) {
            val revision = dao.findRevision(current.id, current.stateVersion)
            if (revision?.reasonCode == LearningPolicyRevisionReason.SHADOW_PROMOTION.name &&
                revision.beforeArtifactSha256 == fence.expectedArtifactHash &&
                revision.afterArtifactSha256 == fence.expectedArtifactHash
            ) {
                return PolicyMutationResult.Duplicate(current.id, current.stateVersion)
            }
        }
        if (current.stateVersion != fence.expectedRevision) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.REVISION_CONFLICT)
        }
        if (current.artifactSha256 != fence.expectedArtifactHash) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.ARTIFACT_CONFLICT)
        }
        if (current.status != StoredLearningPolicyStatus.CANDIDATE.name) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.INVALID_TRANSITION)
        }
        if (!current.sourceValid || !current.schemaValid) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.SOURCE_STALE)
        }
        if (request.frozenNowMs < current.updatedAtMs) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.INVALID_TRANSITION)
        }
        if (current.stateVersion == Long.MAX_VALUE) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.REVISION_CONFLICT)
        }
        val lifecycle = PolicyLifecycle.transition(
            current = PolicyLifecycleState(
                status = LearningPolicyStatus.CANDIDATE,
                revision = current.stateVersion,
                artifactHash = current.artifactSha256,
                reason = PolicyLifecycleReason.CREATED_FROM_VALIDATED_DRAFT,
                updatedAtMs = current.updatedAtMs,
                lastUsedAtMs = current.lastUsedAtMs,
                observedUtilityDelta = current.observedUtilityDelta,
                utilityUncertainty = current.utilityUncertainty,
            ),
            expectedRevision = fence.expectedRevision,
            expectedArtifactHash = fence.expectedArtifactHash,
            target = request.target,
            reason = request.reason,
            frozenNowMs = request.frozenNowMs,
        )
        val nextState = when (lifecycle) {
            is PolicyLifecycleResult.Applied -> lifecycle.state
            is PolicyLifecycleResult.Duplicate -> return PolicyMutationResult.Duplicate(
                current.id,
                lifecycle.state.revision,
            )
            is PolicyLifecycleResult.Rejected -> return PolicyMutationResult.Conflict(
                when (lifecycle.failure) {
                    PolicyLifecycleFailure.REVISION_CONFLICT ->
                        PolicyMutationConflict.REVISION_CONFLICT
                    PolicyLifecycleFailure.ARTIFACT_CONFLICT ->
                        PolicyMutationConflict.ARTIFACT_CONFLICT
                    PolicyLifecycleFailure.INVALID_TRANSITION,
                    PolicyLifecycleFailure.CLOCK_REGRESSION ->
                        PolicyMutationConflict.INVALID_TRANSITION
                },
            )
        }
        val changed = dao.updatePolicyIfCurrent(
            policyId = current.id,
            expectedStateVersion = current.stateVersion,
            expectedArtifactSha256 = current.artifactSha256,
            taskSignature = current.taskSignature,
            policyType = current.policyType,
            triggerSummary = current.triggerSummary,
            procedureSummary = current.procedureSummary,
            verificationSummary = current.verificationSummary,
            boundarySummary = current.boundarySummary,
            failureModeSummary = current.failureModeSummary,
            newArtifactSha256 = current.artifactSha256,
            compilerAbi = current.compilerAbi,
            status = StoredLearningPolicyStatus.SHADOW.name,
            sourceValid = true,
            schemaValid = true,
            staleReason = null,
            distinctEpisodeSupport = current.distinctEpisodeSupport,
            positiveEpisodeCount = current.positiveEpisodeCount,
            negativeEpisodeCount = current.negativeEpisodeCount,
            confidence = current.confidence,
            updatedAtMs = request.frozenNowMs,
        )
        if (changed != 1) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.REVISION_CONFLICT)
        }
        val promoted = current.copy(
            stateVersion = nextState.revision,
            status = nextState.status.name,
            updatedAtMs = nextState.updatedAtMs,
        )
        dao.insertRevision(
            PolicyRevisionEntity(
                policyId = promoted.id,
                revision = promoted.stateVersion,
                beforeSnapshot = current.lifecycleAuditSnapshot(),
                afterSnapshot = promoted.lifecycleAuditSnapshot(),
                beforeArtifactSha256 = current.artifactSha256,
                afterArtifactSha256 = promoted.artifactSha256,
                reasonCode = LearningPolicyRevisionReason.SHADOW_PROMOTION.name,
                actor = LearningPolicyRevisionActor.SYSTEM.name,
                createdAtMs = request.frozenNowMs,
            ),
        )
        check(dao.findPolicy(promoted.id) == promoted) { "Policy promotion CAS result changed" }
        return PolicyMutationResult.Applied(
            policyId = promoted.id,
            revision = promoted.stateVersion,
            status = LearningPolicyStatus.SHADOW,
        )
    }
}

private fun LearningPolicyEntity.lifecycleAuditSnapshot(): String = listOf(
    "policy-lifecycle-snapshot-v1",
    "status=$status",
    "source_valid=$sourceValid",
    "schema_valid=$schemaValid",
    "artifact=$artifactSha256",
    "support=$distinctEpisodeSupport",
    "positive=$positiveEpisodeCount",
    "negative=$negativeEpisodeCount",
    "confidence=$confidence",
).joinToString("\n")
