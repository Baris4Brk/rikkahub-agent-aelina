package me.rerere.rikkahub.learning.temporal

private val DATASET_IDENTITY = Regex("[0-9a-f]{64}")

/** P3 is conditional. This evidence contract cannot enable a runtime feature by itself. */
data class TemporalOperationalDemandEvidence(
    val evaluationDatasetIdentity: String,
    val highFrequencyUnsolvedCaseCount: Int,
    val authorityAndLifecycleDefined: Boolean,
    val measuredBenefitMilli: Int,
    val extractionCostMilli: Int,
    val wrongStructureRiskMilli: Int,
    val containsPersonalFactUseCases: Boolean,
) {
    init {
        require(evaluationDatasetIdentity.matches(DATASET_IDENTITY))
        require(highFrequencyUnsolvedCaseCount in 0..10_000)
        require(measuredBenefitMilli in 0..1_000)
        require(extractionCostMilli in 0..1_000)
        require(wrongStructureRiskMilli in 0..1_000)
    }
}

enum class TemporalOperationalDemandCancellationReason {
    NO_HIGH_FREQUENCY_UNSOLVED_CASE_SET,
    AUTHORITY_OR_LIFECYCLE_UNDEFINED,
    BENEFIT_DOES_NOT_EXCEED_COST_AND_RISK,
    PERSONAL_FACT_DOMAIN_FORBIDDEN,
}

sealed interface TemporalOperationalDemandDecision {
    /** Only a design-review input; it does not toggle temporalOperational. */
    data class EligibleForStoreDesign(
        val evaluationDatasetIdentity: String,
    ) : TemporalOperationalDemandDecision

    data class Cancelled(
        val reasons: Set<TemporalOperationalDemandCancellationReason>,
    ) : TemporalOperationalDemandDecision
}

object TemporalOperationalDemandGate {
    fun evaluate(evidence: TemporalOperationalDemandEvidence): TemporalOperationalDemandDecision {
        val reasons = buildSet {
            if (evidence.highFrequencyUnsolvedCaseCount == 0) {
                add(
                    TemporalOperationalDemandCancellationReason
                        .NO_HIGH_FREQUENCY_UNSOLVED_CASE_SET,
                )
            }
            if (!evidence.authorityAndLifecycleDefined) {
                add(
                    TemporalOperationalDemandCancellationReason
                        .AUTHORITY_OR_LIFECYCLE_UNDEFINED,
                )
            }
            if (
                evidence.measuredBenefitMilli <=
                evidence.extractionCostMilli + evidence.wrongStructureRiskMilli
            ) {
                add(
                    TemporalOperationalDemandCancellationReason
                        .BENEFIT_DOES_NOT_EXCEED_COST_AND_RISK,
                )
            }
            if (evidence.containsPersonalFactUseCases) {
                add(TemporalOperationalDemandCancellationReason.PERSONAL_FACT_DOMAIN_FORBIDDEN)
            }
        }
        return if (reasons.isEmpty()) {
            TemporalOperationalDemandDecision.EligibleForStoreDesign(
                evidence.evaluationDatasetIdentity,
            )
        } else {
            TemporalOperationalDemandDecision.Cancelled(reasons)
        }
    }
}

/** Frozen 2026-08-13 evaluation: no qualifying unsolved non-personal corpus was demonstrated. */
val CURRENT_TEMPORAL_OPERATIONAL_DEMAND_DECISION: TemporalOperationalDemandDecision =
    TemporalOperationalDemandGate.evaluate(
        TemporalOperationalDemandEvidence(
            evaluationDatasetIdentity =
                "22b6fd9725803087842aadc5d9df8c7179889a5494220d1dfde4074b98c432f7",
            highFrequencyUnsolvedCaseCount = 0,
            authorityAndLifecycleDefined = false,
            measuredBenefitMilli = 0,
            extractionCostMilli = 1_000,
            wrongStructureRiskMilli = 1_000,
            containsPersonalFactUseCases = false,
        ),
    )
