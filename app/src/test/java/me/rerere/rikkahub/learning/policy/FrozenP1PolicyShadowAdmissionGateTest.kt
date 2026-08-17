package me.rerere.rikkahub.learning.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrozenP1PolicyShadowAdmissionGateTest {
    @Test
    fun `exact durable evidence admits without a magic score threshold`() {
        assertEquals(
            PolicyShadowAdmissionDecision.Eligible,
            FrozenP1PolicyShadowAdmissionGate.evaluate(facts()),
        )
        // Confidence is intentionally absent from PolicyShadowAdmissionFacts. Promotion cannot be
        // changed by a hidden 0.65-style runtime number.
        assertTrue(
            PolicyShadowAdmissionFacts::class.java.declaredFields.none {
                it.name == "confidence"
            },
        )
    }

    @Test
    fun `duplicate episode retry and cached support disagreement fail closed`() {
        assertEquals(
            PolicyShadowAdmissionDecision.Rejected(
                PolicyShadowAdmissionFailure.EVIDENCE_INVALID,
            ),
            FrozenP1PolicyShadowAdmissionGate.evaluate(
                facts(evidenceEpisodeIds = listOf("episode-1", "episode-1")),
            ),
        )
        assertEquals(
            PolicyShadowAdmissionDecision.Rejected(
                PolicyShadowAdmissionFailure.SUPPORT_MISMATCH,
            ),
            FrozenP1PolicyShadowAdmissionGate.evaluate(facts(distinctEpisodeSupport = 3L)),
        )
    }

    @Test
    fun `stale evidence and any P1 effect state fail closed`() {
        assertEquals(
            PolicyShadowAdmissionDecision.Rejected(
                PolicyShadowAdmissionFailure.EVIDENCE_INVALID,
            ),
            FrozenP1PolicyShadowAdmissionGate.evaluate(
                facts(validEvidenceEpisodeIds = setOf("episode-1")),
            ),
        )
        assertEquals(
            PolicyShadowAdmissionDecision.Rejected(
                PolicyShadowAdmissionFailure.P1_EFFECT_STATE_PRESENT,
            ),
            FrozenP1PolicyShadowAdmissionGate.evaluate(facts(usageCount = 1L)),
        )
    }

    @Test
    fun `positive and avoid candidates require their authoritative polarity`() {
        assertEquals(
            PolicyShadowAdmissionDecision.Rejected(
                PolicyShadowAdmissionFailure.AUTHORITY_POLARITY_MISSING,
            ),
            FrozenP1PolicyShadowAdmissionGate.evaluate(
                facts(
                    positiveEpisodeCount = 0,
                    negativeEpisodeCount = 2,
                    positiveEvidenceEpisodeIds = emptySet(),
                    negativeEvidenceEpisodeIds = setOf("episode-1", "episode-2"),
                ),
            ),
        )
        assertEquals(
            PolicyShadowAdmissionDecision.Rejected(
                PolicyShadowAdmissionFailure.AUTHORITY_POLARITY_MISSING,
            ),
            FrozenP1PolicyShadowAdmissionGate.evaluate(
                facts(
                    policyType = PolicyCandidateType.AVOID,
                    positiveEpisodeCount = 2,
                    negativeEpisodeCount = 0,
                    positiveEvidenceEpisodeIds = setOf("episode-1", "episode-2"),
                    negativeEvidenceEpisodeIds = emptySet(),
                ),
            ),
        )
    }

    @Test
    fun `unknown or neutral evidence cannot satisfy authoritative support`() {
        assertEquals(
            PolicyShadowAdmissionDecision.Rejected(
                PolicyShadowAdmissionFailure.POLARITY_MISMATCH,
            ),
            FrozenP1PolicyShadowAdmissionGate.evaluate(
                facts(
                    positiveEpisodeCount = 1L,
                    positiveEvidenceEpisodeIds = setOf("episode-1"),
                ),
            ),
        )
    }

    private fun facts(
        policyType: PolicyCandidateType = PolicyCandidateType.PROCEDURE,
        distinctEpisodeSupport: Long = 2L,
        positiveEpisodeCount: Long = if (policyType == PolicyCandidateType.AVOID) 1L else 2L,
        negativeEpisodeCount: Long = if (policyType == PolicyCandidateType.AVOID) 1L else 0L,
        evidenceEpisodeIds: List<String> = listOf("episode-1", "episode-2"),
        validEvidenceEpisodeIds: Set<String> = evidenceEpisodeIds.toSet(),
        positiveEvidenceEpisodeIds: Set<String> = if (policyType == PolicyCandidateType.AVOID) {
            setOf("episode-2")
        } else {
            evidenceEpisodeIds.toSet()
        },
        negativeEvidenceEpisodeIds: Set<String> = if (policyType == PolicyCandidateType.AVOID) {
            setOf("episode-1")
        } else {
            emptySet()
        },
        usageCount: Long = 0L,
    ) = PolicyShadowAdmissionFacts(
        gateIdentity = P1_SHADOW_ADMISSION_GATE_ID,
        status = LearningPolicyStatus.CANDIDATE,
        policyType = policyType,
        sourceValid = true,
        schemaValid = true,
        distinctEpisodeSupport = distinctEpisodeSupport,
        positiveEpisodeCount = positiveEpisodeCount,
        negativeEpisodeCount = negativeEpisodeCount,
        evidenceEpisodeIds = evidenceEpisodeIds,
        validEvidenceEpisodeIds = validEvidenceEpisodeIds,
        positiveEvidenceEpisodeIds = positiveEvidenceEpisodeIds,
        negativeEvidenceEpisodeIds = negativeEvidenceEpisodeIds,
        usageCount = usageCount,
        observedUtilityDelta = null,
        utilityUncertainty = null,
    )
}
