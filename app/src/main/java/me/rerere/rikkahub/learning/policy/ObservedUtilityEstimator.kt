package me.rerere.rikkahub.learning.policy

import kotlin.math.sqrt
import me.rerere.rikkahub.learning.exposure.PolicyExposureReceipt
import me.rerere.rikkahub.learning.exposure.PolicyExposureState

const val OBSERVED_UTILITY_METRIC_NAME: String = "observedUtilityDelta"
const val OBSERVED_UTILITY_INTERPRETATION_NAME: String = "observed association"

private const val MAX_OBSERVED_UTILITY_OBSERVATIONS = 10_000
private const val OBSERVED_UTILITY_CONFIDENCE_LEVEL = 0.95
private const val OBSERVED_UTILITY_Z_95 = 1.959963984540054
private const val MIN_OBSERVED_PROPENSITY = 0.01
private const val MAX_OBSERVED_PROPENSITY = 0.99
private val OBSERVED_UTILITY_SHA256 = Regex("[0-9a-f]{64}")
private val OBSERVED_UTILITY_CODE = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")

enum class ObservedUtilityArm {
    EXPOSED,
    NON_EXPOSURE,
}

enum class ObservedUtilityOutcome {
    SUCCESS,
    FAILURE,
    UNKNOWN,
    CENSORED,
}

enum class ObservedUtilityAssignmentMethod {
    MATCHED_NON_EXPOSURE,
    EXPLICIT_HOLDOUT,
    PROPENSITY_WEIGHTED,
}

enum class ObservedUtilitySelectionMethod {
    EXACT_MATCHED_COHORT,
    PRE_REGISTERED_HOLDOUT,
    PRE_REGISTERED_PROPENSITY,
}

enum class ObservedUtilityAttributionUnit {
    BUNDLE,
    INDIVIDUAL_POLICY,
}

/** Every field is part of the cohort boundary; producer changes never rewrite an old estimate. */
data class ObservedUtilityCohortIdentity(
    val taskSignature: String,
    val taskSignatureVersion: Int,
    val modelIdentity: String,
    val modelVersion: String,
    val providerIdentity: String,
    val providerVersion: String,
    val toolsetFingerprint: String,
    val toolSchemaVersion: String,
    val producerModelIdentity: String,
    val producerProviderIdentity: String,
    val producerConfigurationIdentity: String,
    val producerConfigurationGeneration: Long,
    val outcomeDefinitionVersion: String,
    val outcomeWindowIdentity: String,
    /** Exact final provider configuration generation, distinct from Policy producer generation. */
    val providerConfigurationGeneration: Long = 0L,
) {
    init {
        listOf(
            taskSignature,
            modelIdentity,
            modelVersion,
            providerIdentity,
            providerVersion,
            toolSchemaVersion,
            producerModelIdentity,
            producerProviderIdentity,
            producerConfigurationIdentity,
            outcomeDefinitionVersion,
            outcomeWindowIdentity,
        ).forEach { require(it.matches(OBSERVED_UTILITY_CODE)) }
        require(taskSignatureVersion > 0)
        require(toolsetFingerprint.matches(OBSERVED_UTILITY_SHA256))
        require(producerConfigurationGeneration >= 0L)
        require(providerConfigurationGeneration >= 0L)
    }

    override fun toString(): String =
        "ObservedUtilityCohortIdentity(taskVersion=$taskSignatureVersion, " +
            "providerVersion=$providerVersion, toolVersion=$toolSchemaVersion, ids=<redacted>)"
}

