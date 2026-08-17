package me.rerere.rikkahub.learning.exposure

import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.ProviderAttemptEvent
import me.rerere.rikkahub.learning.episode.EpisodeIdFactory
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyExposureDropRecorderTest {
    @Test
    fun `final gate drop records compiled but never injected or dispatched`() = runBlocking {
        val reservation = reservation()
        val store = FakeStore()

        assertTrue(
            store.recordDropObservation(
                PolicyExposureDropObservation(
                    reservation = reservation,
                    reasonByPolicyId = mapOf("policy-one" to "FINAL_CONTEXT_GATE_REJECTED"),
                    compiledBeforeDrop = true,
                ),
                metadata(),
                frozenNowEpochMs = 10L,
            ),
        )

        assertTrue(store.receipt.hasObserved(PolicyExposureState.RETRIEVED))
        assertTrue(store.receipt.hasObserved(PolicyExposureState.COMPILED))
        assertFalse(store.receipt.hasObserved(PolicyExposureState.INJECTED))
        assertFalse(store.receipt.hasObserved(PolicyExposureState.HOST_DISPATCHED))
        assertEquals(
            mapOf("policy-one" to "FINAL_CONTEXT_GATE_REJECTED"),
            store.dropReasons,
        )
    }

    @Test
    fun `compiler drop remains retrieval only`() = runBlocking {
        val store = FakeStore()
        assertTrue(
            store.recordDropObservation(
                PolicyExposureDropObservation(
                    reservation = reservation(),
                    reasonByPolicyId = mapOf("policy-one" to "COMPILER_POLICY_QUOTA_EXCEEDED"),
                    compiledBeforeDrop = false,
                ),
                metadata(),
                frozenNowEpochMs = 10L,
            ),
        )
        assertEquals(setOf(PolicyExposureState.RETRIEVED), store.receipt.observedStates)
    }

    private class FakeStore : PolicyExposureStore {
        lateinit var receipt: PolicyExposureReceipt
        var dropReasons: Map<String, String> = emptyMap()

        override suspend fun reserve(
            reservation: PolicyExposureReservation,
            metadata: PolicyExposureMetadata,
            frozenNowEpochMs: Long,
        ): PolicyExposureStoreResult {
            if (!::receipt.isInitialized) receipt = PolicyExposureReceipt.initial(reservation)
            return PolicyExposureStoreResult.Available(receipt)
        }

        override suspend fun recordDrops(
            reservationId: String,
            expectedStateVersion: Long,
            reasonByPolicyId: Map<String, String>,
            frozenNowEpochMs: Long,
        ): PolicyExposureStoreResult {
            check(expectedStateVersion == receipt.stateVersion)
            dropReasons = reasonByPolicyId
            return PolicyExposureStoreResult.Available(receipt)
        }

        override suspend fun observeMilestone(
            reservationId: String,
            expectedStateVersion: Long,
            state: PolicyExposureState,
            frozenNowEpochMs: Long,
        ): PolicyExposureStoreResult {
            val result = PolicyExposureStateMachine.observe(receipt, expectedStateVersion, state)
            receipt = (result as PolicyExposureMutationResult.Applied).receipt
            return PolicyExposureStoreResult.Available(receipt)
        }

        override suspend fun observeProviderAttempt(
            reservationId: String,
            expectedStateVersion: Long,
            event: ProviderAttemptEvent,
            frozenNowEpochMs: Long,
        ): PolicyExposureStoreResult = error("not used")

        override suspend fun linkOutcome(
            reservationId: String,
            expectedStateVersion: Long,
            authority: PolicyExposureOutcomeAuthority,
            frozenNowEpochMs: Long,
        ): PolicyExposureStoreResult = error("not used")

        override suspend fun load(reservationId: String): PolicyExposureStoreResult =
            PolicyExposureStoreResult.Available(receipt)
    }

    private fun reservation(): PolicyExposureReservation {
        val bundle = PolicyExposureBundle.create(
            listOf(
                PolicyExposurePolicyRef(
                    policyId = "policy-one",
                    policyRevision = 1L,
                    artifactSha256 = "a".repeat(64),
                    scope = SCOPE,
                    rank = 1,
                    estimatedTokens = 20,
                    applicabilityCohortDigest = "a".repeat(64),
                ),
            ),
        )
        return PolicyExposureReservation(
            PolicyExposureReservationKey(
                STREAM_ID,
                EpisodeIdFactory.create(STREAM_ID, LINEAGE_ID, BRANCH_ID),
                RUN_ID,
                1,
                bundle.policySetDigest,
            ),
            bundle,
        )
    }

    private fun metadata() = PolicyExposureMetadata(
        replayGeneration = 0L,
        scope = SCOPE,
        taskSignature = "task-v1",
        treatmentArm = "LEARNED_POLICY",
        modelIdentity = "model-v1",
        providerIdentity = "provider-v1",
        providerGeneration = 0L,
        toolsetFingerprint = "b".repeat(64),
        contextCompilerAbi = "recall-v1",
    )

    private companion object {
        val STREAM_ID = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val LINEAGE_ID = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val BRANCH_ID = Uuid.parse("00000000-0000-0000-0000-000000000003")
        val RUN_ID = Uuid.parse("00000000-0000-0000-0000-000000000004")
        val SCOPE = LearningScope.Assistant(
            Uuid.parse("00000000-0000-0000-0000-000000000005"),
        )
    }
}
