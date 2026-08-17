package me.rerere.rikkahub.learning.workflow.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySource
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityScanCursor
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityScanResult
import me.rerere.rikkahub.learning.grant.PolicyGrantReason
import me.rerere.rikkahub.learning.grant.policyGrantId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearnedPolicyProposal
import me.rerere.rikkahub.learning.policy.LearnedPolicyWorkflowEvidenceAnchor
import me.rerere.rikkahub.learning.policy.LearnedPolicyWorkflowEvidencePolarity
import me.rerere.rikkahub.learning.review.PolicyReviewFence
import me.rerere.rikkahub.learning.workflow.LearnedWorkflowAuthorityResolver
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ReviewedPolicyWorkflowSubmissionTest {
    @Test
    fun `production proposal is one fixed schema-bound time action`() = runBlocking {
        val source = source()
        val grant = grant()
        val runtime = FakeSourceRuntime(source)
        val authority = FakeWorkflowAuthority(grant, catalog())
        val port = ProductionReviewedPolicyWorkflowProposalPort(
            runtime = runtime,
            grants = FakeGrantSource(grant),
            workflowAuthority = authority,
        )

        val result = port.prepareExact(request())

        val proposal = (result as ReviewedPolicyWorkflowProposalResult.Ready).proposal
        assertEquals(POLICY_ID, proposal.policyId)
        assertSame(grant, proposal.exactGrant)
        assertEquals(1, proposal.actions.size)
        assertEquals("get_time_info", proposal.actions.single().toolName)
        assertEquals(JsonObject(emptyMap()), proposal.actions.single().args)
        assertEquals(30, proposal.actions.single().timeoutSeconds)
        assertTrue(proposal.typedSlots.isEmpty())
        assertEquals(source.trigger, proposal.trigger)
        assertEquals(source.evidence, proposal.evidence)
        assertEquals(2, runtime.readCount)
        assertEquals(2, authority.revalidationCount)
    }

    @Test
    fun `proposal fails closed when policy singleton schema is not current time schema`() =
        runBlocking {
            val grant = grant()
            val runtime = FakeSourceRuntime(source(applicableSchema = "f".repeat(64)))
            val authority = FakeWorkflowAuthority(grant, catalog())
            val port = ProductionReviewedPolicyWorkflowProposalPort(
                runtime,
                FakeGrantSource(grant),
                authority,
            )

            val result = port.prepareExact(request())

            assertEquals(
                ReviewedPolicyWorkflowProposalRejection.CURRENT_TOOL_SCHEMA_MISMATCH,
                (result as ReviewedPolicyWorkflowProposalResult.Rejected).reason,
            )
            assertEquals(0, authority.revalidationCount)
        }

    @Test
    fun `proposal rejects a non exact current grant before host authority`() = runBlocking {
        val staleGrant = grant().copy(stateVersion = 8L, updatedAtEpochMs = 8L)
        val authority = FakeWorkflowAuthority(staleGrant, catalog())
        val port = ProductionReviewedPolicyWorkflowProposalPort(
            runtime = FakeSourceRuntime(source()),
            grants = FakeGrantSource(staleGrant),
            workflowAuthority = authority,
        )

        val result = port.prepareExact(request())

        assertEquals(
            ReviewedPolicyWorkflowProposalRejection.GRANT_NOT_EXACT,
            (result as ReviewedPolicyWorkflowProposalResult.Rejected).reason,
        )
        assertEquals(0, authority.loadCount)
    }

    @Test
    fun `user-only coordinator rejects non-user caller without reading proposal`() = runBlocking {
        var proposalCalls = 0
        var submissionCalls = 0
        val coordinator = UserReviewedPolicyWorkflowSubmissionCoordinator(
            proposals = ReviewedPolicyWorkflowProposalPort {
                proposalCalls += 1
                ReviewedPolicyWorkflowProposalResult.Ready(proposal())
            },
            submissions = LearnedWorkflowSubmissionService { _, _ ->
                submissionCalls += 1
                LearnedWorkflowSubmissionResult.Unavailable(
                    LearnedWorkflowSubmissionFailure.UNKNOWN,
                )
            },
        )

        val result = coordinator.submitFromUser(
            UserReviewedPolicyWorkflowSubmissionCommand(
                proposalRequest = request(),
                explicitUserSubmission = false,
            ),
        )

        assertEquals(
            UserReviewedPolicyWorkflowSubmissionResult.ExplicitUserSubmissionRequired,
            result,
        )
        assertEquals(0, proposalCalls)
        assertEquals(0, submissionCalls)
    }

    @Test
    fun `user-only coordinator submits exact prepared proposal with closed profile once`() =
        runBlocking {
            val prepared = proposal()
            var captured: LearnedWorkflowSubmissionRequest? = null
            var capturedNow = -1L
            val terminal = LearnedWorkflowSubmissionResult.Verified(
                candidateId = "workflow-candidate-v1:${"c".repeat(64)}",
                candidateVersion = 1L,
                stateVersion = 3L,
                replayed = false,
            )
            val coordinator = UserReviewedPolicyWorkflowSubmissionCoordinator(
                proposals = ReviewedPolicyWorkflowProposalPort {
                    ReviewedPolicyWorkflowProposalResult.Ready(prepared)
                },
                submissions = LearnedWorkflowSubmissionService { submission, nowMs ->
                    captured = submission
                    capturedNow = nowMs
                    terminal
                },
            )

            val result = coordinator.submitFromUser(
                UserReviewedPolicyWorkflowSubmissionCommand(
                    proposalRequest = request(),
                    explicitUserSubmission = true,
                ),
            )

            assertEquals(
                UserReviewedPolicyWorkflowSubmissionResult.Submitted(terminal),
                result,
            )
            assertSame(prepared, captured?.proposal)
            assertEquals(HostWorkflowFixtureProfile.SAFE_TIME_INFO_V1, captured?.fixtureProfile)
            assertTrue(captured?.explicitUserSubmission == true)
            assertEquals(NOW_MS, capturedNow)
        }

    @Test
    fun `user-only coordinator does not submit rejected proposal`() = runBlocking {
        var submissionCalled = false
        val coordinator = UserReviewedPolicyWorkflowSubmissionCoordinator(
            proposals = ReviewedPolicyWorkflowProposalPort {
                ReviewedPolicyWorkflowProposalResult.Rejected(
                    ReviewedPolicyWorkflowProposalRejection.POLICY_EVIDENCE_INVALID,
                )
            },
            submissions = LearnedWorkflowSubmissionService { _, _ ->
                submissionCalled = true
                error("must not submit")
            },
        )

        val result = coordinator.submitFromUser(
            UserReviewedPolicyWorkflowSubmissionCommand(request(), true),
        )

        assertEquals(
            UserReviewedPolicyWorkflowSubmissionResult.ProposalRejected(
                ReviewedPolicyWorkflowProposalRejection.POLICY_EVIDENCE_INVALID,
            ),
            result,
        )
        assertFalse(submissionCalled)
    }

    private fun request() = ReviewedPolicyWorkflowProposalRequest(
        fence = fence(),
        consumingAssistantId = Uuid.parse(ASSISTANT_ID),
        expectedGrantStateVersion = GRANT_STATE_VERSION,
        frozenNowMs = NOW_MS,
    )

    private fun fence() = PolicyReviewFence(
        policyId = POLICY_ID,
        scope = scope(),
        stateVersion = POLICY_STATE_VERSION,
        contentRevision = POLICY_CONTENT_REVISION,
        artifactSha256 = POLICY_ARTIFACT,
        sourceStreamId = STREAM_ID,
    )

    private fun source(
        applicableSchema: String = catalog().entry("get_time_info")!!.schemaFingerprint,
    ) = ExactReviewedPolicyWorkflowSource(
        policyId = POLICY_ID,
        policyStateVersion = POLICY_STATE_VERSION,
        policyRevision = POLICY_CONTENT_REVISION,
        policyArtifactSha256 = POLICY_ARTIFACT,
        scope = scope(),
        policyType = "PROCEDURE",
        trigger = "user explicitly requests current local time",
        procedure = "perform the bounded local time lookup once",
        verification = "verify the structured local date time and timezone fields",
        boundary = "manual invocation only and never scheduled",
        evidence = listOf(
            LearnedPolicyWorkflowEvidenceAnchor(
                evidenceId = "episode-v1:positive",
                polarity = LearnedPolicyWorkflowEvidencePolarity.POSITIVE,
                sourceRevision = 3L,
                sourceIntegritySha256 = "b".repeat(64),
            ),
        ),
        applicableToolSchemaSha256 = applicableSchema,
        producerProviderIdentity = "d".repeat(64),
        producerModelIdentity = "e".repeat(64),
        producerConfigurationIdentity = "a".repeat(64),
        producerConfigGeneration = 4L,
        producerPromptIdentity = "policy-prompt-v1",
    )

    private fun proposal(): LearnedPolicyProposal = checkNotNull(
        source().toSafeTimeInfoProposalOrNull(
            exactGrant = grant(),
            consumingAssistantId = Uuid.parse(ASSISTANT_ID),
            frozenNowMs = NOW_MS,
        ),
    )

    private fun scope() = LearningScope.Assistant(Uuid.parse(ASSISTANT_ID))

    private fun grant() = PolicyGrantAuthoritySnapshot(
        grantId = policyGrantId(
            STREAM_ID,
            scope(),
            Uuid.parse(ASSISTANT_ID),
            POLICY_ID,
        ),
        sourceStreamId = STREAM_ID,
        scope = scope(),
        consumingAssistantId = Uuid.parse(ASSISTANT_ID),
        policyId = POLICY_ID,
        contentRevision = POLICY_CONTENT_REVISION,
        artifactSha256 = POLICY_ARTIFACT,
        state = PolicyGrantAuthorityState.GRANTED,
        stateVersion = GRANT_STATE_VERSION,
        grantedAtEpochMs = 1L,
        revokedAtEpochMs = null,
        reason = PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE,
        createdAtEpochMs = 1L,
        updatedAtEpochMs = 1L,
    )

    private fun catalog(): ToolCatalogSnapshot = ToolCatalogSnapshot.fromDefinitions(
        listOf(
            Tool(
                name = "get_time_info",
                description = "bounded local time fixture",
                parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
                execute = { error("proposal preparation must not execute a production Tool") },
            ),
        ),
    )

    private class FakeSourceRuntime(
        private val source: ExactReviewedPolicyWorkflowSource,
    ) : ReviewedPolicyWorkflowSourceRuntimePort {
        var readCount = 0

        override suspend fun readExactReviewedPolicyWorkflowSource(
            request: ReviewedPolicyWorkflowProposalRequest,
        ): ReviewedPolicyWorkflowSourceResult {
            readCount += 1
            return ReviewedPolicyWorkflowSourceResult.Ready(source)
        }
    }

    private class FakeGrantSource(
        private val grant: PolicyGrantAuthoritySnapshot,
    ) : PolicyGrantAuthoritySource {
        override suspend fun listExactGranted(
            scope: LearningScope,
            consumingAssistantId: Uuid,
            sourceStreamId: String,
            limit: Int,
        ): List<PolicyGrantAuthoritySnapshot> = listOf(grant)

        override suspend fun revalidateExact(snapshot: PolicyGrantAuthoritySnapshot): Boolean =
            snapshot == grant

        override suspend fun listCurrentPage(
            after: PolicyGrantAuthorityScanCursor?,
            limit: Int,
        ): PolicyGrantAuthorityScanResult = PolicyGrantAuthorityScanResult.Unavailable
    }

    private class FakeWorkflowAuthority(
        private val grant: PolicyGrantAuthoritySnapshot,
        private val catalog: ToolCatalogSnapshot,
    ) : WorkflowSubmissionAuthorityPort {
        var loadCount = 0
        var revalidationCount = 0

        override suspend fun loadCurrent(
            proposal: LearnedPolicyProposal,
        ): WorkflowSubmissionAuthorityContext {
            loadCount += 1
            return WorkflowSubmissionAuthorityContext(
                exactGrant = grant,
                catalog = catalog,
                authorityResolver = LearnedWorkflowAuthorityResolver { _, _ -> true },
            )
        }

        override suspend fun revalidateExact(snapshot: PolicyGrantAuthoritySnapshot): Boolean {
            revalidationCount += 1
            return snapshot == grant
        }
    }

    private companion object {
        const val ASSISTANT_ID = "00000000-0000-0000-0000-000000000101"
        const val STREAM_ID = "00000000-0000-0000-0000-000000000102"
        const val POLICY_ID = "policy-v1:reviewed-time"
        const val POLICY_STATE_VERSION = 7L
        const val POLICY_CONTENT_REVISION = 2L
        const val GRANT_STATE_VERSION = 5L
        const val POLICY_ARTIFACT =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val NOW_MS = 1_000L
    }
}