data class ObservedUtilityDesign(
    val targetPolicySetDigest: String,
    val assignmentMethod: ObservedUtilityAssignmentMethod,
    val selectionMethod: ObservedUtilitySelectionMethod,
    /** Required for holdout/propensity and for any causal interpretation. */
    val preRegisteredDesignDigest: String?,
    val exposureRecordingReliable: Boolean,
    val exposureContractVersion: Int,
    val eligibilityDeterminedBeforeTreatment: Boolean,
    val assignmentBeforeCompileOrInjection: Boolean,
    val fixedOutcomeWindow: Boolean,
    val randomizedAssignment: Boolean,
    val factorialIsolation: Boolean = false,
    val attributionUnit: ObservedUtilityAttributionUnit =
        ObservedUtilityAttributionUnit.BUNDLE,
    val targetPolicyId: String? = null,
) {
    init {
        require(targetPolicySetDigest.matches(OBSERVED_UTILITY_SHA256))
        require(preRegisteredDesignDigest == null ||
            preRegisteredDesignDigest.matches(OBSERVED_UTILITY_SHA256))
        require(exposureContractVersion >= 0)
        require(targetPolicyId == null || targetPolicyId.matches(OBSERVED_UTILITY_CODE))
        require(
            attributionUnit != ObservedUtilityAttributionUnit.INDIVIDUAL_POLICY ||
                targetPolicyId != null,
        ) { "Individual utility requires an exact Policy identity" }
    }
}

/**
 * One content-free outcome row. EXPOSED rows carry the durable receipt. NON_EXPOSURE rows carry
 * the equivalent provider/outcome authority facts because no Policy exposure row exists for them.
 */
data class ObservedUtilityObservation(
    val observationIdDigest: String,
    val arm: ObservedUtilityArm,
    val outcome: ObservedUtilityOutcome,
    val cohort: ObservedUtilityCohortIdentity,
    val policySetDigest: String,
    val matchKeyDigest: String? = null,
    val propensity: Double? = null,
    val exposureReceipt: PolicyExposureReceipt? = null,
    val baselineHostDispatched: Boolean = false,
    val baselineProgressOrResponse: Boolean = false,
    val authoritativeOutcomeCommitted: Boolean = false,
) {
    init {
        require(observationIdDigest.matches(OBSERVED_UTILITY_SHA256))
        require(policySetDigest.matches(OBSERVED_UTILITY_SHA256))
        require(matchKeyDigest == null || matchKeyDigest.matches(OBSERVED_UTILITY_SHA256))
        require(propensity == null || propensity.isFinite())
    }

    override fun toString(): String =
        "ObservedUtilityObservation(arm=$arm, outcome=$outcome, ids=<redacted>)"
}

data class ObservedUtilityConfidenceInterval(
    val level: Double,
    val lower: Double,
    val upper: Double,
) {
    init {
        require(level in 0.0..1.0)
        require(lower.isFinite() && upper.isFinite() && lower <= upper)
        require(lower >= -1.0 && upper <= 1.0)
    }
}

enum class ObservedUtilityCausalInterpretation {
    NOT_CLAIMED,
    PREREGISTERED_RANDOMIZED_DESIGN_ELIGIBLE,
}

data class ObservedUtilityEstimate(
    val metricName: String = OBSERVED_UTILITY_METRIC_NAME,
    val interpretationName: String = OBSERVED_UTILITY_INTERPRETATION_NAME,
    val observedUtilityDelta: Double,
    val confidenceInterval: ObservedUtilityConfidenceInterval,
    val sampleSize: Int,
    val exposedSampleSize: Int,
    val nonExposureSampleSize: Int,
    val unknownCount: Int,
    val censoredCount: Int,
    val assignmentMethod: ObservedUtilityAssignmentMethod,
    val selectionMethod: ObservedUtilitySelectionMethod,
    val cohort: ObservedUtilityCohortIdentity,
    val attributionUnit: ObservedUtilityAttributionUnit,
    val causalInterpretation: ObservedUtilityCausalInterpretation,
) {
    init {
        require(metricName == OBSERVED_UTILITY_METRIC_NAME)
        require(interpretationName == OBSERVED_UTILITY_INTERPRETATION_NAME)
        require(observedUtilityDelta.isFinite() && observedUtilityDelta in -1.0..1.0)
        require(sampleSize > 0 && exposedSampleSize > 0 && nonExposureSampleSize > 0)
        require(sampleSize == exposedSampleSize + nonExposureSampleSize)
        require(unknownCount >= 0 && censoredCount >= 0)
    }
}

