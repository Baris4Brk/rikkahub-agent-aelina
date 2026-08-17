package me.rerere.rikkahub.learning.workflow.runtime

import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.review.PolicyReviewFence
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.storage.PolicyApplicabilityWire
import me.rerere.rikkahub.learning.storage.StoredLearningPolicyStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ReviewedPolicyWorkflowSourceProjectionTest {
    @Test
    fun `exact active policy projects only content-free evidence anchors`() {
        val result = projectExactReviewedPolicyWorkflowSource(
            request = request(),
            currentStreamId = STREAM_ID,
            policy = policy(),
            evidence = listOf(evidence("episode-v1:positive", "POSITIVE")),
        )

        val source = (result as ReviewedPolicyWorkflowSourceResult.Ready).source
        assertEquals(POLICY_ID, source.policyId)
        assertEquals(SCHEMA, source.applicableToolSchemaSha256)
        assertEquals(listOf("episode-v1:positive"), source.evidence.map { it.evidenceId })
        assertEquals("b".repeat(64), source.evidence.single().sourceIntegritySha256)
    }

    @Test
    fun `non-active current row is rejected before proposal construction`() {
        val result = projectExactReviewedPolicyWorkflowSource(
            request(),
            STREAM_ID,
            policy().copy(
                status = StoredLearningPolicyStatus.SUSPENDED.name,
                staleReason = "USER_SUSPENDED",
            ),
            listOf(evidence("episode-v1:positive", "POSITIVE")),
        )

        assertEquals(
            ReviewedPolicyWorkflowProposalRejection.POLICY_NOT_ACTIVE,
            (result as ReviewedPolicyWorkflowSourceResult.Rejected).reason,
        )
    }

    @Test
    fun `neutral or invalid evidence is never converted into workflow authority`() {
        val result = projectExactReviewedPolicyWorkflowSource(
            request(),
            STREAM_ID,
            policy(),
            listOf(evidence("episode-v1:neutral", "NEUTRAL")),
        )

        assertEquals(
            ReviewedPolicyWorkflowProposalRejection.POLICY_EVIDENCE_INVALID,
            (result as ReviewedPolicyWorkflowSourceResult.Rejected).reason,
        )
    }

    @Test
    fun `zero or multiple applicable schemas fail closed`() {
        listOf(
            emptySet(),
            setOf(SCHEMA, "c".repeat(64)),
        ).forEach { schemas ->
            val result = projectExactReviewedPolicyWorkflowSource(
                request(),
                STREAM_ID,
                policy().copy(
                    applicableToolSchemasWire = PolicyApplicabilityWire.encodeToolSchemas(schemas),
                ),
                listOf(evidence("episode-v1:positive", "POSITIVE")),
            )
            assertTrue(result is ReviewedPolicyWorkflowSourceResult.Rejected)
            assertEquals(
                ReviewedPolicyWorkflowProposalRejection.POLICY_PROFILE_NOT_APPLICABLE,
                (result as ReviewedPolicyWorkflowSourceResult.Rejected).reason,
            )
        }
    }

    private fun request() = ReviewedPolicyWorkflowProposalRequest(
        fence = PolicyReviewFence(
            policyId = POLICY_ID,
            scope = scope(),
            stateVersion = POLICY_STATE_VERSION,
            contentRevision = POLICY_CONTENT_REVISION,
            artifactSha256 = POLICY_ARTIFACT,
            sourceStreamId = STREAM_ID,
        ),
        consumingAssistantId = Uuid.parse(ASSISTANT_ID),
        expectedGrantStateVersion = 3L,
        frozenNowMs = 20L,
    )

    private fun evidence(
        id: String,
        polarity: String,
    ) = ReviewedPolicyWorkflowEvidenceRecord(
        evidenceId = id,
        polarity = polarity,
        sourceRevision = 2L,
        sourceIntegritySha256 = "b".repeat(64),
        sourceValid = true,
    )

    private fun policy() = LearningPolicyEntity(
        id = POLICY_ID,
        scopeKind = scope().kind.name,
        scopeId = scope().storageId,
        taskSignature = "task-signature-v1:${"1".repeat(64)}",
        policyType = "PROCEDURE",
        triggerSummary = "explicit current-time request",
        procedureSummary = "perform one bounded local lookup",
        verificationSummary = "verify structured date time and timezone fields",
        boundarySummary = "manual invocation only",
        failureModeSummary = "return no candidate on authority or schema drift",
        stateVersion = POLICY_STATE_VERSION,
        contentRevision = POLICY_CONTENT_REVISION,
        artifactSha256 = POLICY_ARTIFACT,
        compilerAbi = "policy-shadow-compiler-v1",
        status = StoredLearningPolicyStatus.ACTIVE.name,
        sourceValid = true,
        schemaValid = true,
        applicableToolSchemasWire = PolicyApplicabilityWire.encodeToolSchemas(setOf(SCHEMA)),
        applicableModelIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("b".repeat(64)),
        applicableProviderIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("c".repeat(64)),
        applicableTemplateIdentity = "e".repeat(64),
        applicableConfigurationIdentity = "d".repeat(64),
        applicableConfigurationGeneration = 1L,
        applicableCapabilityDigest = null,
        applicableAuthorityDigest = null,
        staleReason = null,
        distinctEpisodeSupport = 1L,
        positiveEpisodeCount = 1L,
        negativeEpisodeCount = 0L,
        usageCount = 0L,
        confidence = 0.8,
        observedUtilityDelta = null,
        utilityUncertainty = null,
        producerModelIdentity = "d".repeat(64),
        producerProviderIdentity = "e".repeat(64),
        producerProviderKind = "remote",
        producerConfigurationIdentity = "f".repeat(64),
        producerConfigGeneration = 4L,
        producerPromptIdentity = "policy-prompt-v1",
        producerTemplateIdentity = "policy-template-v1",
        producerSchemaIdentity = "policy-candidate-schema-v1",
        createdAtMs = 1L,
        updatedAtMs = 10L,
        lastUsedAtMs = null,
    )

    private fun scope() = LearningScope.Assistant(Uuid.parse(ASSISTANT_ID))

    private companion object {
        const val ASSISTANT_ID = "00000000-0000-0000-0000-000000000101"
        const val STREAM_ID = "00000000-0000-0000-0000-000000000102"
        const val POLICY_ID = "policy-v1:reviewed-time"
        const val POLICY_STATE_VERSION = 5L
        const val POLICY_CONTENT_REVISION = 2L
        const val POLICY_ARTIFACT =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SCHEMA =
            "9999999999999999999999999999999999999999999999999999999999999999"
    }
}
