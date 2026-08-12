package me.rerere.rikkahub.learning.policy

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.model.LearningScope

enum class PolicyMutationActor {
    DISTILLER,
    CURATOR_REVIEW,
    USER,
    SOURCE_INVALIDATOR,
    RETENTION,
}

data class PolicyMutationFence(
    val policyId: String,
    val scope: LearningScope,
    val expectedRevision: Long,
    val expectedArtifactHash: String,
) {
    init {
        require(policyId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")))
        require(expectedRevision > 0L)
        require(expectedArtifactHash.matches(Regex("[0-9a-f]{64}")))
    }

    override fun toString(): String =
        "PolicyMutationFence(scope=${scope.kind}, revision=$expectedRevision, ids=<redacted>)"
}

sealed interface PolicyMutationRequest {
    val actor: PolicyMutationActor

    data class CreateCandidate(
        val draft: PolicyCandidateDraft,
        override val actor: PolicyMutationActor = PolicyMutationActor.DISTILLER,
    ) : PolicyMutationRequest

    data class Transition(
        val fence: PolicyMutationFence,
        val target: LearningPolicyStatus,
        val reason: PolicyLifecycleReason,
        /** Caller-frozen wall clock; retries of the same command must reuse this value. */
        val frozenNowMs: Long,
        override val actor: PolicyMutationActor,
    ) : PolicyMutationRequest {
        init {
            require(frozenNowMs >= 0L)
        }
    }
}

sealed interface PolicyMutationResult {
    data class Applied(
        val policyId: String,
        val revision: Long,
        val status: LearningPolicyStatus,
    ) : PolicyMutationResult

    data class Duplicate(
        val policyId: String,
        val revision: Long,
    ) : PolicyMutationResult

    data class Conflict(val reason: PolicyMutationConflict) : PolicyMutationResult
}

enum class PolicyMutationConflict {
    IDENTITY_CONFLICT,
    REVISION_CONFLICT,
    ARTIFACT_CONFLICT,
    SOURCE_STALE,
    INVALID_TRANSITION,
}

/**
 * Canonical Policy write boundary. Reflection and Curator never receive a lower-level Policy DAO;
 * their only durable path is a validated request through this store.
 */
fun interface PolicyMutationStore {
    suspend fun mutate(request: PolicyMutationRequest): PolicyMutationResult
}

class ValidatingPolicyMutationStore(
    private val transaction: PolicyMutationTransaction,
) : PolicyMutationStore {
    override suspend fun mutate(request: PolicyMutationRequest): PolicyMutationResult {
        if (!request.isAllowedP1Mutation()) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.INVALID_TRANSITION)
        }
        return try {
            transaction.apply(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }
}

/** Implemented by the LearningDatabase storage owner as one fenced Room transaction. */
fun interface PolicyMutationTransaction {
    suspend fun apply(request: PolicyMutationRequest): PolicyMutationResult
}

private fun PolicyMutationRequest.isAllowedP1Mutation(): Boolean = when (this) {
    is PolicyMutationRequest.CreateCandidate ->
        actor == PolicyMutationActor.DISTILLER

    is PolicyMutationRequest.Transition -> when (target) {
        LearningPolicyStatus.CANDIDATE -> false
        LearningPolicyStatus.SHADOW ->
            actor == PolicyMutationActor.CURATOR_REVIEW &&
                reason == PolicyLifecycleReason.SHADOW_ELIGIBLE
        LearningPolicyStatus.ARCHIVED ->
            actor in setOf(PolicyMutationActor.USER, PolicyMutationActor.RETENTION)
        LearningPolicyStatus.STALE ->
            actor == PolicyMutationActor.SOURCE_INVALIDATOR &&
                reason in setOf(
                    PolicyLifecycleReason.SOURCE_INVALIDATED,
                    PolicyLifecycleReason.TOOL_SCHEMA_CHANGED,
                )
    }
}
