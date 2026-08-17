package me.rerere.rikkahub.learning.policy

import me.rerere.rikkahub.data.ai.ProviderAttemptTerminalOutcome
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.exposure.PolicyExposureBundle
import me.rerere.rikkahub.learning.exposure.PolicyExposurePolicyRef
import me.rerere.rikkahub.learning.exposure.PolicyExposureReceipt
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservation
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservationKey
import me.rerere.rikkahub.learning.exposure.PolicyExposureState
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ObservedUtilityEstimatorTest {
    @Test
    fun `matched cohort estimates observed association and reports unknown censored counts`() {
        val match = "1".repeat(64)
        val observations = listOf(
            knownExposed(1, ObservedUtilityOutcome.SUCCESS, matchKey = match),
            knownControl(2, ObservedUtilityOutcome.FAILURE, matchKey = match),
            unknownExposed(3),
            censoredControl(4),
        )

        val result = ObservedUtilityEstimator.estimate(matchedDesign(), observations)
            as ObservedUtilityEstimationResult.Estimated

        assertEquals(OBSERVED_UTILITY_METRIC_NAME, result.estimate.metricName)
        assertEquals(OBSERVED_UTILITY_INTERPRETATION_NAME, result.estimate.interpretationName)
        assertEquals(1.0, result.estimate.observedUtilityDelta, 0.0)
        assertEquals(2, result.estimate.sampleSize)
        assertEquals(1, result.estimate.unknownCount)
        assertEquals(1, result.estimate.censoredCount)
        assertEquals(
            ObservedUtilityCausalInterpretation.NOT_CLAIMED,
            result.estimate.causalInterpretation,
        )
        assertTrue(result.estimate.confidenceInterval.lower <= result.estimate.observedUtilityDelta)
        assertTrue(result.estimate.confidenceInterval.upper >= result.estimate.observedUtilityDelta)
    }

    @Test
    fun `missing exact matched non exposure cohort returns abstain`() {
        val result = ObservedUtilityEstimator.estimate(
            matchedDesign(),
            listOf(
                knownExposed(1, ObservedUtilityOutcome.SUCCESS, matchKey = "1".repeat(64)),
                knownControl(2, ObservedUtilityOutcome.FAILURE, matchKey = "2".repeat(64)),
            ),
        )

        assertAbstained(ObservedUtilityAbstainReason.MATCHED_COHORT_MISSING, result)
        val abstained = result as ObservedUtilityEstimationResult.Abstained
        assertEquals(2, abstained.knownSampleSize)
        assertEquals(ObservedUtilityAssignmentMethod.MATCHED_NON_EXPOSURE, abstained.assignmentMethod)
        assertEquals(COHORT, abstained.cohort)
        assertNull(abstained.confidenceInterval)
    }

    @Test
    fun `holdout without preregistration and propensity without values both abstain`() {
        val rows = listOf(
            knownExposed(1, ObservedUtilityOutcome.SUCCESS),
            knownControl(2, ObservedUtilityOutcome.FAILURE),
        )
        val missingHoldout = ObservedUtilityEstimator.estimate(
            baseDesign(
                assignment = ObservedUtilityAssignmentMethod.EXPLICIT_HOLDOUT,
                selection = ObservedUtilitySelectionMethod.PRE_REGISTERED_HOLDOUT,
                preRegistered = null,
            ),
            rows,
        )
        val missingPropensity = ObservedUtilityEstimator.estimate(
            baseDesign(
                assignment = ObservedUtilityAssignmentMethod.PROPENSITY_WEIGHTED,
                selection = ObservedUtilitySelectionMethod.PRE_REGISTERED_PROPENSITY,
                preRegistered = "9".repeat(64),
            ),
            rows,
        )

        assertAbstained(ObservedUtilityAbstainReason.HOLDOUT_NOT_EXPLICIT, missingHoldout)
        assertAbstained(ObservedUtilityAbstainReason.PROPENSITY_MISSING, missingPropensity)
    }

    @Test
    fun `producer model change is a new cohort and never silently combines`() {
        val changed = COHORT.copy(producerModelIdentity = "changed-model")
        val result = ObservedUtilityEstimator.estimate(
            baseDesign(
                assignment = ObservedUtilityAssignmentMethod.EXPLICIT_HOLDOUT,
                selection = ObservedUtilitySelectionMethod.PRE_REGISTERED_HOLDOUT,
                preRegistered = "9".repeat(64),
            ),
            listOf(
                knownExposed(1, ObservedUtilityOutcome.SUCCESS),
                knownControl(2, ObservedUtilityOutcome.FAILURE, cohort = changed),
            ),
        )

        assertAbstained(ObservedUtilityAbstainReason.COHORT_MISMATCH, result)
    }

    @Test
    fun `known treated outcome requires injected dispatched progress and authority link`() {
        val invalidExposure = knownExposed(1, ObservedUtilityOutcome.SUCCESS).copy(
            exposureReceipt = exposureReceipt(1, BUNDLE, outcomeEligible = false),
        )
        val result = ObservedUtilityEstimator.estimate(
            baseDesign(
                assignment = ObservedUtilityAssignmentMethod.EXPLICIT_HOLDOUT,
                selection = ObservedUtilitySelectionMethod.PRE_REGISTERED_HOLDOUT,
                preRegistered = "9".repeat(64),
            ),
            listOf(invalidExposure, knownControl(2, ObservedUtilityOutcome.FAILURE)),
        )

        assertAbstained(ObservedUtilityAbstainReason.EXPOSURE_NOT_ELIGIBLE, result)
    }

    @Test
    fun `preregistered pre treatment randomized holdout may be labeled causal eligible`() {
        val design = baseDesign(
            assignment = ObservedUtilityAssignmentMethod.EXPLICIT_HOLDOUT,
            selection = ObservedUtilitySelectionMethod.PRE_REGISTERED_HOLDOUT,
            preRegistered = "9".repeat(64),
        ).copy(
            eligibilityDeterminedBeforeTreatment = true,
            assignmentBeforeCompileOrInjection = true,
            fixedOutcomeWindow = true,
            randomizedAssignment = true,
        )
        val result = ObservedUtilityEstimator.estimate(
            design,
            listOf(
                knownExposed(1, ObservedUtilityOutcome.SUCCESS),
                knownControl(2, ObservedUtilityOutcome.FAILURE),
            ),
        ) as ObservedUtilityEstimationResult.Estimated

        assertEquals(
            ObservedUtilityCausalInterpretation.PREREGISTERED_RANDOMIZED_DESIGN_ELIGIBLE,
            result.estimate.causalInterpretation,
        )
        assertEquals(OBSERVED_UTILITY_INTERPRETATION_NAME, result.estimate.interpretationName)
    }

    @Test
    fun `multi policy bundle cannot assign utility to one policy without isolation`() {
        val multiBundle = PolicyExposureBundle.create(
            listOf(
                policyRef(POLICY_ID, 1),
                policyRef("policy-two", 2, artifact = "b".repeat(64)),
            ),
        )
        val design = baseDesign(
            assignment = ObservedUtilityAssignmentMethod.EXPLICIT_HOLDOUT,
            selection = ObservedUtilitySelectionMethod.PRE_REGISTERED_HOLDOUT,
            preRegistered = "9".repeat(64),
            bundle = multiBundle,
        ).copy(
            attributionUnit = ObservedUtilityAttributionUnit.INDIVIDUAL_POLICY,
            targetPolicyId = POLICY_ID,
        )
        val rows = listOf(
            knownExposed(
                index = 1,
                outcome = ObservedUtilityOutcome.SUCCESS,
                bundle = multiBundle,
            ),
            knownControl(
                index = 2,
                outcome = ObservedUtilityOutcome.FAILURE,
                bundle = multiBundle,
            ),
        )

        assertAbstained(
            ObservedUtilityAbstainReason.CO_EXPOSURE_NOT_IDENTIFIABLE,
            ObservedUtilityEstimator.estimate(design, rows),
        )
    }

    @Test
    fun `valid propensity assignment reports raw sample and finite interval`() {
        val design = baseDesign(
            assignment = ObservedUtilityAssignmentMethod.PROPENSITY_WEIGHTED,
            selection = ObservedUtilitySelectionMethod.PRE_REGISTERED_PROPENSITY,
            preRegistered = "9".repeat(64),
        )
        val result = ObservedUtilityEstimator.estimate(
            design,
            listOf(
                knownExposed(1, ObservedUtilityOutcome.SUCCESS, propensity = 0.6),
                knownExposed(2, ObservedUtilityOutcome.FAILURE, propensity = 0.7),
                knownControl(3, ObservedUtilityOutcome.FAILURE, propensity = 0.4),
                knownControl(4, ObservedUtilityOutcome.SUCCESS, propensity = 0.3),
            ),
        ) as ObservedUtilityEstimationResult.Estimated

        assertEquals(4, result.estimate.sampleSize)
        assertTrue(result.estimate.observedUtilityDelta.isFinite())
        assertTrue(result.estimate.confidenceInterval.lower.isFinite())
        assertTrue(result.estimate.confidenceInterval.upper.isFinite())
    }

    private fun matchedDesign() = baseDesign(
        assignment = ObservedUtilityAssignmentMethod.MATCHED_NON_EXPOSURE,
        selection = ObservedUtilitySelectionMethod.EXACT_MATCHED_COHORT,
        preRegistered = null,
    )

    private fun baseDesign(
        assignment: ObservedUtilityAssignmentMethod,
        selection: ObservedUtilitySelectionMethod,
        preRegistered: String?,
        bundle: PolicyExposureBundle = BUNDLE,
    ) = ObservedUtilityDesign(
        targetPolicySetDigest = bundle.policySetDigest,
        assignmentMethod = assignment,
        selectionMethod = selection,
        preRegisteredDesignDigest = preRegistered,
        exposureRecordingReliable = true,
        exposureContractVersion = 1,
        eligibilityDeterminedBeforeTreatment = false,
        assignmentBeforeCompileOrInjection = false,
        fixedOutcomeWindow = false,
        randomizedAssignment = false,
    )

    private fun knownExposed(
        index: Int,
        outcome: ObservedUtilityOutcome,
        matchKey: String? = null,
        propensity: Double? = null,
        cohort: ObservedUtilityCohortIdentity = COHORT,
        bundle: PolicyExposureBundle = BUNDLE,
    ) = ObservedUtilityObservation(
        observationIdDigest = digestFor(index),
        arm = ObservedUtilityArm.EXPOSED,
        outcome = outcome,
        cohort = cohort,
        policySetDigest = bundle.policySetDigest,
        matchKeyDigest = matchKey,
        propensity = propensity,
        exposureReceipt = exposureReceipt(index, bundle, outcomeEligible = true),
    )

    private fun knownControl(
        index: Int,
        outcome: ObservedUtilityOutcome,
        matchKey: String? = null,
        propensity: Double? = null,
        cohort: ObservedUtilityCohortIdentity = COHORT,
        bundle: PolicyExposureBundle = BUNDLE,
    ) = ObservedUtilityObservation(
        observationIdDigest = digestFor(index),
        arm = ObservedUtilityArm.NON_EXPOSURE,
        outcome = outcome,
        cohort = cohort,
        policySetDigest = bundle.policySetDigest,
        matchKeyDigest = matchKey,
        propensity = propensity,
        baselineHostDispatched = true,
        baselineProgressOrResponse = true,
        authoritativeOutcomeCommitted = true,
    )

    private fun unknownExposed(index: Int) = ObservedUtilityObservation(
        observationIdDigest = digestFor(index),
        arm = ObservedUtilityArm.EXPOSED,
        outcome = ObservedUtilityOutcome.UNKNOWN,
        cohort = COHORT,
        policySetDigest = BUNDLE.policySetDigest,
        exposureReceipt = exposureReceipt(index, BUNDLE, outcomeEligible = false),
    )

    private fun censoredControl(index: Int) = ObservedUtilityObservation(
        observationIdDigest = digestFor(index),
        arm = ObservedUtilityArm.NON_EXPOSURE,
        outcome = ObservedUtilityOutcome.CENSORED,
        cohort = COHORT,
        policySetDigest = BUNDLE.policySetDigest,
    )

    private fun exposureReceipt(
        index: Int,
        bundle: PolicyExposureBundle,
        outcomeEligible: Boolean,
    ): PolicyExposureReceipt {
        val reservation = PolicyExposureReservation(
            key = PolicyExposureReservationKey(
                streamId = uuid(100 + index),
                episodeId = requireNotNull(
                    EpisodeId.parseOrNull("episode-v1:${digestFor(100 + index)}"),
                ),
                logicalRunId = uuid(200 + index),
                attemptOrdinal = 1,
                policySetDigest = bundle.policySetDigest,
            ),
            bundle = bundle,
        )
        val states = buildSet {
            add(PolicyExposureState.RETRIEVED)
            add(PolicyExposureState.COMPILED)
            add(PolicyExposureState.INJECTED)
            add(PolicyExposureState.HOST_DISPATCHED)
            if (outcomeEligible) {
                add(PolicyExposureState.RESPONSE_FINISHED)
                add(PolicyExposureState.OUTCOME_LINKED)
            }
        }
        return PolicyExposureReceipt.restore(
            reservation = reservation,
            observedStates = states,
            stateVersion = states.size.toLong(),
            terminalOutcome = if (outcomeEligible) {
                ProviderAttemptTerminalOutcome.COMPLETED
            } else {
                ProviderAttemptTerminalOutcome.CANCELLED
            },
        )
    }

    private fun policyRef(
        policyId: String,
        rank: Int,
        artifact: String = "a".repeat(64),
    ) = PolicyExposurePolicyRef(
        policyId = policyId,
        policyRevision = 3L,
        artifactSha256 = artifact,
        scope = SCOPE,
        rank = rank,
        estimatedTokens = 20,
        applicabilityCohortDigest = "a".repeat(64),
    )

    private fun assertAbstained(
        reason: ObservedUtilityAbstainReason,
        result: ObservedUtilityEstimationResult,
    ) {
        assertTrue(result is ObservedUtilityEstimationResult.Abstained)
        assertEquals(reason, (result as ObservedUtilityEstimationResult.Abstained).reason)
    }

    private companion object {
        const val POLICY_ID = "policy-one"
        val SCOPE = LearningScope.Assistant(
            Uuid.parse("00000000-0000-0000-0000-000000000401"),
        )
        val BUNDLE = PolicyExposureBundle.create(
            listOf(
                PolicyExposurePolicyRef(
                    policyId = POLICY_ID,
                    policyRevision = 3L,
                    artifactSha256 = "a".repeat(64),
                    scope = SCOPE,
                    rank = 1,
                    estimatedTokens = 20,
                    applicabilityCohortDigest = "a".repeat(64),
                ),
            ),
        )
        val COHORT = ObservedUtilityCohortIdentity(
            taskSignature = "task-signature-v1",
            taskSignatureVersion = 1,
            modelIdentity = "runtime-model",
            modelVersion = "model-v1",
            providerIdentity = "runtime-provider",
            providerVersion = "provider-v1",
            toolsetFingerprint = "c".repeat(64),
            toolSchemaVersion = "tools-v1",
            producerModelIdentity = "producer-model",
            producerProviderIdentity = "producer-provider",
            producerConfigurationIdentity = "producer-config",
            producerConfigurationGeneration = 2L,
            outcomeDefinitionVersion = "outcome-v1",
            outcomeWindowIdentity = "window-v1",
        )

        fun digestFor(index: Int): String =
            (index.toString(16).last()).toString().repeat(64)

        fun uuid(index: Int): Uuid = Uuid.parse(
            "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
        )
    }
}
