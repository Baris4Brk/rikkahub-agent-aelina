package me.rerere.rikkahub.learning.policy

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.learning.grant.policyGrantId
import me.rerere.rikkahub.learning.model.LearningScope
import kotlin.uuid.Uuid

enum class PolicyMutationActor {
    DISTILLER,
    /** Frozen Stage-D admission gate; it has no review or provider authority. */
    SHADOW_GATE,
    CURATOR_REVIEW,
    USER,
    GRANT_BINDER,
    SAFETY_GOVERNOR,
    AUTHORITY_RECONCILER,
    SOURCE_INVALIDATOR,
    RETENTION,
}

/**
 * Content-free receipt produced only after the AppDatabase authority owner has revalidated a
 * current GRANTED head. This object is deliberately not an authority source: the Learning DB
 * mutation verifies the exact tuple, while the caller owns the cross-database ordering/saga.
 */
data class PolicyGrantBindingProof(
    val grantId: String,
    val sourceStreamId: String,
    val scope: LearningScope,
    val consumingAssistantId: Uuid,
    val policyId: String,
    val contentRevision: Long,
    val artifactSha256: String,
    val grantStateVersion: Long,
) {
    init {
        require(scope is LearningScope.Assistant || scope is LearningScope.AuthoritySubject) {
            "Policy grant proof requires an exact non-global scope"
        }
        if (scope is LearningScope.Assistant) require(scope.assistantId == consumingAssistantId)
        require(contentRevision > 0L) { "Invalid granted Policy content revision" }
        require(artifactSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Invalid granted Policy artifact"
        }
        require(grantStateVersion > 0L) { "Invalid Policy grant state version" }
        require(grantId == policyGrantId(sourceStreamId, scope, consumingAssistantId, policyId)) {
            "Policy grant proof identity mismatch"
        }
    }

    internal fun matches(fence: PolicyMutationFence): Boolean =
        scope == fence.scope &&
            policyId == fence.policyId &&
            contentRevision == fence.expectedContentRevision &&
            artifactSha256 == fence.expectedArtifactHash

    override fun toString(): String =
        "PolicyGrantBindingProof(scope=${scope.kind}, version=$grantStateVersion, ids=<redacted>)"
}

data class PolicyMutationFence(
    val policyId: String,
    val scope: LearningScope,
    val expectedRevision: Long,
    val expectedContentRevision: Long,
    val expectedArtifactHash: String,
) {
    init {
        require(policyId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")))
        require(expectedRevision > 0L)
        require(expectedContentRevision > 0L)
        require(expectedArtifactHash.matches(Regex("[0-9a-f]{64}")))
    }

    override fun toString(): String =
        "PolicyMutationFence(scope=${scope.kind}, revision=$expectedRevision, " +
            "contentRevision=$expectedContentRevision, ids=<redacted>)"
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
        /** Required for review admission and activation; it contains no Policy or grant body. */
        val grantBindingProof: PolicyGrantBindingProof? = null,
        /**
         * Content-free evidence embedded into the same append-only revision as an automatic
         * downgrade. Authority evidence is also recorded through its dedicated durable ledger.
         */
        val lifecycleEvidence: PolicyLifecycleEvidenceRecord? = null,
    ) : PolicyMutationRequest {
        init {
            require(frozenNowMs >= 0L)
            require(lifecycleEvidence == null || lifecycleEvidence.exactlyAuthorizes(this)) {
                "Policy lifecycle evidence does not authorize this exact transition"
            }
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
    CONTENT_REVISION_CONFLICT,
    ARTIFACT_CONFLICT,
    GRANT_BINDING_CONFLICT,
    SOURCE_STALE,
    SCHEMA_STALE,
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
        if (!request.isAllowedPolicyMutation()) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.INVALID_TRANSITION)
        }
        if (request is PolicyMutationRequest.Transition &&
            !request.hasValidGrantBinding()
        ) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.GRANT_BINDING_CONFLICT)
        }
        if (request is PolicyMutationRequest.Transition &&
            !request.hasValidLifecycleEvidence()
        ) {
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

private fun PolicyMutationRequest.isAllowedPolicyMutation(): Boolean = when (this) {
    is PolicyMutationRequest.CreateCandidate ->
        actor == PolicyMutationActor.DISTILLER

    is PolicyMutationRequest.Transition -> when (target) {
        LearningPolicyStatus.CANDIDATE -> false
        LearningPolicyStatus.SHADOW -> when (actor) {
            PolicyMutationActor.SHADOW_GATE,
            PolicyMutationActor.CURATOR_REVIEW ->
                reason == PolicyLifecycleReason.SHADOW_ELIGIBLE
            PolicyMutationActor.USER ->
                reason == PolicyLifecycleReason.USER_RESTORED_REVISION
            else -> false
        }
        LearningPolicyStatus.PROBATION ->
            actor == PolicyMutationActor.USER &&
                reason == PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE
        LearningPolicyStatus.ACTIVE ->
            actor == PolicyMutationActor.GRANT_BINDER &&
                reason == PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE
        LearningPolicyStatus.SUSPENDED ->
            actor == PolicyMutationActor.USER && reason == PolicyLifecycleReason.USER_SUSPENDED
        LearningPolicyStatus.SUSPENDED_PENDING_REVIEW ->
            actor == PolicyMutationActor.SAFETY_GOVERNOR &&
                reason == PolicyLifecycleReason.SAFETY_REVIEW_REQUIRED
        LearningPolicyStatus.ARCHIVED ->
            (
                actor == PolicyMutationActor.USER &&
                    reason == PolicyLifecycleReason.USER_ARCHIVED
                ) || (
                actor == PolicyMutationActor.RETENTION &&
                    reason == PolicyLifecycleReason.RETENTION_EXPIRED
                )
        LearningPolicyStatus.STALE_SOURCE ->
            actor == PolicyMutationActor.SOURCE_INVALIDATOR &&
                reason == PolicyLifecycleReason.SOURCE_INVALIDATED
        LearningPolicyStatus.STALE_SCHEMA ->
            actor == PolicyMutationActor.SOURCE_INVALIDATOR &&
                reason in setOf(
                    PolicyLifecycleReason.TOOL_SCHEMA_CHANGED,
                    PolicyLifecycleReason.CAPABILITY_CHANGED,
                )
        LearningPolicyStatus.STALE_AUTHORITY ->
            actor == PolicyMutationActor.AUTHORITY_RECONCILER &&
                reason == PolicyLifecycleReason.AUTHORITY_CHANGED
    }
}

private fun PolicyMutationRequest.Transition.hasValidGrantBinding(): Boolean {
    val proof = grantBindingProof
    return if (target in setOf(LearningPolicyStatus.PROBATION, LearningPolicyStatus.ACTIVE)) {
        proof != null && proof.matches(fence)
    } else {
        proof == null || proof.matches(fence)
    }
}

private fun PolicyMutationRequest.Transition.hasValidLifecycleEvidence(): Boolean {
    val evidenceRequired = target == LearningPolicyStatus.SUSPENDED_PENDING_REVIEW ||
        reason == PolicyLifecycleReason.CAPABILITY_CHANGED
    val evidence = lifecycleEvidence ?: return !evidenceRequired
    return evidence.exactlyAuthorizes(this) && actor in setOf(
        PolicyMutationActor.SAFETY_GOVERNOR,
        PolicyMutationActor.SOURCE_INVALIDATOR,
        PolicyMutationActor.AUTHORITY_RECONCILER,
    )
}
