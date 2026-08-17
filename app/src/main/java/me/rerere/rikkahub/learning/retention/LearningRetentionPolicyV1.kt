@file:Suppress("unused")

package me.rerere.rikkahub.learning.retention

import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.model.LearningRetentionPreferencesV1
import me.rerere.rikkahub.learning.model.LearningRetentionPresetV1

enum class LearningRetentionArtifactKind {
    EPISODE,
    TRACE_FEATURE,
    EPISODE_LESSON,
    REWARD_WINDOW,
    POLICY_CANDIDATE,
    POLICY_REVISION,
    SOURCE_TOMBSTONE,
    OUTBOUND_RECEIPT,
    WORKFLOW_CANDIDATE,
    WORKFLOW_REVISION,
    INBOX_EVENT,
    DONE_JOB,
    POLICY_EXPOSURE,
    REWARD_SIGNAL,
}

enum class LearningRetentionReason {
    WITHIN_TTL,
    OPEN_EPISODE,
    PENDING_JOB,
    REFERENCED_BY_VALID_LESSON,
    REFERENCED_BY_POLICY,
    USER_REVIEW_RECORD,
    LIFECYCLE_PROTECTED,
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

/** One frozen, fully resolved retention plan shared by decisions and Room maintenance. */
data class LearningRetentionPlanV1(
    val frozenNowMs: Long,
    val openEpisodeMaxAgeMs: Long,
    val traceTtlMs: Long,
    val episodeTtlMs: Long,
    val rewardTtlMs: Long,
    val lessonTtlMs: Long,
    val candidateTtlMs: Long,
    val revisionTtlMs: Long,
    val sourceTombstoneAuditFloorMs: Long,
    val outboundReceiptTtlMs: Long,
    val workflowCandidateTtlMs: Long,
    val workflowRevisionTtlMs: Long,
    val inboxTtlMs: Long,
    val doneJobTtlMs: Long,
    val policyExposureTtlMs: Long,
) {
    init {
        require(frozenNowMs >= 0L)
        listOf(
            openEpisodeMaxAgeMs,
            traceTtlMs,
            episodeTtlMs,
            rewardTtlMs,
            lessonTtlMs,
            candidateTtlMs,
            revisionTtlMs,
            sourceTombstoneAuditFloorMs,
            outboundReceiptTtlMs,
            workflowCandidateTtlMs,
            workflowRevisionTtlMs,
            inboxTtlMs,
            doneJobTtlMs,
            policyExposureTtlMs,
        ).forEach { require(it > 0L) }
    }

    val nowMs: Long get() = frozenNowMs
    val openEpisodeCutoffMs: Long get() = cutoff(openEpisodeMaxAgeMs)
    val traceCutoffMs: Long get() = cutoff(traceTtlMs)
    val episodeCutoffMs: Long get() = cutoff(episodeTtlMs)
    val rewardCutoffMs: Long get() = cutoff(rewardTtlMs)
    val lessonCutoffMs: Long get() = cutoff(lessonTtlMs)
    val candidateCutoffMs: Long get() = cutoff(candidateTtlMs)
    val revisionCutoffMs: Long get() = cutoff(revisionTtlMs)
    val sourceTombstoneCutoffMs: Long get() = cutoff(sourceTombstoneAuditFloorMs)
    val outboundReceiptCutoffMs: Long get() = cutoff(outboundReceiptTtlMs)
    val workflowCandidateCutoffMs: Long get() = cutoff(workflowCandidateTtlMs)
    val workflowRevisionCutoffMs: Long get() = cutoff(workflowRevisionTtlMs)
    val inboxCutoffMs: Long get() = cutoff(inboxTtlMs)
    val doneJobCutoffMs: Long get() = cutoff(doneJobTtlMs)
    val policyExposureCutoffMs: Long get() = cutoff(policyExposureTtlMs)

    /** Compatibility alias for the original default-only storage contract. */
    val episodeAndRewardCutoffMs: Long get() = episodeCutoffMs
    val dormantPolicyCutoffMs: Long get() = candidateCutoffMs
    val invalidSourceCutoffMs: Long get() = sourceTombstoneCutoffMs

    fun ttlFor(kind: LearningRetentionArtifactKind): Long = when (kind) {
        LearningRetentionArtifactKind.EPISODE -> episodeTtlMs
        LearningRetentionArtifactKind.TRACE_FEATURE -> traceTtlMs
        LearningRetentionArtifactKind.EPISODE_LESSON -> lessonTtlMs
        LearningRetentionArtifactKind.REWARD_WINDOW -> rewardTtlMs
        LearningRetentionArtifactKind.POLICY_CANDIDATE -> candidateTtlMs
        LearningRetentionArtifactKind.POLICY_REVISION -> revisionTtlMs
        LearningRetentionArtifactKind.SOURCE_TOMBSTONE -> sourceTombstoneAuditFloorMs
        LearningRetentionArtifactKind.OUTBOUND_RECEIPT -> outboundReceiptTtlMs
        LearningRetentionArtifactKind.WORKFLOW_CANDIDATE -> workflowCandidateTtlMs
        LearningRetentionArtifactKind.WORKFLOW_REVISION -> workflowRevisionTtlMs
        LearningRetentionArtifactKind.INBOX_EVENT -> inboxTtlMs
        LearningRetentionArtifactKind.DONE_JOB -> doneJobTtlMs
        LearningRetentionArtifactKind.POLICY_EXPOSURE -> policyExposureTtlMs
        LearningRetentionArtifactKind.REWARD_SIGNAL -> rewardTtlMs
    }

    private fun cutoff(ttlMs: Long): Long =
        if (frozenNowMs < ttlMs) 0L else frozenNowMs - ttlMs
}

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
    const val WORKFLOW_CANDIDATE_TTL_MS = 180L * DAY_MS
    const val WORKFLOW_REVISION_TTL_MS = 180L * DAY_MS
    const val INBOX_TTL_MS = 90L * DAY_MS
    const val DONE_JOB_TTL_MS = OUTBOUND_RECEIPT_TTL_MS
    const val POLICY_EXPOSURE_TTL_MS = 180L * DAY_MS

