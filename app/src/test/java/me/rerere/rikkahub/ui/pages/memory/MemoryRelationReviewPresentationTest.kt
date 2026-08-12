package me.rerere.rikkahub.ui.pages.memory

import me.rerere.rikkahub.data.db.entity.MemoryRelationCandidateEntity
import me.rerere.rikkahub.memory.MemoryRelationReviewCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryRelationReviewPresentationTest {
    @Test
    fun `review command remains bound to the row scope after the visible scope changes`() {
        val staleVisibleRow = relationCandidate(scopeId = "assistant-a")

        assertEquals(
            MemoryRelationReviewCommand.Accept(
                relationCandidateId = "relation-1",
                expectedScopeId = "assistant-a",
            ),
            staleVisibleRow.reviewCommand(accept = true),
        )
        assertEquals(
            MemoryRelationReviewCommand.Reject(
                relationCandidateId = "relation-1",
                expectedScopeId = "assistant-a",
            ),
            staleVisibleRow.reviewCommand(accept = false),
        )
    }

    @Test
    fun `relation card derives explicit endpoints and a distinct evidence count`() {
        val candidate = relationCandidate(scopeId = "assistant-a").copy(
            sourceMemoryId = 42,
            sourceProposalKey = "ignored-when-memory-is-known",
            targetProposalKey = "new-preference",
            evidenceMessageIdsJson = """["message-1","message-1","message-2"]""",
        )

        assertEquals(
            MemoryRelationEndpointUi(MemoryRelationEndpointKind.MEMORY, "42"),
            candidate.sourceEndpointUi(),
        )
        assertEquals(
            MemoryRelationEndpointUi(MemoryRelationEndpointKind.PROPOSAL, "new-preference"),
            candidate.targetEndpointUi(),
        )
        assertEquals(2, candidate.evidenceCount())
        assertEquals(0, candidate.copy(evidenceMessageIdsJson = "not-json").evidenceCount())
    }

    private fun relationCandidate(scopeId: String) = MemoryRelationCandidateEntity(
        id = "relation-1",
        batchId = "batch-1",
        relationType = "SUPPORTS",
        weight = 0.8f,
        description = "The two memories describe the same stable preference.",
        status = "PENDING",
        createdAtMs = 1_000L,
        scopeId = scopeId,
        createdByAssistantId = "assistant-a",
    )
}
