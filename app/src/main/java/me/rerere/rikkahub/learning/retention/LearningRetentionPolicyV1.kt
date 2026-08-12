@file:Suppress("unused")

package me.rerere.rikkahub.learning.retention

import me.rerere.rikkahub.learning.policy.LearningPolicyStatus

enum class LearningRetentionArtifactKind {
    EPISODE,
    TRACE_FEATURE,
    EPISODE_LESSON,
    REWARD_WINDOW,
    POLICY_CANDIDATE,
    POLICY_REVISION,
    SOURCE_TOMBSTONE,
    OUTBOUND_RECEIPT,
}

enum class LearningRetentionReason {
    WITHIN_TTL,
    OPEN_EPISODE,
    PENDING_JOB,
    REFERENCED_BY_VALID_LESSON,
    REFERENCED_BY_POLICY,
    USER_REVIEW_RECORD,
    SOURCE_AUDIT_FLOOR,
    EXPIRED,
    PRIVACY_ERASE,
}

data class LearningRetentionSubject(
    val kind: LearningRetentionArtifactKind,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val open: Boolean = false,
    val pendingJob: Boolean = false,
    val referencedByValidLesson: Boolean = false,
    val referencedByPolicy: Boolean = false,
    val userReviewRecord: Boolean = false,
    val policyStatus: LearningPolicyStatus? = null,
) {
    init {
        require(createdAtMs >= 0L && updatedAtMs >= createdAtMs)
    }
}

data class LearningRetentionDecision(
    val retain: Boolean,
    val reason: LearningRetentionReason,
    /** Summaries/FTS must be removed immediately even when a tombstone itself is retained. */
    val eraseDerivedTextAndIndex: Boolean,
)

/** All P1 TTLs live here; DAOs receive cutoffs and never embed duration constants. */
/** Pure artifact-level projection of the canonical storage retention policy. */
object LearningRetentionDecisionPolicyV1 {
    const val VERSION = "learning-retention-v1"
    private const val DAY_MS = 24L * 60L * 60L * 1_000L
    const val TRACE_TTL_MS = 30L * DAY_MS
    const val EPISODE_TTL_MS = 90L * DAY_MS
    const val REWARD_TTL_MS = 90L * DAY_MS
    const val OPEN_EPISODE_MAX_AGE_MS = 7L * DAY_MS
    const val LESSON_TTL_MS = 180L * DAY_MS
    const val CANDIDATE_TTL_MS = 180L * DAY_MS
    const val REVISION_TTL_MS = 180L * DAY_MS
    const val SOURCE_TOMBSTONE_AUDIT_FLOOR_MS = 180L * DAY_MS
    const val OUTBOUND_RECEIPT_TTL_MS = 90L * DAY_MS

    fun decide(
        subject: LearningRetentionSubject,
        frozenNowMs: Long,
        privacyEraseRequested: Boolean = false,
    ): LearningRetentionDecision {
        require(frozenNowMs >= 0L)
        if (privacyEraseRequested) {
            val retainAuditTombstone = subject.kind == LearningRetentionArtifactKind.SOURCE_TOMBSTONE &&
                age(subject, frozenNowMs) < SOURCE_TOMBSTONE_AUDIT_FLOOR_MS
            return LearningRetentionDecision(
                retain = retainAuditTombstone,
                reason = if (retainAuditTombstone) {
                    LearningRetentionReason.SOURCE_AUDIT_FLOOR
                } else {
                    LearningRetentionReason.PRIVACY_ERASE
                },
                eraseDerivedTextAndIndex = true,
            )
        }
        if (subject.pendingJob) {
            return LearningRetentionDecision(true, LearningRetentionReason.PENDING_JOB, false)
        }
        if (subject.userReviewRecord) {
            return LearningRetentionDecision(true, LearningRetentionReason.USER_REVIEW_RECORD, false)
        }
        if (subject.referencedByPolicy) {
            return LearningRetentionDecision(true, LearningRetentionReason.REFERENCED_BY_POLICY, false)
        }
        if (subject.referencedByValidLesson) {
            return LearningRetentionDecision(
                true,
                LearningRetentionReason.REFERENCED_BY_VALID_LESSON,
                false,
            )
        }
        if (
            subject.kind == LearningRetentionArtifactKind.EPISODE &&
            subject.open && age(subject, frozenNowMs) <= OPEN_EPISODE_MAX_AGE_MS
        ) {
            return LearningRetentionDecision(true, LearningRetentionReason.OPEN_EPISODE, false)
        }
        val ttl = when (subject.kind) {
            LearningRetentionArtifactKind.EPISODE -> EPISODE_TTL_MS
            LearningRetentionArtifactKind.TRACE_FEATURE -> TRACE_TTL_MS
            LearningRetentionArtifactKind.EPISODE_LESSON -> LESSON_TTL_MS
            LearningRetentionArtifactKind.REWARD_WINDOW -> REWARD_TTL_MS
            LearningRetentionArtifactKind.POLICY_CANDIDATE -> CANDIDATE_TTL_MS
            LearningRetentionArtifactKind.POLICY_REVISION -> REVISION_TTL_MS
            LearningRetentionArtifactKind.SOURCE_TOMBSTONE -> SOURCE_TOMBSTONE_AUDIT_FLOOR_MS
            LearningRetentionArtifactKind.OUTBOUND_RECEIPT -> OUTBOUND_RECEIPT_TTL_MS
        }
        return if (age(subject, frozenNowMs) <= ttl) {
            LearningRetentionDecision(true, LearningRetentionReason.WITHIN_TTL, false)
        } else {
            LearningRetentionDecision(true, LearningRetentionReason.EXPIRED, true)
                .copy(retain = false)
        }
    }

    private fun age(subject: LearningRetentionSubject, frozenNowMs: Long): Long =
        (frozenNowMs - subject.updatedAtMs).coerceAtLeast(0L)
}
