package me.rerere.rikkahub.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryProposalValidatorScopeTest {
    private val validator = MemoryProposalValidator()
    private val context = MemoryProposalValidationContext(
        allowedEvidenceMessageIds = setOf("message-1"),
        visibleExistingMemories = mapOf(7 to 3, 8 to 1),
        nowMs = 1_000L,
    )

    @Test
    fun `update expected revision must equal the visible frozen revision`() {
        val proposal = proposal("p1").copy(
            action = MemoryCandidateAction.UPDATE,
            targetIds = listOf(7),
            expectedRevisions = listOf(2),
        )

        val result = validator.validate(MemoryExtractionEnvelope(2, listOf(proposal)), context)

        assertTrue(result.accepted.isEmpty())
        assertEquals(MemoryProposalRejectionCode.INVALID_TARGET, result.rejected.single().code)
    }

    @Test
    fun `relations require evidence and reject self endpoints`() {
        val emptyEvidence = MemoryRelationProposal(
            sourceMemoryId = 7,
            targetMemoryId = 8,
            type = MemoryRelationType.RELATED_TO,
        )
        val self = emptyEvidence.copy(
            targetMemoryId = 7,
            evidenceMessageIds = listOf("message-1"),
        )

        val result = validator.validate(
            MemoryExtractionEnvelope(2, emptyList(), listOf(emptyEvidence, self)),
            context,
        )

        assertTrue(result.acceptedRelations.isEmpty())
        assertEquals(2, result.rejectedRelations.size)
    }

    @Test
    fun `derived relation cycles are rejected before persistence`() {
        val proposals = listOf(proposal("p1"), proposal("p2"))
        val forward = MemoryRelationProposal(
            sourceProposalKey = "p1",
            targetProposalKey = "p2",
            type = MemoryRelationType.DERIVED_FROM,
            evidenceMessageIds = listOf("message-1"),
        )
        val backward = forward.copy(sourceProposalKey = "p2", targetProposalKey = "p1")

        val result = validator.validate(
            MemoryExtractionEnvelope(2, proposals, listOf(forward, backward)),
            context,
        )

        assertEquals(listOf(forward), result.acceptedRelations)
        assertEquals(listOf(backward), result.rejectedRelations)
    }

    @Test
    fun `relation count is bounded`() {
        val relations = (0 until 13).map { index ->
            MemoryRelationProposal(
                sourceMemoryId = 7,
                targetMemoryId = 8,
                type = MemoryRelationType.RELATED_TO,
                description = "relation-$index",
                evidenceMessageIds = listOf("message-1"),
            )
        }

        val result = validator.validate(MemoryExtractionEnvelope(2, emptyList(), relations), context)

        assertEquals(12, result.acceptedRelations.size)
        assertEquals(1, result.rejectedRelations.size)
    }

    private fun proposal(key: String) = MemoryProposal(
        proposalKey = key,
        action = MemoryCandidateAction.CREATE,
        title = "Title $key",
        content = "Durable content for $key",
        confidence = 0.9f,
        evidenceMessageIds = listOf("message-1"),
    )
}
