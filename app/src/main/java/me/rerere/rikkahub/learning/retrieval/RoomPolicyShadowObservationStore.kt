package me.rerere.rikkahub.learning.retrieval

import androidx.room.withTransaction
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.FrozenP1PolicyShadowAdmissionGate
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.policy.MAX_SHADOW_ADMISSION_EVIDENCE
import me.rerere.rikkahub.learning.policy.PolicyCandidateType
import me.rerere.rikkahub.learning.policy.PolicyEvidencePolarity
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.policy.PolicyMutationResult
import me.rerere.rikkahub.learning.policy.PolicyMutationStore
import me.rerere.rikkahub.learning.policy.PolicyShadowAdmissionDecision
import me.rerere.rikkahub.learning.policy.PolicyShadowAdmissionFacts
import me.rerere.rikkahub.learning.policy.PolicyShadowLifecycle
import me.rerere.rikkahub.learning.policy.PolicyShadowPromotionCommand
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.storage.LearningPolicyShadowObservationEntity
import me.rerere.rikkahub.learning.storage.LearningPolicyShadowObservationItemEntity
import me.rerere.rikkahub.learning.storage.RoomPolicyLifecycleMutationStore
import me.rerere.rikkahub.learning.storage.StoredLearningPolicyStatus
import me.rerere.rikkahub.learning.storage.encodePolicyShadowDropReasonCounts

internal enum class PolicyShadowObservationCommitFailure {
    ROLLOUT_DISABLED,
    REQUEST_IDENTITY_CONFLICT,
    POLICY_IDENTITY_CONFLICT,
    ADMISSION_REJECTED,
    LIFECYCLE_CONFLICT,
}

internal sealed interface PolicyShadowObservationCommitResult {
    data class Committed(val trace: PolicyRetrievalTrace) : PolicyShadowObservationCommitResult
    data class Duplicate(val trace: PolicyRetrievalTrace) : PolicyShadowObservationCommitResult
    data class Rejected(val failure: PolicyShadowObservationCommitFailure) :
        PolicyShadowObservationCommitResult
}

/**
 * Stage-D durable boundary. Retrieval completes in memory first; this store re-reads every selected
 * Policy head and all evidence, applies the frozen admission invariant, performs any
 * CANDIDATE->SHADOW CAS, and inserts the request/items in one Room transaction.
 */
