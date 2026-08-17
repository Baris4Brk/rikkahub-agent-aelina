package me.rerere.rikkahub.learning.temporal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalOperationalDemandGateTest {
    @Test
    fun `current P3 demand gate is cancelled rather than implemented speculatively`() {
        val decision = CURRENT_TEMPORAL_OPERATIONAL_DEMAND_DECISION
            as TemporalOperationalDemandDecision.Cancelled
        assertTrue(
            TemporalOperationalDemandCancellationReason.NO_HIGH_FREQUENCY_UNSOLVED_CASE_SET in
                decision.reasons,
        )
        assertTrue(
            TemporalOperationalDemandCancellationReason.AUTHORITY_OR_LIFECYCLE_UNDEFINED in
                decision.reasons,
        )
    }

    @Test
    fun `personal facts always cancel even when numeric threshold looks favorable`() {
        val result = TemporalOperationalDemandGate.evaluate(
            TemporalOperationalDemandEvidence(
                evaluationDatasetIdentity = "a".repeat(64),
                highFrequencyUnsolvedCaseCount = 100,
                authorityAndLifecycleDefined = true,
                measuredBenefitMilli = 900,
                extractionCostMilli = 10,
                wrongStructureRiskMilli = 10,
                containsPersonalFactUseCases = true,
            ),
        ) as TemporalOperationalDemandDecision.Cancelled
        assertEquals(
            setOf(TemporalOperationalDemandCancellationReason.PERSONAL_FACT_DOMAIN_FORBIDDEN),
            result.reasons,
        )
    }
}