enum class ObservedUtilityAbstainReason {
    EXPOSURE_RECORDING_UNRELIABLE,
    NO_OBSERVATIONS,
    OBSERVATION_BOUND_EXCEEDED,
    DUPLICATE_OBSERVATION,
    POLICY_SET_MISMATCH,
    COHORT_MISMATCH,
    EXPOSURE_NOT_ELIGIBLE,
    NON_EXPOSURE_OUTCOME_NOT_AUTHORITATIVE,
    ASSIGNMENT_SELECTION_MISMATCH,
    MATCHED_COHORT_MISSING,
    HOLDOUT_NOT_EXPLICIT,
    PROPENSITY_MISSING,
    PROPENSITY_OUT_OF_RANGE,
    KNOWN_OUTCOME_MISSING,
    CO_EXPOSURE_NOT_IDENTIFIABLE,
}

sealed interface ObservedUtilityEstimationResult {
    data class Estimated(
        val estimate: ObservedUtilityEstimate,
    ) : ObservedUtilityEstimationResult

    data class Abstained(
        val reason: ObservedUtilityAbstainReason,
        val observationCount: Int,
        val knownSampleSize: Int,
        val exposedKnownSampleSize: Int,
        val nonExposureKnownSampleSize: Int,
        val unknownCount: Int,
        val censoredCount: Int,
        val assignmentMethod: ObservedUtilityAssignmentMethod,
        val selectionMethod: ObservedUtilitySelectionMethod,
        /** Null when no single cohort can be established (empty or cross-cohort input). */
        val cohort: ObservedUtilityCohortIdentity?,
    ) : ObservedUtilityEstimationResult {
        init {
            require(observationCount >= 0 && knownSampleSize >= 0)
            require(exposedKnownSampleSize >= 0 && nonExposureKnownSampleSize >= 0)
            require(exposedKnownSampleSize + nonExposureKnownSampleSize == knownSampleSize)
            require(unknownCount >= 0 && censoredCount >= 0)
            require(knownSampleSize + unknownCount + censoredCount == observationCount)
        }

        val metricName: String
            get() = OBSERVED_UTILITY_METRIC_NAME

        val interpretationName: String
            get() = OBSERVED_UTILITY_INTERPRETATION_NAME

        /** ABSTAIN has no numerical interval; null is explicit rather than a fabricated zero. */
        val confidenceInterval: ObservedUtilityConfidenceInterval?
            get() = null
    }
}

/**
 * P2 estimator for content-free binary outcomes. It never falls back to a with/without mean when
 * the declared matched cohort, holdout, or propensity evidence is absent.
 */
