package me.rerere.rikkahub.learning.policy

enum class LearningPolicyStatus {
    CANDIDATE,
    SHADOW,
    PROBATION,
    ACTIVE,
    SUSPENDED,
    SUSPENDED_PENDING_REVIEW,
    STALE_SCHEMA,
    STALE_SOURCE,
    STALE_AUTHORITY,
    ARCHIVED,
}

enum class PolicyLifecycleReason {
    CREATED_FROM_VALIDATED_DRAFT,
    SHADOW_ELIGIBLE,
    USER_APPROVED_CONTEXTUAL_ADVICE,
    USER_SUSPENDED,
    USER_RESTORED_REVISION,
    SAFETY_REVIEW_REQUIRED,
    USER_ARCHIVED,
    RETENTION_EXPIRED,
    SOURCE_INVALIDATED,
    TOOL_SCHEMA_CHANGED,
    CAPABILITY_CHANGED,
    AUTHORITY_CHANGED,
}

data class PolicyLifecycleState(
    val status: LearningPolicyStatus,
    val revision: Long,
    val contentRevision: Long,
    val artifactHash: String,
    val reason: PolicyLifecycleReason,
    val staleReason: PolicyLifecycleReason? = null,
    val updatedAtMs: Long,
    val usageCount: Long = 0L,
    val lastUsedAtMs: Long? = null,
    val observedUtilityDelta: Double? = null,
    val utilityUncertainty: Double? = null,
) {
    init {
        require(revision > 0L)
        require(contentRevision > 0L)
        require(artifactHash.matches(Regex("[0-9a-f]{64}")))
        require(updatedAtMs >= 0L)
        require(usageCount >= 0L) { "Negative Policy usage" }
        require((usageCount == 0L) == (lastUsedAtMs == null)) {
            "Policy usage and last-used clock disagree"
        }
        require(lastUsedAtMs == null || lastUsedAtMs in 0L..updatedAtMs) {
            "Invalid Policy last-used clock"
        }
        require((observedUtilityDelta == null) == (utilityUncertainty == null)) {
            "Policy utility estimate and uncertainty must be recorded together"
        }
        observedUtilityDelta?.let { require(it.isFinite()) { "Invalid observed utility" } }
        utilityUncertainty?.let {
            require(it.isFinite() && it >= 0.0) { "Invalid utility uncertainty" }
        }
        require((status in REASON_REQUIRED_STATUSES) == (staleReason != null)) {
            "Policy lifecycle reason disagrees with its status"
        }
    }
}