internal class RoomPolicyShadowObservationStore(
    private val database: LearningDatabase,
    private val admissionEnabled: () -> Boolean = { true },
) {
    suspend fun record(
        request: PolicyShadowRuntimeRequest,
        result: PolicyRetrievalResult,
        frozenNowMs: Long,
    ): PolicyShadowObservationCommitResult {
        require(frozenNowMs >= 0L)
        require(result.hits.size == result.trace.selectedCount)
        require(result.hits.map { it.candidate.policyId }.distinct().size == result.hits.size)
        require(result.hits.map(PolicyRetrievalHit::rank) == (1..result.hits.size).toList())
        require(result.hits.sumOf { it.candidate.estimatedTokens } == result.trace.estimatedTokens)
        require(result.hits.size <= request.retrieval.maxCandidates)
        require(result.trace.estimatedTokens <= request.retrieval.maxEstimatedTokens)
        return try {
            database.withTransaction {
                if (!admissionEnabled()) {
                    abort(PolicyShadowObservationCommitFailure.ROLLOUT_DISABLED)
                }
                val observationDao = database.policyShadowObservationDao()
                val existing = observationDao.findObservation(request.requestIdentity)
                if (existing != null) {
                    return@withTransaction if (
                        existing.exactlyMatches(request, result.trace) &&
                        observationDao.listItems(
                            request.requestIdentity,
                            MAX_SHADOW_ITEMS_PLUS_ONE,
                        ).let { stored ->
                            stored.size == result.hits.size && stored.zip(result.hits).all {
                                (item, hit) -> item.matchesDuplicateHit(hit)
                            }
                        }
                    ) {
                        PolicyShadowObservationCommitResult.Duplicate(result.trace)
                    } else {
                        abort(
                            PolicyShadowObservationCommitFailure.REQUEST_IDENTITY_CONFLICT,
                        )
                    }
                }

                val lifecycleStore = RoomPolicyLifecycleMutationStore(database)
                val lifecycle = PolicyShadowLifecycle(
                    PolicyMutationStore { mutation ->
                        lifecycleStore.mutateInOpenTransaction(mutation)
                    },
                )
                val committedItems = mutableListOf<LearningPolicyShadowObservationItemEntity>()
                for (hit in result.hits) {
                    if (!admissionEnabled()) {
                        abort(PolicyShadowObservationCommitFailure.ROLLOUT_DISABLED)
                    }
                    var policy = database.policyDao().findPolicy(hit.candidate.policyId)
                        ?: abort(PolicyShadowObservationCommitFailure.POLICY_IDENTITY_CONFLICT)
                    if (!policy.matchesRetrievedIdentity(request.retrieval.scope, hit)) {
                        abort(PolicyShadowObservationCommitFailure.POLICY_IDENTITY_CONFLICT)
                    }
                    val admission = policy.admissionDecision(request.admissionGateIdentity)
                    if (admission !is PolicyShadowAdmissionDecision.Eligible) {
                        abort(PolicyShadowObservationCommitFailure.ADMISSION_REJECTED)
                    }
                    if (policy.status == LearningPolicyStatus.CANDIDATE.name) {
                        when (
                            lifecycle.promote(
                                PolicyShadowPromotionCommand(
                                    fence = PolicyMutationFence(
                                        policyId = policy.id,
                                        scope = request.retrieval.scope,
                                        expectedRevision = policy.stateVersion,
                                        expectedContentRevision = policy.contentRevision,
                                        expectedArtifactHash = policy.artifactSha256,
                                    ),
                                    frozenNowMs = frozenNowMs,
                                    admissionGateIdentity = request.admissionGateIdentity,
                                ),
                            )
                        ) {
                            is PolicyMutationResult.Applied,
                            is PolicyMutationResult.Duplicate,
                            -> Unit
                            is PolicyMutationResult.Conflict -> abort(
                                PolicyShadowObservationCommitFailure.LIFECYCLE_CONFLICT,
                            )
                        }
                        policy = database.policyDao().findPolicy(policy.id)
                            ?: abort(PolicyShadowObservationCommitFailure.POLICY_IDENTITY_CONFLICT)
                    }
                    val expectedCommittedStateVersion = when (hit.candidate.status) {
                        LearningPolicyStatus.CANDIDATE ->
                            hit.candidate.stateVersion.takeIf { it < Long.MAX_VALUE }?.plus(1L)
                        LearningPolicyStatus.SHADOW -> hit.candidate.stateVersion
                        else -> null
                    }
                    val committedAdmission = policy.admissionDecision(
                        request.admissionGateIdentity,
                    )
                    if (policy.status != StoredLearningPolicyStatus.SHADOW.name ||
                        expectedCommittedStateVersion == null ||
                        !policy.matchesRetrievedIdentity(
                            request.retrieval.scope,
                            hit,
                            expectedStateVersion = expectedCommittedStateVersion,
                        ) ||
                        committedAdmission !is PolicyShadowAdmissionDecision.Eligible
                    ) {
                        abort(PolicyShadowObservationCommitFailure.POLICY_IDENTITY_CONFLICT)
                    }
                    committedItems += LearningPolicyShadowObservationItemEntity(
                        requestIdentity = request.requestIdentity,
                        policyId = policy.id,
                        policyStateVersion = policy.stateVersion,
                        policyContentRevision = policy.contentRevision,
                        artifactSha256 = policy.artifactSha256,
                        lifecycleStatus = policy.status,
                        rank = hit.rank,
                        exactTaskMatch = hit.exactTaskMatch,
                        lexicalScoreMicros = (hit.lexicalScore * SCORE_MICROS).toInt()
                            .coerceIn(0, SCORE_MICROS),
                        estimatedTokens = hit.candidate.estimatedTokens,
                    )
                }

                if (!admissionEnabled()) {
                    abort(PolicyShadowObservationCommitFailure.ROLLOUT_DISABLED)
                }
                val observation = result.trace.toEntity(request, frozenNowMs)
                if (observationDao.insertObservationIgnore(observation) == -1L) {
                    abort(PolicyShadowObservationCommitFailure.REQUEST_IDENTITY_CONFLICT)
                }
                if (committedItems.isNotEmpty()) observationDao.insertItems(committedItems)
                check(observationDao.findObservation(request.requestIdentity) == observation)
                check(
                    observationDao.listItems(request.requestIdentity, MAX_SHADOW_ITEMS_PLUS_ONE) ==
                        committedItems,
                )
                PolicyShadowObservationCommitResult.Committed(result.trace)
            }
        } catch (rollback: PolicyShadowCommitRollback) {
            PolicyShadowObservationCommitResult.Rejected(rollback.failure)
        }
    }

    private suspend fun LearningPolicyEntity.admissionDecision(
        gateIdentity: String,
    ): PolicyShadowAdmissionDecision {
        val evidence = database.policyDao().listEvidenceValidity(
            id,
            MAX_SHADOW_ADMISSION_EVIDENCE + 1,
        )
        if (evidence.size > MAX_SHADOW_ADMISSION_EVIDENCE) {
            return PolicyShadowAdmissionDecision.Rejected(
                me.rerere.rikkahub.learning.policy.PolicyShadowAdmissionFailure.EVIDENCE_INVALID,
            )
        }
        val valid = evidence.filter { it.sourceValid }
        return FrozenP1PolicyShadowAdmissionGate.evaluate(
            PolicyShadowAdmissionFacts(
                gateIdentity = gateIdentity,
                status = runCatching { LearningPolicyStatus.valueOf(status) }.getOrElse {
                    return PolicyShadowAdmissionDecision.Rejected(
                        me.rerere.rikkahub.learning.policy.PolicyShadowAdmissionFailure
                            .STATUS_INELIGIBLE,
                    )
                },
                policyType = runCatching { PolicyCandidateType.valueOf(policyType) }.getOrElse {
                    return PolicyShadowAdmissionDecision.Rejected(
                        me.rerere.rikkahub.learning.policy.PolicyShadowAdmissionFailure
                            .AUTHORITY_POLARITY_MISSING,
                    )
                },
                sourceValid = sourceValid,
                schemaValid = schemaValid,
                distinctEpisodeSupport = distinctEpisodeSupport,
                positiveEpisodeCount = positiveEpisodeCount,
                negativeEpisodeCount = negativeEpisodeCount,
                evidenceEpisodeIds = evidence.map { it.episodeId },
                validEvidenceEpisodeIds = valid.mapTo(linkedSetOf()) { it.episodeId },
                positiveEvidenceEpisodeIds = valid.filter {
                    it.polarity == PolicyEvidencePolarity.POSITIVE.name
                }.mapTo(linkedSetOf()) { it.episodeId },
                negativeEvidenceEpisodeIds = valid.filter {
                    it.polarity == PolicyEvidencePolarity.NEGATIVE.name
                }.mapTo(linkedSetOf()) { it.episodeId },
                usageCount = usageCount,
                observedUtilityDelta = observedUtilityDelta,
                utilityUncertainty = utilityUncertainty,
            ),
        )
    }
}