object ObservedUtilityEstimator {
    fun estimate(
        design: ObservedUtilityDesign,
        observations: List<ObservedUtilityObservation>,
    ): ObservedUtilityEstimationResult {
        fun abstain(reason: ObservedUtilityAbstainReason): ObservedUtilityEstimationResult.Abstained {
            val known = observations.filter { it.outcome.isKnown() }
            return ObservedUtilityEstimationResult.Abstained(
                reason = reason,
                observationCount = observations.size,
                knownSampleSize = known.size,
                exposedKnownSampleSize = known.count { it.arm == ObservedUtilityArm.EXPOSED },
                nonExposureKnownSampleSize = known.count {
                    it.arm == ObservedUtilityArm.NON_EXPOSURE
                },
                unknownCount = observations.count { it.outcome == ObservedUtilityOutcome.UNKNOWN },
                censoredCount = observations.count { it.outcome == ObservedUtilityOutcome.CENSORED },
                assignmentMethod = design.assignmentMethod,
                selectionMethod = design.selectionMethod,
                cohort = observations.map(ObservedUtilityObservation::cohort).distinct()
                    .singleOrNull(),
            )
        }

        if (!design.exposureRecordingReliable || design.exposureContractVersion <= 0) {
            return abstain(ObservedUtilityAbstainReason.EXPOSURE_RECORDING_UNRELIABLE)
        }
        if (observations.isEmpty()) return abstain(ObservedUtilityAbstainReason.NO_OBSERVATIONS)
        if (observations.size > MAX_OBSERVED_UTILITY_OBSERVATIONS) {
            return abstain(ObservedUtilityAbstainReason.OBSERVATION_BOUND_EXCEEDED)
        }
        if (observations.map { it.observationIdDigest }.distinct().size != observations.size) {
            return abstain(ObservedUtilityAbstainReason.DUPLICATE_OBSERVATION)
        }
        if (observations.any { it.policySetDigest != design.targetPolicySetDigest }) {
            return abstain(ObservedUtilityAbstainReason.POLICY_SET_MISMATCH)
        }
        val cohorts = observations.map { it.cohort }.distinct()
        if (cohorts.size != 1) return abstain(ObservedUtilityAbstainReason.COHORT_MISMATCH)

        observations.forEach { observation ->
            when (observation.arm) {
                ObservedUtilityArm.EXPOSED -> {
                    val receipt = observation.exposureReceipt
                        ?: return abstain(ObservedUtilityAbstainReason.EXPOSURE_NOT_ELIGIBLE)
                    if (receipt.reservation.bundle.policySetDigest != design.targetPolicySetDigest ||
                        !receipt.hasObserved(PolicyExposureState.INJECTED) ||
                        !receipt.hasObserved(PolicyExposureState.HOST_DISPATCHED)
                    ) {
                        return abstain(ObservedUtilityAbstainReason.EXPOSURE_NOT_ELIGIBLE)
                    }
                    if (observation.outcome.isKnown() && !receipt.canAttributeObservedUtility) {
                        return abstain(ObservedUtilityAbstainReason.EXPOSURE_NOT_ELIGIBLE)
                    }
                }

                ObservedUtilityArm.NON_EXPOSURE -> {
                    if (observation.exposureReceipt != null) {
                        return abstain(ObservedUtilityAbstainReason.POLICY_SET_MISMATCH)
                    }
                    if (observation.outcome.isKnown() &&
                        (!observation.baselineHostDispatched ||
                            !observation.baselineProgressOrResponse ||
                            !observation.authoritativeOutcomeCommitted)
                    ) {
                        return abstain(
                            ObservedUtilityAbstainReason.NON_EXPOSURE_OUTCOME_NOT_AUTHORITATIVE,
                        )
                    }
                }
            }
        }

        if (design.attributionUnit == ObservedUtilityAttributionUnit.INDIVIDUAL_POLICY) {
            val isolated = observations.asSequence()
                .filter { it.arm == ObservedUtilityArm.EXPOSED }
                .mapNotNull { it.exposureReceipt }
                .all { receipt ->
                    val policies = receipt.reservation.bundle.policies
                    policies.size == 1 && policies.single().policyId == design.targetPolicyId
                }
            val factorial = design.factorialIsolation && causalDesignPrerequisitesHold(design)
            if (!isolated && !factorial) {
                return abstain(ObservedUtilityAbstainReason.CO_EXPOSURE_NOT_IDENTIFIABLE)
            }
        }

        val known = observations.filter { it.outcome.isKnown() }
        if (known.isEmpty()) return abstain(ObservedUtilityAbstainReason.KNOWN_OUTCOME_MISSING)
        val selected = when (design.assignmentMethod) {
            ObservedUtilityAssignmentMethod.MATCHED_NON_EXPOSURE -> {
                if (design.selectionMethod !=
                    ObservedUtilitySelectionMethod.EXACT_MATCHED_COHORT
                ) {
                    return abstain(
                        ObservedUtilityAbstainReason.ASSIGNMENT_SELECTION_MISMATCH,
                    )
                }
                val matched = selectExactMatchedPairs(known)
                if (matched.first.isEmpty() || matched.second.isEmpty()) {
                    return abstain(ObservedUtilityAbstainReason.MATCHED_COHORT_MISSING)
                }
                SelectedUtilityRows(matched.first, matched.second, weighted = false)
            }

            ObservedUtilityAssignmentMethod.EXPLICIT_HOLDOUT -> {
                if (design.selectionMethod !=
                    ObservedUtilitySelectionMethod.PRE_REGISTERED_HOLDOUT
                ) {
                    return abstain(
                        ObservedUtilityAbstainReason.ASSIGNMENT_SELECTION_MISMATCH,
                    )
                }
                if (design.preRegisteredDesignDigest == null) {
                    return abstain(ObservedUtilityAbstainReason.HOLDOUT_NOT_EXPLICIT)
                }
                SelectedUtilityRows(
                    exposed = known.filter { it.arm == ObservedUtilityArm.EXPOSED },
                    nonExposure = known.filter { it.arm == ObservedUtilityArm.NON_EXPOSURE },
                    weighted = false,
                )
            }

            ObservedUtilityAssignmentMethod.PROPENSITY_WEIGHTED -> {
                if (design.selectionMethod !=
                    ObservedUtilitySelectionMethod.PRE_REGISTERED_PROPENSITY
                ) {
                    return abstain(
                        ObservedUtilityAbstainReason.ASSIGNMENT_SELECTION_MISMATCH,
                    )
                }
                if (design.preRegisteredDesignDigest == null || known.any { it.propensity == null }) {
                    return abstain(ObservedUtilityAbstainReason.PROPENSITY_MISSING)
                }
                if (known.any {
                        val propensity = requireNotNull(it.propensity)
                        propensity !in MIN_OBSERVED_PROPENSITY..MAX_OBSERVED_PROPENSITY
                    }
                ) {
                    return abstain(ObservedUtilityAbstainReason.PROPENSITY_OUT_OF_RANGE)
                }
                SelectedUtilityRows(
                    exposed = known.filter { it.arm == ObservedUtilityArm.EXPOSED },
                    nonExposure = known.filter { it.arm == ObservedUtilityArm.NON_EXPOSURE },
                    weighted = true,
                )
            }
        }
        if (selected.exposed.isEmpty() || selected.nonExposure.isEmpty()) {
            return abstain(ObservedUtilityAbstainReason.KNOWN_OUTCOME_MISSING)
        }

        val exposedEstimate = estimateArm(selected.exposed, selected.weighted)
        val controlEstimate = estimateArm(selected.nonExposure, selected.weighted)
        val delta = (exposedEstimate.mean - controlEstimate.mean).coerceIn(-1.0, 1.0)
        val interval = ObservedUtilityConfidenceInterval(
            level = OBSERVED_UTILITY_CONFIDENCE_LEVEL,
            lower = (exposedEstimate.lower - controlEstimate.upper).coerceIn(-1.0, 1.0),
            upper = (exposedEstimate.upper - controlEstimate.lower).coerceIn(-1.0, 1.0),
        )
        val causal = if (causalDesignPrerequisitesHold(design)) {
            ObservedUtilityCausalInterpretation.PREREGISTERED_RANDOMIZED_DESIGN_ELIGIBLE
        } else {
            ObservedUtilityCausalInterpretation.NOT_CLAIMED
        }
        return ObservedUtilityEstimationResult.Estimated(
            ObservedUtilityEstimate(
                observedUtilityDelta = delta,
                confidenceInterval = interval,
                sampleSize = selected.exposed.size + selected.nonExposure.size,
                exposedSampleSize = selected.exposed.size,
                nonExposureSampleSize = selected.nonExposure.size,
                unknownCount = observations.count {
                    it.outcome == ObservedUtilityOutcome.UNKNOWN
                },
                censoredCount = observations.count {
                    it.outcome == ObservedUtilityOutcome.CENSORED
                },
                assignmentMethod = design.assignmentMethod,
                selectionMethod = design.selectionMethod,
                cohort = cohorts.single(),
                attributionUnit = design.attributionUnit,
                causalInterpretation = causal,
            ),
        )
    }
}

