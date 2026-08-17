package me.rerere.rikkahub.learning.curator

import me.rerere.rikkahub.learning.model.DisabledLearningPositiveMutationGate
import me.rerere.rikkahub.learning.model.LearningPositiveMutationGate

/** Exact approved-candidate fence. No Policy text or evidence body crosses this API. */
data class CuratorRuntimeApplyRequest(
    val candidateId: String,
    val expectedOperation: CuratorDeltaOperation,
    val expectedCandidateStateVersion: Long,
    val expectedCandidateSha256: String,
    val expectedCandidateUpdatedAtMs: Long,
    val committedAtMs: Long,
) {
    init {
        require(candidateId.isSafeCuratorId())
        require(expectedCandidateStateVersion > 0L)
        require(expectedCandidateSha256.isCuratorSha256())
        require(expectedCandidateUpdatedAtMs >= 0L)
        require(committedAtMs >= expectedCandidateUpdatedAtMs)
        require(expectedCandidateStateVersion <= Long.MAX_VALUE - 2L)
    }

    override fun toString(): String =
        "CuratorRuntimeApplyRequest(operation=$expectedOperation, content=<redacted>, ids=<redacted>)"
}

/** Exact APPLIED head/plan fence used for an explicit rollback. */
data class CuratorRuntimeRollbackRequest(
    val candidateId: String,
    val expectedOperation: CuratorDeltaOperation,
    val expectedCandidateStateVersion: Long,
    val expectedCandidateSha256: String,
    val expectedApplyPlanId: String,
    val expectedApplyPlanSha256: String,
    val expectedCandidateUpdatedAtMs: Long,
    val committedAtMs: Long,
) {
    init {
        require(candidateId.isSafeCuratorId())
        require(expectedCandidateStateVersion > 0L)
        require(expectedCandidateStateVersion <= Long.MAX_VALUE - 2L)
        require(expectedCandidateSha256.isCuratorSha256())
        require(expectedApplyPlanId.isSafeCuratorPlanId())
        require(expectedApplyPlanSha256.isCuratorSha256())
        require(expectedCandidateUpdatedAtMs >= 0L)
        require(committedAtMs >= expectedCandidateUpdatedAtMs)
    }

    override fun toString(): String =
        "CuratorRuntimeRollbackRequest(operation=$expectedOperation, content=<redacted>, ids=<redacted>)"
}

enum class CuratorRuntimeConflict {
    ROLLOUT_DISABLED,
    CANDIDATE_MISSING,
    CANDIDATE_FENCE_CONFLICT,
    CANDIDATE_STATE_CONFLICT,
    CLOCK_CONFLICT,
    PLAN_INVALID,
    POLICY_HEAD_CONFLICT,
    POLICY_IDENTITY_CONFLICT,
    EVIDENCE_CONFLICT,
    LINEAGE_CONFLICT,
    REVISION_OVERFLOW,
}

sealed interface CuratorRuntimeMutationResult {
    val operation: CuratorDeltaOperation?

    data class Applied(
        override val operation: CuratorDeltaOperation,
        val candidateStateVersion: Long,
        val applyPlanId: String,
        val applyPlanSha256: String,
        val mutatedPolicyCount: Int,
    ) : CuratorRuntimeMutationResult {
        init {
            require(candidateStateVersion > 0L)
            require(applyPlanId.isSafeCuratorPlanId())
            require(applyPlanSha256.isCuratorSha256())
            require(mutatedPolicyCount in 1..MAX_CURATOR_RUNTIME_MUTATIONS)
        }
    }

    data class RolledBack(
        override val operation: CuratorDeltaOperation,
        val candidateStateVersion: Long,
        val applyPlanId: String,
        val applyPlanSha256: String,
        val mutatedPolicyCount: Int,
    ) : CuratorRuntimeMutationResult {
        init {
            require(candidateStateVersion > 0L)
            require(applyPlanId.isSafeCuratorPlanId())
            require(applyPlanSha256.isCuratorSha256())
            require(mutatedPolicyCount in 1..MAX_CURATOR_RUNTIME_MUTATIONS)
        }
    }

    data class Duplicate(
        override val operation: CuratorDeltaOperation,
        val terminalCandidateStateVersion: Long,
        val applyPlanId: String,
        val terminalState: CuratorRuntimeTerminalState,
    ) : CuratorRuntimeMutationResult {
        init {
            require(terminalCandidateStateVersion > 0L)
            require(applyPlanId.isSafeCuratorPlanId())
        }
    }

    data class Conflict(
        val reason: CuratorRuntimeConflict,
        override val operation: CuratorDeltaOperation? = null,
    ) : CuratorRuntimeMutationResult
}

enum class CuratorRuntimeTerminalState { APPLIED, ROLLED_BACK }

/** The only production boundary allowed to materialize a reviewed Curator v1 delta. */
interface CuratorApplyRuntimeStore {
    /** Plans and atomically applies the exact APPROVED candidate, or resumes its exact APPLYING plan. */
    suspend fun applyApproved(request: CuratorRuntimeApplyRequest): CuratorRuntimeMutationResult

    /** Atomically applies the persisted rollback plan; inserted Policies are archived, never deleted. */
    suspend fun rollbackApplied(request: CuratorRuntimeRollbackRequest): CuratorRuntimeMutationResult
}

/**
 * Stable application-facing coordinator. UI/review wiring may depend on this class without gaining
 * DAO access; all correctness and duplicate semantics remain inside [CuratorApplyRuntimeStore].
 */
class CuratorApplyRuntimeCoordinator(
    private val store: CuratorApplyRuntimeStore,
    private val positiveMutations: LearningPositiveMutationGate =
        DisabledLearningPositiveMutationGate,
) {
    suspend fun apply(
        request: CuratorRuntimeApplyRequest,
    ): CuratorRuntimeMutationResult {
        if (!positiveMutations.allows(request.expectedOperation)) {
            return CuratorRuntimeMutationResult.Conflict(
                CuratorRuntimeConflict.ROLLOUT_DISABLED,
                request.expectedOperation,
            )
        }
        return store.applyApproved(request)
    }

    suspend fun rollback(
        request: CuratorRuntimeRollbackRequest,
    ): CuratorRuntimeMutationResult = store.rollbackApplied(request)
}

const val MAX_CURATOR_RUNTIME_MUTATIONS: Int = MAX_CURATOR_SPLIT_OUTPUTS + MAX_CURATOR_SOURCES
