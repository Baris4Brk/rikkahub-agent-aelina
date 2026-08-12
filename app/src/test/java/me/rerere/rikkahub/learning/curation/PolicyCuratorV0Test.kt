package me.rerere.rikkahub.learning.curation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyCuratorV0Test {
    @Test
    fun noOpIsValidatedAndTouchesNoQueue() = runBlocking {
        var draftCalls = 0
        var reviewCalls = 0
        val curator = PolicyCuratorV0(
            PolicyDistillationRequestQueue {
                draftCalls += 1
                PolicyCuratorQueueDisposition.QUEUED
            },
            PolicyHarmReviewQueue {
                reviewCalls += 1
                PolicyCuratorQueueDisposition.QUEUED
            },
        )

        val result = curator.route(noOp(), emptySet())

        assertEquals(PolicyCuratorRoutingResult.NoOp, result)
        assertEquals(0, draftCalls)
        assertEquals(0, reviewCalls)
    }

    @Test
    fun newDraftUsesOnlyExistingDistillationQueue() = runBlocking {
        val queued = mutableListOf<PolicyDeltaCandidate>()
        var reviewCalls = 0
        val curator = PolicyCuratorV0(
            PolicyDistillationRequestQueue {
                queued += it
                PolicyCuratorQueueDisposition.DUPLICATE
            },
            PolicyHarmReviewQueue {
                reviewCalls += 1
                PolicyCuratorQueueDisposition.QUEUED
            },
        )
        val candidate = newDraft()

        val result = curator.route(candidate, setOf("episode-1", "episode-2"))

        assertEquals(
            PolicyCuratorRoutingResult.NewDraftQueued(PolicyCuratorQueueDisposition.DUPLICATE),
            result,
        )
        assertEquals(listOf(candidate), queued)
        assertEquals(0, reviewCalls)
    }

    @Test
    fun harmReviewOnlyQueuesReviewAndNeverRoutesToDistiller() = runBlocking {
        var draftCalls = 0
        val reviews = mutableListOf<PolicyDeltaCandidate>()
        val curator = PolicyCuratorV0(
            PolicyDistillationRequestQueue {
                draftCalls += 1
                PolicyCuratorQueueDisposition.QUEUED
            },
            PolicyHarmReviewQueue {
                reviews += it
                PolicyCuratorQueueDisposition.QUEUED
            },
        )
        val candidate = harmReview()

        val result = curator.route(candidate, setOf("episode-3"))

        assertEquals(
            PolicyCuratorRoutingResult.HarmReviewQueued(PolicyCuratorQueueDisposition.QUEUED),
            result,
        )
        assertEquals(listOf(candidate), reviews)
        assertEquals(0, draftCalls)
    }

    @Test
    fun forgedNewDraftTargetIsRejectedBeforeEitherQueue() = runBlocking {
        var calls = 0
        val curator = PolicyCuratorV0(
            PolicyDistillationRequestQueue {
                calls += 1
                PolicyCuratorQueueDisposition.QUEUED
            },
            PolicyHarmReviewQueue {
                calls += 1
                PolicyCuratorQueueDisposition.QUEUED
            },
        )

        val result = curator.route(
            newDraft().copy(targetPolicyId = "existing-policy", expectedRevision = 4L),
            setOf("episode-1", "episode-2"),
        )

        assertTrue(result is PolicyCuratorRoutingResult.Rejected)
        assertEquals(0, calls)
    }

    private fun noOp() = PolicyDeltaCandidate(
        operation = PolicyDeltaOperation.NO_OP,
        candidateId = null,
        inputSetHash = null,
        producerIdentity = null,
        modelIdentity = null,
        promptVersion = null,
        schemaVersion = null,
        targetPolicyId = null,
        expectedRevision = null,
        baseArtifactHash = null,
        evidenceIds = emptyList(),
        reasonCode = null,
    )

    private fun newDraft() = PolicyDeltaCandidate(
        operation = PolicyDeltaOperation.QUEUE_NEW_DRAFT,
        candidateId = "candidate-1",
        inputSetHash = "input-v1",
        producerIdentity = "distiller-v1",
        modelIdentity = "model-v1",
        promptVersion = "prompt-v1",
        schemaVersion = 1,
        targetPolicyId = null,
        expectedRevision = null,
        baseArtifactHash = null,
        evidenceIds = listOf("episode-1", "episode-2"),
        reasonCode = null,
    )

    private fun harmReview() = PolicyDeltaCandidate(
        operation = PolicyDeltaOperation.QUEUE_HARM_REVIEW,
        candidateId = null,
        inputSetHash = null,
        producerIdentity = null,
        modelIdentity = null,
        promptVersion = null,
        schemaVersion = null,
        targetPolicyId = "policy-1",
        expectedRevision = 7L,
        baseArtifactHash = "b".repeat(64),
        evidenceIds = listOf("episode-3"),
        reasonCode = "AUTHORITATIVE_HARM",
    )
}
