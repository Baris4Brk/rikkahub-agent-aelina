package me.rerere.rikkahub.learning.curator

import me.rerere.rikkahub.learning.model.DisabledLearningPositiveMutationGate
import me.rerere.rikkahub.learning.model.LearningPositiveMutationGate

/** Exact reviewed Policy identity captured by the explicit proposal surface. */
data class CuratorProductionSourceFence(
    val source: CuratorSourceFence,
    val expectedContentRevision: Long,
    val expectedStorageState: String,
    val expectedUpdatedAtMs: Long,
) {
    init {
        require(expectedContentRevision > 0L)
        require(expectedStorageState in CURATOR_PRODUCTION_REVIEWED_STATES)
        require(expectedUpdatedAtMs >= 0L)
    }
}

data class CuratorProductionSourceProjection(
    val exact: CuratorProductionSourceFence,
    val document: CuratorPolicyDocument,
    val policyType: String,
    val taskSignature: String,
    val evidence: List<CuratorEvidenceRef>,
) {
    init {
        require(policyType.length in 1..64)
        require(taskSignature.length in 1..256)
        require(evidence.isNotEmpty() && evidence.size <= MAX_CURATOR_EVIDENCE)
        require(evidence == evidence.sortedBy(CuratorEvidenceRef::evidenceId))
        require(evidence.all { it.scope == exact.source.scope })
    }

    override fun toString(): String =
        "CuratorProductionSourceProjection(state=${exact.expectedStorageState}, " +
            "evidence=${evidence.size}, content=<redacted>, ids=<redacted>)"
}

/**
 * A bounded, typed proposal submitted only after a user has reviewed the source Policy and delta.
 * The candidate may contain sanitized summaries, but it can only be persisted as PROPOSED. This
 * API intentionally exposes no approve/apply capability and accepts no model response envelope.
 */
data class CuratorCandidateProductionRequest(
    val candidate: CuratorDeltaCandidate,
    val exactSources: List<CuratorProductionSourceFence>,
    val explicitlyUserReviewed: Boolean,
    val proposedAtMs: Long,
) {
    init {
        require(explicitlyUserReviewed)
        require(proposedAtMs >= 0L)
        require(exactSources.size in 1..MAX_CURATOR_SOURCES)
        require(exactSources == exactSources.sortedBy { it.source.policyId })
        require(exactSources.map { it.source.policyId }.distinct().size == exactSources.size)
        require(candidate.sources.sortedBy(CuratorSourceFence::policyId) ==
            exactSources.map(CuratorProductionSourceFence::source))
    }

    override fun toString(): String =
        "CuratorCandidateProductionRequest(operation=${candidate.operation}, " +
            "sources=${exactSources.size}, content=<redacted>, ids=<redacted>)"
}

enum class CuratorCandidateProductionConflict {
    ROLLOUT_DISABLED,
    RUNTIME_UNAVAILABLE,
    SOURCE_MISSING,
    SOURCE_FENCE_CONFLICT,
    SOURCE_NOT_REVIEWED,
    SOURCE_SCOPE_CONFLICT,
    SOURCE_COMPATIBILITY_CONFLICT,
    EVIDENCE_MISSING,
    EVIDENCE_FENCE_CONFLICT,
    INVALID_DELTA,
    OUTPUT_ID_CONFLICT,
    IDENTITY_CONFLICT,
}

sealed interface CuratorCandidateProductionResult {
    data class Proposed(
        val candidateId: String,
        val candidateSha256: String,
        val stateVersion: Long,
        val proposedAtMs: Long,
    ) : CuratorCandidateProductionResult

    data class Duplicate(
        val candidateId: String,
        val state: String,
        val stateVersion: Long,
    ) : CuratorCandidateProductionResult

    data class Conflict(
        val reason: CuratorCandidateProductionConflict,
    ) : CuratorCandidateProductionResult
}

/** The sole production insertion boundary for a Curator v1 PROPOSED candidate. */
fun interface CuratorCandidateProductionStore {
    suspend fun propose(
        request: CuratorCandidateProductionRequest,
    ): CuratorCandidateProductionResult

    suspend fun listExactReviewedSources(
        consumingAssistantId: kotlin.uuid.Uuid,
        limit: Int = 40,
    ): List<CuratorProductionSourceProjection> = emptyList()
}

/** Stable typed entry point; review and apply remain separate explicit coordinators. */
class CuratorCandidateProductionCoordinator(
    private val store: CuratorCandidateProductionStore,
    private val positiveMutations: LearningPositiveMutationGate =
        DisabledLearningPositiveMutationGate,
) {
    suspend fun propose(
        request: CuratorCandidateProductionRequest,
    ): CuratorCandidateProductionResult {
        if (!positiveMutations.allows(request.candidate.operation)) {
            return CuratorCandidateProductionResult.Conflict(
                CuratorCandidateProductionConflict.ROLLOUT_DISABLED,
            )
        }
        return store.propose(request)
    }

    suspend fun listExactReviewedSources(
        consumingAssistantId: kotlin.uuid.Uuid,
        limit: Int = 40,
    ): List<CuratorProductionSourceProjection> =
        store.listExactReviewedSources(consumingAssistantId, limit)
}

val CURATOR_PRODUCTION_REVIEWED_STATES: Set<String> = setOf("SHADOW", "PROBATION", "ACTIVE")
