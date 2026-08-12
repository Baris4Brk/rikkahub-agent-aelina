package me.rerere.rikkahub.learning.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P1PolicyEvidenceRecalculationTest {
    @Test
    fun noSurvivingEvidenceProducesZeroStatistics() {
        assertEquals(
            P1PolicyEvidenceStatistics(0, 0, 0, 0.0),
            P1PolicyEvidenceRecalculator.calculate(emptyList()),
        )
    }

    @Test
    fun supportPolarityAndQualityAreRecomputedFromDistinctSurvivors() {
        val statistics = P1PolicyEvidenceRecalculator.calculate(
            listOf(
                P1PolicyEvidenceSignal("episode-a", PolicyEvidencePolarity.POSITIVE, 1.0),
                P1PolicyEvidenceSignal("episode-a", PolicyEvidencePolarity.NEGATIVE, 0.0),
                P1PolicyEvidenceSignal("episode-b", PolicyEvidencePolarity.NEGATIVE, 0.5),
            ),
        )

        assertEquals(2, statistics.distinctEpisodeSupport)
        assertEquals(1, statistics.positiveEpisodeCount)
        assertEquals(1, statistics.negativeEpisodeCount)
        assertTrue(statistics.confidence in 0.0..1.0)
    }

    @Test
    fun moreConsistentEvidenceRaisesConfidence() {
        val mixed = P1PolicyEvidenceRecalculator.calculate(
            listOf(
                P1PolicyEvidenceSignal("a", PolicyEvidencePolarity.POSITIVE, null),
                P1PolicyEvidenceSignal("b", PolicyEvidencePolarity.NEGATIVE, null),
            ),
        )
        val consistent = P1PolicyEvidenceRecalculator.calculate(
            listOf(
                P1PolicyEvidenceSignal("a", PolicyEvidencePolarity.POSITIVE, null),
                P1PolicyEvidenceSignal("b", PolicyEvidencePolarity.POSITIVE, null),
            ),
        )

        assertTrue(consistent.confidence > mixed.confidence)
    }
}