private fun LearningPolicyEntity.matchesRetrievedIdentity(
    scope: LearningScope,
    hit: PolicyRetrievalHit,
    expectedStateVersion: Long = hit.candidate.stateVersion,
): Boolean = id == hit.candidate.policyId &&
    scopeKind == scope.kind.name && scopeId == scope.storageId &&
    taskSignature == hit.candidate.taskSignature.value &&
    stateVersion == expectedStateVersion &&
    contentRevision == hit.candidate.contentRevision &&
    artifactSha256 == hit.candidate.artifactHash &&
    sourceValid && schemaValid &&
    applicableToolSchemasWire.startsWith("EXACT_V1:") &&
    applicableModelIdentityWire.startsWith("EXACT_V1:") &&
    applicableProviderIdentityWire.startsWith("EXACT_V1:") &&
    applicableTemplateIdentity != null &&
    applicableConfigurationIdentity != null &&
    applicableConfigurationGeneration?.let { it > 0L } == true &&
    status in setOf(
        StoredLearningPolicyStatus.CANDIDATE.name,
        StoredLearningPolicyStatus.SHADOW.name,
    )

private fun LearningPolicyShadowObservationItemEntity.matchesDuplicateHit(
    hit: PolicyRetrievalHit,
): Boolean {
    val expectedStateVersion = when (hit.candidate.status) {
        LearningPolicyStatus.CANDIDATE ->
            hit.candidate.stateVersion.takeIf { it < Long.MAX_VALUE }?.plus(1L)
        LearningPolicyStatus.SHADOW -> hit.candidate.stateVersion
        else -> null
    }
    return expectedStateVersion != null && policyId == hit.candidate.policyId &&
        policyStateVersion == expectedStateVersion &&
        policyContentRevision == hit.candidate.contentRevision &&
        artifactSha256 == hit.candidate.artifactHash && rank == hit.rank &&
        exactTaskMatch == hit.exactTaskMatch &&
        lexicalScoreMicros == (hit.lexicalScore * SCORE_MICROS).toInt()
            .coerceIn(0, SCORE_MICROS) &&
        estimatedTokens == hit.candidate.estimatedTokens &&
        lifecycleStatus == StoredLearningPolicyStatus.SHADOW.name
}