private data class SelectedUtilityRows(
    val exposed: List<ObservedUtilityObservation>,
    val nonExposure: List<ObservedUtilityObservation>,
    val weighted: Boolean,
)

private data class ArmEstimate(
    val mean: Double,
    val lower: Double,
    val upper: Double,
)

private fun selectExactMatchedPairs(
    rows: List<ObservedUtilityObservation>,
): Pair<List<ObservedUtilityObservation>, List<ObservedUtilityObservation>> {
    if (rows.any { it.matchKeyDigest == null }) return emptyList<ObservedUtilityObservation>() to emptyList()
    val exposed = mutableListOf<ObservedUtilityObservation>()
    val controls = mutableListOf<ObservedUtilityObservation>()
    rows.groupBy { requireNotNull(it.matchKeyDigest) }.toSortedMap().forEach { (_, group) ->
        val treated = group.filter { it.arm == ObservedUtilityArm.EXPOSED }
            .sortedBy { it.observationIdDigest }
        val nonExposure = group.filter { it.arm == ObservedUtilityArm.NON_EXPOSURE }
            .sortedBy { it.observationIdDigest }
        val pairCount = minOf(treated.size, nonExposure.size)
        exposed += treated.take(pairCount)
        controls += nonExposure.take(pairCount)
    }
    return exposed to controls
}

