package me.rerere.rikkahub.learning.retention

import me.rerere.rikkahub.learning.model.LearningRetentionPreferencesV1
import me.rerere.rikkahub.learning.model.LearningRetentionPresetV1
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
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

    @Test
    fun userPresetsResolveOnceIntoFrozenTraceAndRewardCutoffs() {
        val day = 24L * 60L * 60L * 1_000L
        val now = 365L * day
        val plan = LearningRetentionDecisionPolicyV1.freezePlan(
            frozenNowMs = now,
            preferences = LearningRetentionPreferencesV1(
                tracePreset = LearningRetentionPresetV1.MINIMAL,
                rewardPreset = LearningRetentionPresetV1.EXTENDED,
            ),
        )

        assertEquals(7L * day, plan.traceTtlMs)
        assertEquals(180L * day, plan.rewardTtlMs)
        assertEquals(now - 7L * day, plan.traceCutoffMs)
        assertEquals(now - 180L * day, plan.rewardCutoffMs)
        assertEquals(now, plan.frozenNowMs)
    }

    @Test
    fun invalidImportedRetentionPreferencesFailClosedToStandardDefaults() {
        val plan = LearningRetentionDecisionPolicyV1.freezePlan(
            frozenNowMs = LearningRetentionDecisionPolicyV1.EXTENDED_REWARD_TTL_MS,
            preferences = LearningRetentionPreferencesV1(
                schemaVersion = Int.MAX_VALUE,
                tracePreset = LearningRetentionPresetV1.EXTENDED,
                rewardPreset = LearningRetentionPresetV1.MINIMAL,
            ),
        )

        assertEquals(LearningRetentionDecisionPolicyV1.TRACE_TTL_MS, plan.traceTtlMs)
        assertEquals(LearningRetentionDecisionPolicyV1.REWARD_TTL_MS, plan.rewardTtlMs)
    }

    @Test
    fun planCoversMaintenanceArtifactsAndKeepsReceiptBeforeDoneJobAligned() {
        val plan = LearningRetentionDecisionPolicyV1.freezePlan(1_000_000_000_000L)

        LearningRetentionArtifactKind.entries.forEach { kind ->
            assertTrue(plan.ttlFor(kind) > 0L)
        }
        assertEquals(plan.outboundReceiptTtlMs, plan.doneJobTtlMs)
        assertEquals(plan.outboundReceiptCutoffMs, plan.doneJobCutoffMs)
    }

    @Test
    fun reviewedAndNonCandidatePolicyLifecyclesNeverExpireAsCache() {
        val old = LearningRetentionDecisionPolicyV1.CANDIDATE_TTL_MS * 2
        listOf(
            LearningPolicyStatus.PROBATION,
            LearningPolicyStatus.ACTIVE,
            LearningPolicyStatus.SUSPENDED,
            LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
            LearningPolicyStatus.STALE_SCHEMA,
            LearningPolicyStatus.STALE_SOURCE,
            LearningPolicyStatus.STALE_AUTHORITY,
            LearningPolicyStatus.ARCHIVED,
        ).forEach { status ->
            val decision = LearningRetentionDecisionPolicyV1.decide(
                LearningRetentionSubject(
                    kind = LearningRetentionArtifactKind.POLICY_CANDIDATE,
                    createdAtMs = 0,
                    updatedAtMs = 0,
                    policyStatus = status,
                ),
                old,
            )
            assertTrue(decision.retain)
            assertEquals(LearningRetentionReason.LIFECYCLE_PROTECTED, decision.reason)
        }
    }
}
