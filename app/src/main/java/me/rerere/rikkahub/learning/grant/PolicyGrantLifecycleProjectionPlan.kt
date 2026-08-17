package me.rerere.rikkahub.learning.grant

import me.rerere.rikkahub.learning.policy.LearningPolicyStatus

/**
 * Pure next-step table for the replayable AppDatabase-to-LearningDatabase grant saga.
 * A persisted PROBATION head is the intentional crash boundary between review admission and
 * activation; replay therefore resumes with [ACTIVATE] instead of attempting admission again.
 */
internal enum class PolicyGrantLifecycleProjectionStep {
    ADMIT_PROBATION,
    ACTIVATE,
    SUSPEND,
    STALE_AUTHORITY,
    ALREADY_SATISFIED,
    BLOCKED,
}

internal fun nextPolicyGrantLifecycleProjectionStep(
    authorityState: PolicyGrantAuthorityState,
    policyStatus: LearningPolicyStatus?,
): PolicyGrantLifecycleProjectionStep = when (authorityState) {
    PolicyGrantAuthorityState.GRANTED -> when (policyStatus) {
        LearningPolicyStatus.SHADOW -> PolicyGrantLifecycleProjectionStep.ADMIT_PROBATION
        LearningPolicyStatus.PROBATION -> PolicyGrantLifecycleProjectionStep.ACTIVATE
        LearningPolicyStatus.SUSPENDED -> PolicyGrantLifecycleProjectionStep.ACTIVATE
        LearningPolicyStatus.ACTIVE -> PolicyGrantLifecycleProjectionStep.ALREADY_SATISFIED
        else -> PolicyGrantLifecycleProjectionStep.BLOCKED
    }

    // A grant belongs to one consuming Assistant. Revoking A must not mutate the shared technical
    // Policy state used by an independently granted B. Runtime eligibility always joins the exact
    // per-consumer GRANTED head; with no grants the ACTIVE row is inert but remains reviewable.
    PolicyGrantAuthorityState.REVOKED -> if (policyStatus == null) {
        PolicyGrantLifecycleProjectionStep.BLOCKED
    } else {
        PolicyGrantLifecycleProjectionStep.ALREADY_SATISFIED
    }
}