private fun PolicyRetrievalTrace.toEntity(
    request: PolicyShadowRuntimeRequest,
    frozenNowMs: Long,
) = LearningPolicyShadowObservationEntity(
    requestIdentity = request.requestIdentity,
    scopeKind = request.retrieval.scope.kind.name,
    scopeId = request.retrieval.scope.storageId,
    taskSignature = request.retrieval.taskSignature.value,
    gateIdentity = request.admissionGateIdentity,
    queryTermCount = queryTermCount,
    exactCandidateCount = exactCandidateCount,
    lexicalCandidateCount = lexicalCandidateCount,
    selectedCount = selectedCount,
    estimatedTokens = estimatedTokens,
    latencyMicros = latencyMicros,
    dropReasonCountsWire = encodePolicyShadowDropReasonCounts(dropReasonCounts),
    observedAtMs = frozenNowMs,
)

private fun LearningPolicyShadowObservationEntity.exactlyMatches(
    request: PolicyShadowRuntimeRequest,
    trace: PolicyRetrievalTrace,
): Boolean = requestIdentity == request.requestIdentity &&
    scopeKind == request.retrieval.scope.kind.name && scopeId == request.retrieval.scope.storageId &&
    taskSignature == request.retrieval.taskSignature.value &&
    gateIdentity == request.admissionGateIdentity &&
    queryTermCount == trace.queryTermCount && exactCandidateCount == trace.exactCandidateCount &&
    lexicalCandidateCount == trace.lexicalCandidateCount && selectedCount == trace.selectedCount &&
    estimatedTokens == trace.estimatedTokens && latencyMicros == trace.latencyMicros &&
    dropReasonCountsWire == encodePolicyShadowDropReasonCounts(trace.dropReasonCounts)

private class PolicyShadowCommitRollback(
    val failure: PolicyShadowObservationCommitFailure,
) : RuntimeException(null, null, false, false)

private fun abort(failure: PolicyShadowObservationCommitFailure): Nothing =
    throw PolicyShadowCommitRollback(failure)

private const val MAX_SHADOW_ITEMS_PLUS_ONE = 21
private const val SCORE_MICROS = 1_000_000
