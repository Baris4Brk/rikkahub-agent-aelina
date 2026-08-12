package me.rerere.rikkahub.learning.retention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningRetentionPolicyV1Test {
    @Test
    fun exactTtlBoundaryRetainsThenExpires() {
        val subject = LearningRetentionSubject(
            kind = LearningRetentionArtifactKind.TRACE_FEATURE,
            createdAtMs = 0,
            updatedAtMs = 0,
        )
        assertTrue(
            LearningRetentionDecisionPolicyV1.decide(
                subject,
                LearningRetentionDecisionPolicyV1.TRACE_TTL_MS,
            ).retain,
        )
        assertFalse(
            LearningRetentionDecisionPolicyV1.decide(
                subject,
                LearningRetentionDecisionPolicyV1.TRACE_TTL_MS + 1,
            ).retain,
        )
    }

    @Test
    fun pendingAndReferencedArtifactsArePinned() {
        val old = LearningRetentionDecisionPolicyV1.LESSON_TTL_MS * 2
        assertEquals(
            LearningRetentionReason.PENDING_JOB,
            LearningRetentionDecisionPolicyV1.decide(
                LearningRetentionSubject(
                    LearningRetentionArtifactKind.EPISODE_LESSON,
                    0,
                    0,
                    pendingJob = true,
                ),
                old,
            ).reason,
        )
        assertEquals(
            LearningRetentionReason.REFERENCED_BY_POLICY,
            LearningRetentionDecisionPolicyV1.decide(
                LearningRetentionSubject(
                    LearningRetentionArtifactKind.EPISODE_LESSON,
                    0,
                    0,
                    referencedByPolicy = true,
                ),
                old,
            ).reason,
        )
    }

    @Test
    fun privacyErasePurgesDerivedTextButKeepsYoungAuditTombstone() {
        val decision = LearningRetentionDecisionPolicyV1.decide(
            LearningRetentionSubject(
                LearningRetentionArtifactKind.SOURCE_TOMBSTONE,
                0,
                0,
            ),
            1,
            privacyEraseRequested = true,
        )
        assertTrue(decision.retain)
        assertTrue(decision.eraseDerivedTextAndIndex)
        assertEquals(LearningRetentionReason.SOURCE_AUDIT_FLOOR, decision.reason)
    }
}
