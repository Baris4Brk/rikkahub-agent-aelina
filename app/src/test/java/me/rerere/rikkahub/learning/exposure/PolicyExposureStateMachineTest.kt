package me.rerere.rikkahub.learning.exposure

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.ProviderAttemptTerminalOutcome
import me.rerere.rikkahub.learning.episode.EpisodeIdFactory
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyExposureStateMachineTest {
    @Test
    fun `milestones advance monotonically with expected revision and duplicates consume nothing`() {
        val initial = receipt()

        val compiled = initial.observe(PolicyExposureState.COMPILED)
        assertEquals(1L, compiled.stateVersion)

        val duplicate = PolicyExposureStateMachine.observe(
            receipt = compiled,
            expectedStateVersion = 0L,
            state = PolicyExposureState.COMPILED,
        )
        assertTrue(duplicate is PolicyExposureMutationResult.Duplicate)
        assertEquals(1L, duplicate.receipt.stateVersion)

        val wrongRevision = PolicyExposureStateMachine.observe(
            receipt = compiled,
            expectedStateVersion = 0L,
            state = PolicyExposureState.INJECTED,
        )
        assertRejected(wrongRevision, PolicyExposureRejectionReason.STATE_VERSION_MISMATCH)

        val injected = compiled.observe(PolicyExposureState.INJECTED)
        val dispatched = injected.observe(PolicyExposureState.HOST_DISPATCHED)
        val progressed = dispatched.observe(PolicyExposureState.FIRST_PROGRESS)
        val finished = progressed.observe(PolicyExposureState.RESPONSE_FINISHED)
        val terminal = finished.terminal(ProviderAttemptTerminalOutcome.COMPLETED)
        val linked = terminal.observe(PolicyExposureState.OUTCOME_LINKED)

        assertEquals(PolicyExposureState.OUTCOME_LINKED, linked.latestState)
        assertTrue(linked.canAttributeUsage)
        assertTrue(linked.canAttributeOutcome)
        assertTrue(linked.canAttributeObservedUtility)
    }

    @Test
    fun `skipped compile injection and dispatch milestones are rejected`() {
        val initial = receipt()
        assertRejected(
            PolicyExposureStateMachine.observe(
                receipt = initial,
                expectedStateVersion = initial.stateVersion,
                state = PolicyExposureState.INJECTED,
            ),
            PolicyExposureRejectionReason.MISSING_PREREQUISITE,
        )

        val compiled = initial.observe(PolicyExposureState.COMPILED)
        assertRejected(
            PolicyExposureStateMachine.observe(
                receipt = compiled,
                expectedStateVersion = compiled.stateVersion,
                state = PolicyExposureState.HOST_DISPATCHED,
            ),
            PolicyExposureRejectionReason.MISSING_PREREQUISITE,
        )

        val responseWithoutProgress = compiled
            .observe(PolicyExposureState.INJECTED)
            .observe(PolicyExposureState.HOST_DISPATCHED)
            .observe(PolicyExposureState.RESPONSE_FINISHED)
        assertRejected(
            PolicyExposureStateMachine.observe(
                receipt = responseWithoutProgress,
                expectedStateVersion = responseWithoutProgress.stateVersion,
                state = PolicyExposureState.FIRST_PROGRESS,
            ),
            PolicyExposureRejectionReason.NON_MONOTONIC_STATE,
        )
    }

    @Test
    fun `terminal is independent and outcome link requires both terminal and dispatch`() {
        val injected = receipt()
            .observe(PolicyExposureState.COMPILED)
            .observe(PolicyExposureState.INJECTED)

        assertRejected(
            PolicyExposureStateMachine.observe(
                receipt = injected,
                expectedStateVersion = injected.stateVersion,
                state = PolicyExposureState.OUTCOME_LINKED,
            ),
            PolicyExposureRejectionReason.OUTCOME_LINK_REQUIRES_TERMINAL,
        )

        val failedBeforeDispatch = injected.terminal(ProviderAttemptTerminalOutcome.FAILED)
        assertFalse(failedBeforeDispatch.canAttributeUsage)
        assertRejected(
            PolicyExposureStateMachine.observe(
                receipt = failedBeforeDispatch,
                expectedStateVersion = failedBeforeDispatch.stateVersion,
                state = PolicyExposureState.OUTCOME_LINKED,
            ),
            PolicyExposureRejectionReason.OUTCOME_LINK_REQUIRES_DISPATCH,
        )

        val dispatched = receipt()
            .observe(PolicyExposureState.COMPILED)
            .observe(PolicyExposureState.INJECTED)
            .observe(PolicyExposureState.HOST_DISPATCHED)
        val failed = dispatched.terminal(ProviderAttemptTerminalOutcome.FAILED)
        val linked = failed.observe(PolicyExposureState.OUTCOME_LINKED)
        assertTrue(linked.canAttributeOutcome)
        assertFalse(linked.canAttributeObservedUtility)
    }

    @Test
    fun `terminal duplicates are idempotent and conflicting terminal is rejected`() {
        val dispatched = receipt()
            .observe(PolicyExposureState.COMPILED)
            .observe(PolicyExposureState.INJECTED)
            .observe(PolicyExposureState.HOST_DISPATCHED)
        val failed = dispatched.terminal(ProviderAttemptTerminalOutcome.FAILED)

        val duplicate = PolicyExposureStateMachine.recordTerminal(
            receipt = failed,
            expectedStateVersion = dispatched.stateVersion,
            outcome = ProviderAttemptTerminalOutcome.FAILED,
        )
        assertTrue(duplicate is PolicyExposureMutationResult.Duplicate)
        assertEquals(failed.stateVersion, duplicate.receipt.stateVersion)

        assertRejected(
            PolicyExposureStateMachine.recordTerminal(
                receipt = failed,
                expectedStateVersion = failed.stateVersion,
                outcome = ProviderAttemptTerminalOutcome.CANCELLED,
            ),
            PolicyExposureRejectionReason.TERMINAL_CONFLICT,
        )
    }

    @Test
    fun `retry gets a new ordinal and reservation identity without new Episode support`() {
        val first = reservation()
        val retry = first.nextRetry()

        assertEquals(1, first.key.attemptOrdinal)
        assertEquals(2, retry.key.attemptOrdinal)
        assertEquals(first.key.streamId, retry.key.streamId)
        assertEquals(first.key.episodeId, retry.key.episodeId)
        assertEquals(first.key.logicalRunId, retry.key.logicalRunId)
        assertEquals(first.bundle, retry.bundle)
        assertNotEquals(first.key.reservationId, retry.key.reservationId)
    }

    @Test
    fun `reservation identity includes every required key dimension`() {
        val original = reservation()
        val key = original.key
        val otherStream = Uuid.parse("00000000-0000-0000-0000-000000000011")
        val otherRun = Uuid.parse("00000000-0000-0000-0000-000000000012")
        val otherEpisode = EpisodeIdFactory.create(otherStream, LINEAGE_ID, BRANCH_ID)
        val otherBundle = bundle(policyId = "policy-two")

        listOf(
            key.copy(streamId = otherStream),
            key.copy(episodeId = otherEpisode),
            key.copy(logicalRunId = otherRun),
            key.copy(attemptOrdinal = 2),
            key.copy(policySetDigest = otherBundle.policySetDigest),
        ).forEach { mutated ->
            assertNotEquals(key.reservationId, mutated.reservationId)
        }
    }

    @Test
    fun `multi Policy outcome remains one bundle attribution`() {
        val bundle = PolicyExposureBundle.create(
            listOf(
                policy(policyId = "policy-b", rank = 2, artifact = "b".repeat(64)),
                policy(policyId = "policy-a", rank = 1, artifact = "a".repeat(64)),
            ),
        )
        val reservation = reservation(bundle)
        val linked = PolicyExposureReceipt.initial(reservation)
            .observe(PolicyExposureState.COMPILED)
            .observe(PolicyExposureState.INJECTED)
            .observe(PolicyExposureState.HOST_DISPATCHED)
            .observe(PolicyExposureState.RESPONSE_FINISHED)
            .terminal(ProviderAttemptTerminalOutcome.COMPLETED)
            .observe(PolicyExposureState.OUTCOME_LINKED)

        assertEquals(listOf("policy-a", "policy-b"), bundle.policies.map { it.policyId })
        assertEquals(2, linked.reservation.bundle.policies.size)
        assertEquals(ProviderAttemptTerminalOutcome.COMPLETED, linked.terminalOutcome)
        assertTrue(linked.canAttributeObservedUtility)
    }

    @Test
    fun `policy set identity includes exact applicability cohort`() {
        val original = PolicyExposureBundle.create(listOf(policy("policy-one", 1)))
        val drifted = PolicyExposureBundle.create(
            listOf(policy("policy-one", 1, cohort = "b".repeat(64))),
        )
        assertNotEquals(original.policySetDigest, drifted.policySetDigest)
    }

    @Test
    fun `diagnostic strings redact stream Episode run Policy and digest identities`() {
        val reservation = reservation()
        val receipt = PolicyExposureReceipt.initial(reservation)
        val text = listOf(
            reservation.key.toString(),
            reservation.bundle.toString(),
            reservation.bundle.policies.single().toString(),
            reservation.toString(),
            receipt.toString(),
        ).joinToString("\n")

        listOf(
            reservation.key.streamId.toString(),
            reservation.key.episodeId.value,
            reservation.key.logicalRunId.toString(),
            reservation.key.policySetDigest,
            reservation.key.reservationId,
            reservation.bundle.policies.single().policyId,
            reservation.bundle.policies.single().artifactSha256,
        ).forEach { secretIdentity ->
            assertFalse(text.contains(secretIdentity))
        }
        assertTrue(text.contains("ids=<redacted>"))
    }

    @Test
    fun `bundle rejects duplicate Policy and rank`() {
        assertThrows(IllegalArgumentException::class.java) {
            PolicyExposureBundle.create(
                listOf(policy(policyId = "same", rank = 1), policy(policyId = "same", rank = 2)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PolicyExposureBundle.create(
                listOf(policy(policyId = "one", rank = 1), policy(policyId = "two", rank = 1)),
            )
        }
    }

    private fun receipt(): PolicyExposureReceipt = PolicyExposureReceipt.initial(reservation())

    private fun reservation(bundle: PolicyExposureBundle = bundle()): PolicyExposureReservation {
        val episodeId = EpisodeIdFactory.create(STREAM_ID, LINEAGE_ID, BRANCH_ID)
        return PolicyExposureReservation(
            key = PolicyExposureReservationKey(
                streamId = STREAM_ID,
                episodeId = episodeId,
                logicalRunId = RUN_ID,
                attemptOrdinal = 1,
                policySetDigest = bundle.policySetDigest,
            ),
            bundle = bundle,
        )
    }

    private fun bundle(policyId: String = "policy-one"): PolicyExposureBundle =
        PolicyExposureBundle.create(listOf(policy(policyId = policyId, rank = 1)))

    private fun policy(
        policyId: String,
        rank: Int,
        artifact: String = "a".repeat(64),
        cohort: String = "a".repeat(64),
    ): PolicyExposurePolicyRef = PolicyExposurePolicyRef(
        policyId = policyId,
        policyRevision = 3L,
        artifactSha256 = artifact,
        scope = LearningScope.Assistant(ASSISTANT_ID),
        rank = rank,
        estimatedTokens = 24,
        applicabilityCohortDigest = cohort,
    )

    private fun PolicyExposureReceipt.observe(state: PolicyExposureState): PolicyExposureReceipt {
        val result = PolicyExposureStateMachine.observe(this, stateVersion, state)
        assertTrue("Expected $state to apply, got $result", result is PolicyExposureMutationResult.Applied)
        return result.receipt
    }

    private fun PolicyExposureReceipt.terminal(
        outcome: ProviderAttemptTerminalOutcome,
    ): PolicyExposureReceipt {
        val result = PolicyExposureStateMachine.recordTerminal(this, stateVersion, outcome)
        assertTrue(
            "Expected terminal $outcome to apply, got $result",
            result is PolicyExposureMutationResult.Applied,
        )
        return result.receipt
    }

    private fun assertRejected(
        result: PolicyExposureMutationResult,
        reason: PolicyExposureRejectionReason,
    ) {
        assertTrue("Expected rejection, got $result", result is PolicyExposureMutationResult.Rejected)
        assertEquals(reason, (result as PolicyExposureMutationResult.Rejected).reason)
    }

    private companion object {
        val STREAM_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val LINEAGE_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000002")
        val BRANCH_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000003")
        val RUN_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000004")
        val ASSISTANT_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000005")
    }
}
