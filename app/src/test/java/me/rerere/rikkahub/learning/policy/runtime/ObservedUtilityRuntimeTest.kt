package me.rerere.rikkahub.learning.policy.runtime

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.ProviderAttemptTerminalOutcome
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.exposure.PolicyExposureBundle
import me.rerere.rikkahub.learning.exposure.PolicyExposurePolicyRef
import me.rerere.rikkahub.learning.exposure.PolicyExposureReceipt
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservation
import me.rerere.rikkahub.learning.exposure.PolicyExposureReservationKey
import me.rerere.rikkahub.learning.exposure.PolicyExposureState
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.ObservedUtilityAssignmentMethod
import me.rerere.rikkahub.learning.policy.ObservedUtilityCohortIdentity
import me.rerere.rikkahub.learning.policy.ObservedUtilityDesign
import me.rerere.rikkahub.learning.policy.ObservedUtilityEstimationResult
import me.rerere.rikkahub.learning.policy.ObservedUtilitySelectionMethod
import me.rerere.rikkahub.learning.policy.PolicyAuthoritativeTerminalOutcome
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.policy.PolicySafetyAbstainReason
import me.rerere.rikkahub.learning.policy.PolicySafetyGovernorResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ObservedUtilityRuntimeTest {
    @Test
    fun `matched durable exposed and baseline facts estimate then persist exact receipt`() =
        runBlocking {
            val receipt = exposedReceipt()
            val rows = listOf(
                exposedRow(1, receipt, PolicyAuthoritativeTerminalOutcome.SUCCESS, MATCH),
                baselineRow(2, PolicyAuthoritativeTerminalOutcome.FAILURE, MATCH),
            )
            val persisted = mutableListOf<ObservedUtilityEvaluationReceipt>()

            val result = runtime(rows, persisted).evaluate(request(receipt), 1_300L)

            result as ObservedUtilityRuntimeResult.Evaluated
            assertTrue(result.estimation is ObservedUtilityEstimationResult.Estimated)
            assertEquals(ObservedUtilityPersistenceDisposition.APPLIED, result.persistence)
            assertEquals(1, persisted.size)
            val durable = persisted.single()
            assertEquals(FENCE, durable.fence)
            assertEquals(ObservedUtilityRuntimeStatus.ESTIMATED, durable.status)
            assertNotNull(durable.observedUtilityDelta)
            assertNotNull(durable.utilityUncertainty)
            assertEquals(2, durable.sampleSize)
        }

    @Test
    fun `insufficient evidence persists explicit ABSTAIN instead of fabricating zero`() =
        runBlocking {
            val receipt = exposedReceipt()
            val persisted = mutableListOf<ObservedUtilityEvaluationReceipt>()

            val result = runtime(emptyList(), persisted).evaluate(request(receipt), 1_300L)

            result as ObservedUtilityRuntimeResult.Evaluated
            assertTrue(result.estimation is ObservedUtilityEstimationResult.Abstained)
            assertEquals(ObservedUtilityRuntimeStatus.ABSTAINED, persisted.single().status)
            assertEquals(null, persisted.single().observedUtilityDelta)
            assertEquals(null, persisted.single().utilityUncertainty)
        }

    @Test
    fun `producer identity change forms a new cohort and mixed rows abstain`() = runBlocking {
        val receipt = exposedReceipt()
        val rows = listOf(
            exposedRow(1, receipt, PolicyAuthoritativeTerminalOutcome.SUCCESS, MATCH),
            baselineRow(
                2,
                PolicyAuthoritativeTerminalOutcome.FAILURE,
                MATCH,
                cohort = COHORT.copy(producerModelIdentity = "producer-model-v2"),
            ),
        )
        val persisted = mutableListOf<ObservedUtilityEvaluationReceipt>()

        val result = runtime(rows, persisted).evaluate(request(receipt), 1_300L)
            as ObservedUtilityRuntimeResult.Evaluated

        val abstained = result.estimation as ObservedUtilityEstimationResult.Abstained
        assertEquals(
            me.rerere.rikkahub.learning.policy.ObservedUtilityAbstainReason.COHORT_MISMATCH,
            abstained.reason,
        )
        assertEquals(ObservedUtilityRuntimeStatus.ABSTAINED, persisted.single().status)
    }

    @Test
    fun `bounded negative association creates advisory only after durable estimate`() = runBlocking {
        val receipt = exposedReceipt()
        val rows = buildList {
            repeat(100) { index ->
                val match = (index + 1).toString(16).padStart(64, '0')
                add(exposedRow(index * 2 + 1, receipt, PolicyAuthoritativeTerminalOutcome.FAILURE, match))
                add(baselineRow(index * 2 + 2, PolicyAuthoritativeTerminalOutcome.SUCCESS, match))
            }
        }
        val persisted = mutableListOf<ObservedUtilityEvaluationReceipt>()
        var advisories = 0
        val runtime = runtime(
            rows,
            persisted,
            ObservedUtilityAdvisoryPort { fence, _, _ ->
                assertEquals(FENCE, fence)
                advisories += 1
                PolicySafetyGovernorResult.Abstained(
                    PolicySafetyAbstainReason.REVIEW_QUEUE_UNAVAILABLE,
                )
            },
        )

        val result = runtime.evaluate(request(receipt), 1_300L)
            as ObservedUtilityRuntimeResult.Evaluated

        val estimate = (result.estimation as ObservedUtilityEstimationResult.Estimated).estimate
        assertTrue(estimate.confidenceInterval.upper < 0.0)
        assertEquals(1, advisories)
        assertEquals(1, persisted.size)
    }

    private fun runtime(
        rows: List<DurableObservedUtilityRow>,
        persisted: MutableList<ObservedUtilityEvaluationReceipt>,
        advisory: ObservedUtilityAdvisoryPort? = null,
    ) = ProductionObservedUtilityRuntime(
        source = object : DurableObservedUtilitySource {
            override suspend fun loadExact(
                request: ObservedUtilityRuntimeRequest,
            ) = DurableObservedUtilityBatchResult.Ready(
                DurableObservedUtilityBatch(
                    rows = rows,
                    sourceWatermarkDigest = "e".repeat(64),
                    complete = true,
                ),
            )

            override suspend fun revalidatePolicyFence(fence: PolicyMutationFence): Boolean =
                fence == FENCE
        },
        store = ObservedUtilityEvaluationStore { receipt ->
            persisted += receipt
            ObservedUtilityPersistenceDisposition.APPLIED
        },
        advisory = advisory,
    )

    private fun request(receipt: PolicyExposureReceipt) = ObservedUtilityRuntimeRequest(
        fence = FENCE,
        design = ObservedUtilityDesign(
            targetPolicySetDigest = receipt.reservation.bundle.policySetDigest,
            assignmentMethod = ObservedUtilityAssignmentMethod.MATCHED_NON_EXPOSURE,
            selectionMethod = ObservedUtilitySelectionMethod.EXACT_MATCHED_COHORT,
            preRegisteredDesignDigest = null,
            exposureRecordingReliable = true,
            exposureContractVersion = 1,
            eligibilityDeterminedBeforeTreatment = false,
            assignmentBeforeCompileOrInjection = false,
            fixedOutcomeWindow = true,
            randomizedAssignment = false,
        ),
        expectedCohortDigest = observedUtilityCohortDigest(COHORT),
        sourceWindowStartMs = 1L,
        sourceWindowEndMs = 1_000L,
    )

    private fun exposedRow(
        index: Int,
        receipt: PolicyExposureReceipt,
        outcome: PolicyAuthoritativeTerminalOutcome,
        match: String,
    ) = DurableObservedUtilityRow(
        durableObservationIdentityDigest = digest(index),
        arm = me.rerere.rikkahub.learning.policy.ObservedUtilityArm.EXPOSED,
        authoritativeOutcome = outcome,
        cohort = COHORT,
        policySetDigest = receipt.reservation.bundle.policySetDigest,
        matchKeyDigest = match,
        exposureReceipt = receipt,
        authoritativeOutcomeCommitted = true,
    )

    private fun baselineRow(
        index: Int,
        outcome: PolicyAuthoritativeTerminalOutcome,
        match: String,
        cohort: ObservedUtilityCohortIdentity = COHORT,
    ) = DurableObservedUtilityRow(
        durableObservationIdentityDigest = digest(index),
        arm = me.rerere.rikkahub.learning.policy.ObservedUtilityArm.NON_EXPOSURE,
        authoritativeOutcome = outcome,
        cohort = cohort,
        policySetDigest = exposedReceipt().reservation.bundle.policySetDigest,
        matchKeyDigest = match,
        baselineHostDispatched = true,
        baselineProgressOrResponse = true,
        authoritativeOutcomeCommitted = true,
    )

    private fun digest(index: Int): String = index.toString(16).padStart(64, '0')

    private fun exposedReceipt(): PolicyExposureReceipt {
        val bundle = PolicyExposureBundle.create(
            listOf(
                PolicyExposurePolicyRef(
                    policyId = FENCE.policyId,
                    policyRevision = FENCE.expectedContentRevision,
                    artifactSha256 = FENCE.expectedArtifactHash,
                    scope = FENCE.scope,
                    rank = 1,
                    estimatedTokens = 20,
                applicabilityCohortDigest = "a".repeat(64),
                ),
            ),
        )
        return PolicyExposureReceipt.restore(
            reservation = PolicyExposureReservation(
                key = PolicyExposureReservationKey(
                    streamId = Uuid.parse("00000000-0000-0000-0000-000000000631"),
                    episodeId = requireNotNull(
                        EpisodeId.parseOrNull("episode-v1:${"f".repeat(64)}"),
                    ),
                    logicalRunId = Uuid.parse("00000000-0000-0000-0000-000000000632"),
                    attemptOrdinal = 1,
                    policySetDigest = bundle.policySetDigest,
                ),
                bundle = bundle,
            ),
            observedStates = setOf(
                PolicyExposureState.RETRIEVED,
                PolicyExposureState.COMPILED,
                PolicyExposureState.INJECTED,
                PolicyExposureState.HOST_DISPATCHED,
                PolicyExposureState.RESPONSE_FINISHED,
                PolicyExposureState.OUTCOME_LINKED,
            ),
            stateVersion = 6L,
            terminalOutcome = ProviderAttemptTerminalOutcome.COMPLETED,
        )
    }

    private companion object {
        const val MATCH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val FENCE = PolicyMutationFence(
            policyId = "policy-utility-runtime",
            scope = LearningScope.Assistant(
                Uuid.parse("00000000-0000-0000-0000-000000000633"),
            ),
            expectedRevision = 9L,
            expectedContentRevision = 4L,
            expectedArtifactHash = "b".repeat(64),
        )
        val COHORT = ObservedUtilityCohortIdentity(
            taskSignature = "task-v1",
            taskSignatureVersion = 1,
            modelIdentity = "model-v1",
            modelVersion = "model-version-v1",
            providerIdentity = "provider-v1",
            providerVersion = "provider-version-v1",
            toolsetFingerprint = "c".repeat(64),
            toolSchemaVersion = "tool-schema-v1",
            producerModelIdentity = "producer-model-v1",
            producerProviderIdentity = "producer-provider-v1",
            producerConfigurationIdentity = "producer-config-v1",
            producerConfigurationGeneration = 1L,
            outcomeDefinitionVersion = "outcome-v1",
            outcomeWindowIdentity = "window-v1",
        )
    }
}