enum class PolicyLifecycleFailure {
    REVISION_CONFLICT,
    CONTENT_REVISION_CONFLICT,
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
        expectedContentRevision: Long,
        expectedArtifactHash: String,
        target: LearningPolicyStatus,
        reason: PolicyLifecycleReason,
        frozenNowMs: Long,
    ): PolicyLifecycleResult {
        if (current.revision != expectedRevision) {
            return PolicyLifecycleResult.Rejected(PolicyLifecycleFailure.REVISION_CONFLICT)
        }
        if (current.contentRevision != expectedContentRevision) {
            return PolicyLifecycleResult.Rejected(PolicyLifecycleFailure.CONTENT_REVISION_CONFLICT)
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
        if (current.revision == Long.MAX_VALUE) {
            return PolicyLifecycleResult.Rejected(PolicyLifecycleFailure.REVISION_CONFLICT)
        }
        if (target !in ALLOWED.getValue(current.status) ||
            !isReasonAllowed(current.status, target, reason)
        ) {
            return PolicyLifecycleResult.Rejected(PolicyLifecycleFailure.INVALID_TRANSITION)
        }
        return PolicyLifecycleResult.Applied(
            current.copy(
                status = target,
                revision = Math.addExact(current.revision, 1L),
                reason = reason,
                staleReason = if (target in REASON_REQUIRED_STATUSES) reason else null,
                updatedAtMs = frozenNowMs,
            ),
        )
    }

    private val ALLOWED = mapOf(
        LearningPolicyStatus.CANDIDATE to setOf(
            LearningPolicyStatus.SHADOW,
            LearningPolicyStatus.STALE_SCHEMA,
            LearningPolicyStatus.STALE_SOURCE,
            LearningPolicyStatus.STALE_AUTHORITY,
            LearningPolicyStatus.ARCHIVED,
        ),
        LearningPolicyStatus.SHADOW to setOf(
            LearningPolicyStatus.PROBATION,
            LearningPolicyStatus.STALE_SCHEMA,
            LearningPolicyStatus.STALE_SOURCE,
            LearningPolicyStatus.STALE_AUTHORITY,
            LearningPolicyStatus.ARCHIVED,
        ),
        LearningPolicyStatus.PROBATION to setOf(
            LearningPolicyStatus.ACTIVE,
            LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
            LearningPolicyStatus.STALE_SCHEMA,
            LearningPolicyStatus.STALE_SOURCE,
            LearningPolicyStatus.STALE_AUTHORITY,
            LearningPolicyStatus.ARCHIVED,
        ),
        LearningPolicyStatus.ACTIVE to setOf(
            LearningPolicyStatus.SUSPENDED,
            LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
            LearningPolicyStatus.STALE_SCHEMA,
            LearningPolicyStatus.STALE_SOURCE,
            LearningPolicyStatus.STALE_AUTHORITY,
            LearningPolicyStatus.ARCHIVED,
        ),
        LearningPolicyStatus.SUSPENDED to setOf(
            LearningPolicyStatus.ACTIVE,
            LearningPolicyStatus.STALE_SCHEMA,
            LearningPolicyStatus.STALE_SOURCE,
            LearningPolicyStatus.STALE_AUTHORITY,
            LearningPolicyStatus.ARCHIVED,
        ),
        LearningPolicyStatus.SUSPENDED_PENDING_REVIEW to setOf(
            LearningPolicyStatus.STALE_SCHEMA,
            LearningPolicyStatus.STALE_SOURCE,
            LearningPolicyStatus.STALE_AUTHORITY,
            LearningPolicyStatus.ARCHIVED,
        ),
        LearningPolicyStatus.STALE_SCHEMA to setOf(LearningPolicyStatus.ARCHIVED),
        LearningPolicyStatus.STALE_SOURCE to setOf(LearningPolicyStatus.ARCHIVED),
        LearningPolicyStatus.STALE_AUTHORITY to setOf(LearningPolicyStatus.ARCHIVED),
        // Restoring an archived revision returns only to Shadow; it never restores authority.
        LearningPolicyStatus.ARCHIVED to setOf(LearningPolicyStatus.SHADOW),
    )

    private fun isReasonAllowed(
        current: LearningPolicyStatus,
        target: LearningPolicyStatus,
        reason: PolicyLifecycleReason,
    ): Boolean = when (target) {
        LearningPolicyStatus.CANDIDATE -> false
        LearningPolicyStatus.SHADOW -> when (current) {
            LearningPolicyStatus.CANDIDATE ->
                reason == PolicyLifecycleReason.SHADOW_ELIGIBLE
            LearningPolicyStatus.ARCHIVED ->
                reason == PolicyLifecycleReason.USER_RESTORED_REVISION
            else -> false
        }
        LearningPolicyStatus.PROBATION ->
            current == LearningPolicyStatus.SHADOW &&
                reason == PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE
        LearningPolicyStatus.ACTIVE ->
            current in setOf(LearningPolicyStatus.PROBATION, LearningPolicyStatus.SUSPENDED) &&
                reason == PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE
        LearningPolicyStatus.SUSPENDED ->
            current == LearningPolicyStatus.ACTIVE &&
                reason == PolicyLifecycleReason.USER_SUSPENDED
        LearningPolicyStatus.SUSPENDED_PENDING_REVIEW ->
            current in setOf(LearningPolicyStatus.PROBATION, LearningPolicyStatus.ACTIVE) &&
                reason == PolicyLifecycleReason.SAFETY_REVIEW_REQUIRED
        LearningPolicyStatus.STALE_SOURCE ->
            current in LIVE_POLICY_STATUSES &&
                reason == PolicyLifecycleReason.SOURCE_INVALIDATED
        LearningPolicyStatus.STALE_SCHEMA ->
            current in LIVE_POLICY_STATUSES &&
                reason in setOf(
                    PolicyLifecycleReason.TOOL_SCHEMA_CHANGED,
                    PolicyLifecycleReason.CAPABILITY_CHANGED,
                )
        LearningPolicyStatus.STALE_AUTHORITY ->
            current in LIVE_POLICY_STATUSES &&
                reason == PolicyLifecycleReason.AUTHORITY_CHANGED
        LearningPolicyStatus.ARCHIVED ->
            reason == PolicyLifecycleReason.USER_ARCHIVED ||
                (
                    reason == PolicyLifecycleReason.RETENTION_EXPIRED && current in setOf(
                        LearningPolicyStatus.CANDIDATE,
                        LearningPolicyStatus.SHADOW,
                    )
                    )
    }
}

private val LIVE_POLICY_STATUSES = setOf(
    LearningPolicyStatus.CANDIDATE,
    LearningPolicyStatus.SHADOW,
    LearningPolicyStatus.PROBATION,
    LearningPolicyStatus.ACTIVE,
    LearningPolicyStatus.SUSPENDED,
    LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
)

private val REASON_REQUIRED_STATUSES = setOf(
    LearningPolicyStatus.SUSPENDED,
    LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
    LearningPolicyStatus.STALE_SCHEMA,
    LearningPolicyStatus.STALE_SOURCE,
    LearningPolicyStatus.STALE_AUTHORITY,
)
