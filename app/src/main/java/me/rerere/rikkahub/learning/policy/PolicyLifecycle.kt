package me.rerere.rikkahub.learning.policy

enum class LearningPolicyStatus {
    CANDIDATE,
    SHADOW,
    ARCHIVED,
    STALE,
}

enum class PolicyLifecycleReason {
    CREATED_FROM_VALIDATED_DRAFT,
    SHADOW_ELIGIBLE,
    USER_ARCHIVED,
    SOURCE_INVALIDATED,
    TOOL_SCHEMA_CHANGED,
}

data class PolicyLifecycleState(
    val status: LearningPolicyStatus,
    val revision: Long,
    val artifactHash: String,
    val reason: PolicyLifecycleReason,
    val staleReason: PolicyLifecycleReason? = null,
    val updatedAtMs: Long,
    val lastUsedAtMs: Long? = null,
    val observedUtilityDelta: Double? = null,
    val utilityUncertainty: Double? = null,
) {
    init {
        require(revision > 0L)
        require(artifactHash.matches(Regex("[0-9a-f]{64}")))
        require(updatedAtMs >= 0L)
        // P1 has no actual exposure and therefore no usage/utility authority.
        require(lastUsedAtMs == null) { "P1 cannot record policy use" }
        require(observedUtilityDelta == null && utilityUncertainty == null) {
            "P1 shadow data cannot claim observed utility"
        }
        require((status == LearningPolicyStatus.STALE) == (staleReason != null)) {
            "A stale policy requires an explicit source/schema reason"
        }
    }
}

enum class PolicyLifecycleFailure {
    REVISION_CONFLICT,
    ARTIFACT_CONFLICT,
    INVALID_TRANSITION,
    CLOCK_REGRESSION,
}

sealed interface PolicyLifecycleResult {
    data class Applied(val state: PolicyLifecycleState) : PolicyLifecycleResult
    data class Duplicate(val state: PolicyLifecycleState) : PolicyLifecycleResult
    data class Rejected(val failure: PolicyLifecycleFailure) : PolicyLifecycleResult
}

object PolicyLifecycle {
    fun transition(
        current: PolicyLifecycleState,
        expectedRevision: Long,
        expectedArtifactHash: String,
        target: LearningPolicyStatus,
        reason: PolicyLifecycleReason,
        frozenNowMs: Long,
    ): PolicyLifecycleResult {
        if (current.revision != expectedRevision) {
            return PolicyLifecycleResult.Rejected(PolicyLifecycleFailure.REVISION_CONFLICT)
        }
        if (current.artifactHash != expectedArtifactHash) {
            return PolicyLifecycleResult.Rejected(PolicyLifecycleFailure.ARTIFACT_CONFLICT)
        }
        if (frozenNowMs < current.updatedAtMs) {
            return PolicyLifecycleResult.Rejected(PolicyLifecycleFailure.CLOCK_REGRESSION)
        }
        if (target == current.status && reason == current.reason) {
            return PolicyLifecycleResult.Duplicate(current)
        }
        if (target !in ALLOWED.getValue(current.status)) {
            return PolicyLifecycleResult.Rejected(PolicyLifecycleFailure.INVALID_TRANSITION)
        }
        return PolicyLifecycleResult.Applied(
            current.copy(
                status = target,
                revision = Math.addExact(current.revision, 1L),
                reason = reason,
                staleReason = if (target == LearningPolicyStatus.STALE) reason else null,
                updatedAtMs = frozenNowMs,
            ),
        )
    }

    private val ALLOWED = mapOf(
        LearningPolicyStatus.CANDIDATE to setOf(
            LearningPolicyStatus.SHADOW,
            LearningPolicyStatus.ARCHIVED,
            LearningPolicyStatus.STALE,
        ),
        LearningPolicyStatus.SHADOW to setOf(
            LearningPolicyStatus.ARCHIVED,
            LearningPolicyStatus.STALE,
        ),
        LearningPolicyStatus.ARCHIVED to emptySet(),
        LearningPolicyStatus.STALE to setOf(LearningPolicyStatus.ARCHIVED),
    )
}
