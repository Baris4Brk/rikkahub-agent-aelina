package me.rerere.rikkahub.learning.workflow.review

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionActor
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionReason
import me.rerere.rikkahub.learning.storage.entity.toEntity
import me.rerere.rikkahub.learning.storage.entity.toRevisionEntity
import me.rerere.rikkahub.learning.workflow.WorkflowArtifactCanonicalizer
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowSlotType
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowToolSchemaFingerprint
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowTypedSlot
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowVerificationReport
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowVerificationStatus
import me.rerere.rikkahub.workflow.model.TriggerSpec
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.model.WorkflowOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowReviewContractsTest {
    @Test
    fun `projection is bounded complete and masks secret references`() {
        val candidate = candidate(LearnedWorkflowCandidateState.VERIFIED)
        val revision = candidate.toEntity().toRevisionEntity(
            previousArtifactSha256 = null,
            reason = LearnedWorkflowCandidateRevisionReason.CREATED,
            actor = LearnedWorkflowCandidateRevisionActor.COMPILER,
        )

        val detail = checkNotNull(candidate.toWorkflowReviewDetailOrNull(listOf(revision)))

        assertEquals(candidate.artifactSha256, detail.item.fence.artifactSha256)
        assertEquals(1, detail.item.evidenceCount)
        assertEquals(listOf("evidence:positive"), detail.evidenceIds)
        assertEquals("{\"params\":{},\"type\":\"manual\"}", detail.trigger)
        assertEquals(1, detail.actions.size)
        assertEquals("UNRESOLVED", detail.actions.single().origin)
        assertEquals("UNRESOLVED", detail.actions.single().risk)
        assertTrue(detail.actions.single().secretReferenceMasked)
        assertFalse(detail.actions.single().normalizedParameters.contains("super-secret-token"))
        assertTrue(detail.actions.single().normalizedParameters.contains("masked-secret-ref"))
        assertEquals("secret-ref:••••oken", detail.slots.single().displayValue)
        assertTrue(detail.canPromoteDisabled)
        assertFalse(detail.canEnable)
        assertEquals(1, detail.revisions.size)
    }

    @Test
    fun `two activation phases cannot collapse into a generic confirmation`() {
        val verified = checkNotNull(
            candidate(LearnedWorkflowCandidateState.VERIFIED)
                .toWorkflowReviewDetailOrNull(emptyList()),
        )
        val promoted = verified.copy(
            item = verified.item.copy(state = LearnedWorkflowCandidateState.PROMOTED_DISABLED),
            installedWorkflowStateVersion = 3L,
        )

        assertTrue(verified.canPromoteDisabled)
        assertFalse(verified.canEnable)
        assertFalse(promoted.canPromoteDisabled)
        assertTrue(promoted.canEnable)
    }

    private fun candidate(state: LearnedWorkflowCandidateState): LearnedWorkflowCandidate {
        val schema = "a".repeat(64)
        val definition = WorkflowDefinition(
            id = "candidate-template",
            name = "Reviewed workflow",
            enabled = false,
            trigger = TriggerSpec.Manual,
            actions = listOf(
                WorkflowAction(
                    tool = "test_tool",
                    args = JsonObject(
                        mapOf(
                            "api_token" to JsonPrimitive("super-secret-token"),
                            "secret_reference" to JsonPrimitive("secret-ref:vault/super-secret-token"),
                        ),
                    ),
                    toolSchemaFingerprint = schema,
                ),
            ),
            authoringAssistantId = ASSISTANT_ID,
            capabilitySnapshot = setOf("device.read"),
            origin = WorkflowOrigin.USER,
        )
        val template = WorkflowJson.encode(definition)
        val slots = listOf(
            LearnedWorkflowTypedSlot(
                name = "api_token",
                type = LearnedWorkflowSlotType.SECRET_REF,
                required = true,
                secretRef = "secret-ref:vault/super-secret-token",
            ),
        )
        val schemas = listOf(LearnedWorkflowToolSchemaFingerprint(0, "test_tool", schema))
        val artifact = WorkflowArtifactCanonicalizer.artifactSha256(
            canonicalTemplateJson = template,
            canonicalTypedSlots = WorkflowArtifactCanonicalizer.canonicalSlots(slots),
            canonicalCapabilities = WorkflowArtifactCanonicalizer.canonicalCapabilities(
                definition.capabilitySnapshot,
            ),
            canonicalToolSchemas = WorkflowArtifactCanonicalizer.canonicalToolSchemas(schemas),
            assistantId = ASSISTANT_ID,
            authoritySubjectId = null,
            sourcePolicyId = "policy:test",
            sourcePolicyRevision = 2L,
            sourcePolicyArtifactSha256 = "b".repeat(64),
            sourceGrantDigest = "c".repeat(64),
            compilerVersion = "compiler-v1",
            templateVersion = "template-v1",
        )
        return LearnedWorkflowCandidate(
            id = "workflow-candidate-v1:${"d".repeat(64)}",
            candidateVersion = 1L,
            stateVersion = 1L,
            state = state,
            assistantId = ASSISTANT_ID,
            authoritySubjectId = null,
            sourcePolicyId = "policy:test",
            sourcePolicyRevision = 2L,
            sourcePolicyArtifactSha256 = "b".repeat(64),
            sourceGrantDigest = "c".repeat(64),
            positiveAnchorEvidenceId = "evidence:positive",
            evidenceIds = listOf("evidence:positive"),
            canonicalTemplateJson = template,
            typedSlots = slots,
            capabilitySnapshot = listOf("device.read"),
            toolSchemaFingerprints = schemas,
            producerProviderIdentity = "provider-v1",
            producerModelIdentity = "model-v1",
            producerConfigurationIdentity = "config-v1",
            producerConfigGeneration = 1L,
            compilerVersion = "compiler-v1",
            promptVersion = "prompt-v1",
            templateVersion = "template-v1",
            validatorVersion = "validator-v1",
            verifierVersion = "verifier-v1",
            maxOutputUtf8Bytes = 1_024,
            artifactSha256 = artifact,
            verificationReport = LearnedWorkflowVerificationReport(
                verifierVersion = "verifier-v1",
                fixtureSetSha256 = "e".repeat(64),
                status = LearnedWorkflowVerificationStatus.PASSED,
                passedChecks = 4,
                failedChecks = 0,
                failureCodes = emptyList(),
                completedAtMs = 100L,
            ),
            verifiedAtMs = 100L,
            archivedAtMs = null,
            createdAtMs = 100L,
            updatedAtMs = 100L,
        )
    }

    companion object {
        private const val ASSISTANT_ID = "11111111-1111-4111-8111-111111111111"
    }
}