private fun estimateArm(
    rows: List<ObservedUtilityObservation>,
    weighted: Boolean,
): ArmEstimate {
    val weights = rows.map { row ->
        if (!weighted) {
            1.0
        } else {
            val propensity = requireNotNull(row.propensity)
            if (row.arm == ObservedUtilityArm.EXPOSED) 1.0 / propensity else 1.0 / (1.0 - propensity)
        }
    }
    val weightSum = weights.sum()
    val mean = rows.zip(weights).sumOf { (row, weight) -> row.outcome.score() * weight } /
        weightSum
    val effectiveN = if (!weighted) {
        rows.size.toDouble()
    } else {
        weightSum * weightSum / weights.sumOf { it * it }
    }
    val denominator = 1.0 + OBSERVED_UTILITY_Z_95 * OBSERVED_UTILITY_Z_95 / effectiveN
    val center = (
        mean + OBSERVED_UTILITY_Z_95 * OBSERVED_UTILITY_Z_95 / (2.0 * effectiveN)
        ) / denominator
    val margin = OBSERVED_UTILITY_Z_95 * sqrt(
        (mean * (1.0 - mean) / effectiveN) +
            (OBSERVED_UTILITY_Z_95 * OBSERVED_UTILITY_Z_95 / (4.0 * effectiveN * effectiveN)),
    ) / denominator
    return ArmEstimate(
        mean = mean,
        lower = (center - margin).coerceIn(0.0, 1.0),
        upper = (center + margin).coerceIn(0.0, 1.0),
    )
}

private fun causalDesignPrerequisitesHold(design: ObservedUtilityDesign): Boolean =
    design.randomizedAssignment &&
        design.preRegisteredDesignDigest != null &&
        design.eligibilityDeterminedBeforeTreatment &&
        design.assignmentBeforeCompileOrInjection &&
        design.fixedOutcomeWindow

private fun ObservedUtilityOutcome.isKnown(): Boolean =
    this == ObservedUtilityOutcome.SUCCESS || this == ObservedUtilityOutcome.FAILURE

private fun ObservedUtilityOutcome.score(): Double = when (this) {
    ObservedUtilityOutcome.SUCCESS -> 1.0
    ObservedUtilityOutcome.FAILURE -> 0.0
    ObservedUtilityOutcome.UNKNOWN,
    ObservedUtilityOutcome.CENSORED,
    -> error("UNKNOWN/CENSORED outcomes cannot enter an observed utility estimate")
}
