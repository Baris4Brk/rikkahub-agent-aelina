package me.rerere.rikkahub.workflow.repository

import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.model.WorkflowOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnedWorkflowEraseTombstoneContractTest {
    private val candidateId = "workflow-candidate-v1:${"1".padStart(64, '0')}"

    @Test
    fun claimContainsOnlyContentFreeFenceIdentityAndCannotMatchPromotionArtifact() {
        val claim = LearnedWorkflowEraseTombstoneContract.claimEntity(candidateId, 42L)

        assertEquals(
            LearnedWorkflowEraseTombstoneContract.RowClass.CLAIM_TOMBSTONE,
            LearnedWorkflowEraseTombstoneContract.classify(candidateId, claim),
        )
        assertFalse(claim.enabled)
        assertEquals("{}", claim.definitionJson)
        assertNull(claim.description)
        assertNull(claim.authoringAssistantId)
        assertNull(claim.sourceArtifactHash)
        assertNull(claim.grantDigest)
        assertEquals("[]", claim.capabilitySnapshotJson)
        assertEquals("[]", claim.toolSchemaFingerprintsJson)
    }

    @Test
    fun exactLearnedDefinitionRedactsButUserOrMismatchedProvenanceConflicts() {
        val live = liveLearnedRow()
        assertEquals(
            LearnedWorkflowEraseTombstoneContract.RowClass.LIVE_DEFINITION,
            LearnedWorkflowEraseTombstoneContract.classify(candidateId, live),
        )
        assertEquals(
            LearnedWorkflowEraseTombstoneContract.RowClass.CONFLICT,
            LearnedWorkflowEraseTombstoneContract.classify(
                candidateId,
                live.copy(origin = WorkflowOrigin.USER.name),
            ),
        )
        assertEquals(
            LearnedWorkflowEraseTombstoneContract.RowClass.CONFLICT,
            LearnedWorkflowEraseTombstoneContract.classify(
                candidateId,
                live.copy(sourceCandidateId = "workflow-candidate-v1:${"2".padStart(64, '0')}"),
            ),
        )
    }

    @Test
    fun definitionTombstoneIsReplayStableAndAnyPayloadReappearanceIsAConflict() {
        val tombstone = LearnedWorkflowEraseTombstoneContract.claimEntity(candidateId, 42L).copy(
            staleReason = LearnedWorkflowEraseTombstoneContract.DEFINITION_TOMBSTONE_REASON,
            stateVersion = 7L,
        )
        assertTrue(LearnedWorkflowEraseTombstoneContract.isExactTombstone(candidateId, tombstone))
        assertEquals(
            LearnedWorkflowEraseTombstoneContract.RowClass.CONFLICT,
            LearnedWorkflowEraseTombstoneContract.classify(
                candidateId,
                tombstone.copy(description = "payload must stay erased"),
            ),
        )
    }

    private fun liveLearnedRow() = WorkflowEntity(
        id = LearnedWorkflowEraseTombstoneContract.workflowId(candidateId),
        name = "private workflow name",
        description = "private description",
        enabled = true,
        definitionJson = "{\"private\":true}",
        createdAtMs = 1L,
        updatedAtMs = 2L,
        stateVersion = 3L,
        origin = WorkflowOrigin.LEARNED.name,
        sourceCandidateId = candidateId,
        sourceArtifactHash = "a".repeat(64),
        grantDigest = "b".repeat(64),
        authoringAssistantId = "00000000-0000-4000-8000-000000000001",
        capabilitySnapshotJson = "[\"private\"]",
        toolSchemaFingerprintsJson = "[{\"private\":true}]",
    )
}