    const val MINIMAL_TRACE_TTL_MS = 7L * DAY_MS
    const val EXTENDED_TRACE_TTL_MS = 90L * DAY_MS
    const val MINIMAL_REWARD_TTL_MS = 30L * DAY_MS
    const val EXTENDED_REWARD_TTL_MS = 180L * DAY_MS

    fun freezePlan(
        frozenNowMs: Long,
        preferences: LearningRetentionPreferencesV1 = LearningRetentionPreferencesV1(),
    ): LearningRetentionPlanV1 {
        require(frozenNowMs >= 0L)
        val safe = preferences.failClosed()
        val traceTtlMs = when (safe.tracePreset) {
            LearningRetentionPresetV1.MINIMAL -> MINIMAL_TRACE_TTL_MS
            LearningRetentionPresetV1.STANDARD -> TRACE_TTL_MS
            LearningRetentionPresetV1.EXTENDED -> EXTENDED_TRACE_TTL_MS
        }
        val rewardTtlMs = when (safe.rewardPreset) {
            LearningRetentionPresetV1.MINIMAL -> MINIMAL_REWARD_TTL_MS
            LearningRetentionPresetV1.STANDARD -> REWARD_TTL_MS
            LearningRetentionPresetV1.EXTENDED -> EXTENDED_REWARD_TTL_MS
        }
        return LearningRetentionPlanV1(
            frozenNowMs = frozenNowMs,
            openEpisodeMaxAgeMs = OPEN_EPISODE_MAX_AGE_MS,
            traceTtlMs = traceTtlMs,
            episodeTtlMs = EPISODE_TTL_MS,
            rewardTtlMs = rewardTtlMs,
            lessonTtlMs = LESSON_TTL_MS,
            candidateTtlMs = CANDIDATE_TTL_MS,
            revisionTtlMs = REVISION_TTL_MS,
            sourceTombstoneAuditFloorMs = SOURCE_TOMBSTONE_AUDIT_FLOOR_MS,
            outboundReceiptTtlMs = OUTBOUND_RECEIPT_TTL_MS,
            workflowCandidateTtlMs = WORKFLOW_CANDIDATE_TTL_MS,
            workflowRevisionTtlMs = WORKFLOW_REVISION_TTL_MS,
            inboxTtlMs = INBOX_TTL_MS,
            doneJobTtlMs = DONE_JOB_TTL_MS,
            policyExposureTtlMs = POLICY_EXPOSURE_TTL_MS,
        )
    }

    fun decide(
        subject: LearningRetentionSubject,
        frozenNowMs: Long,
        privacyEraseRequested: Boolean = false,
        preferences: LearningRetentionPreferencesV1 = LearningRetentionPreferencesV1(),
    ): LearningRetentionDecision = decide(
        subject = subject,
        plan = freezePlan(frozenNowMs, preferences),
        privacyEraseRequested = privacyEraseRequested,
    )

    fun decide(
        subject: LearningRetentionSubject,
        plan: LearningRetentionPlanV1,
        privacyEraseRequested: Boolean = false,
    ): LearningRetentionDecision {
        if (privacyEraseRequested) {
            val retainAuditTombstone = subject.kind == LearningRetentionArtifactKind.SOURCE_TOMBSTONE &&
                age(subject, plan.frozenNowMs) < plan.sourceTombstoneAuditFloorMs
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
        if (
            subject.kind == LearningRetentionArtifactKind.POLICY_CANDIDATE &&
            subject.policyStatus != null &&
            subject.policyStatus !in setOf(
                LearningPolicyStatus.CANDIDATE,
                LearningPolicyStatus.SHADOW,
            )
        ) {
            return LearningRetentionDecision(
                true,
                LearningRetentionReason.LIFECYCLE_PROTECTED,
                false,
            )
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
            subject.open && age(subject, plan.frozenNowMs) <= plan.openEpisodeMaxAgeMs
        ) {
            return LearningRetentionDecision(true, LearningRetentionReason.OPEN_EPISODE, false)
        }
        val ttl = plan.ttlFor(subject.kind)
        return if (age(subject, plan.frozenNowMs) <= ttl) {
            LearningRetentionDecision(true, LearningRetentionReason.WITHIN_TTL, false)
        } else {
            LearningRetentionDecision(true, LearningRetentionReason.EXPIRED, true)
                .copy(retain = false)
        }
    }

    private fun age(subject: LearningRetentionSubject, frozenNowMs: Long): Long =
        (frozenNowMs - subject.updatedAtMs).coerceAtLeast(0L)
}
