package me.rerere.rikkahub.learning.workflow.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import me.rerere.rikkahub.learning.grant.PolicyGrantReason
import me.rerere.rikkahub.learning.grant.policyGrantId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearnedPolicyProposal
import me.rerere.rikkahub.learning.policy.LearnedPolicyWorkflowEvidenceAnchor
import me.rerere.rikkahub.learning.policy.LearnedPolicyWorkflowEvidencePolarity
import me.rerere.rikkahub.learning.policy.LearnedWorkflowActionProposal
import me.rerere.rikkahub.learning.verification.WORKFLOW_CANDIDATE_VERIFIER_VERSION
import me.rerere.rikkahub.learning.workflow.LearnedWorkflowCompileResult
import me.rerere.rikkahub.learning.workflow.LearnedWorkflowCompiler
import me.rerere.rikkahub.learning.workflow.LearnedWorkflowAuthorityResolver
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowVerificationStatus
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LearnedWorkflowSubmissionOrchestratorTest {
    @Test
    fun `explicit safe host profile reaches durable VERIFIED without executing Tool`() = runBlocking {
        val proposal = proposal()
        val catalog = timeCatalog()
        val authority = FakeAuthority(proposal.exactGrant, catalog)
        val store = FakeStore()

        val result = LearnedWorkflowSubmissionOrchestrator(
            authority = authority,
            candidates = store,
            rolloutFence = { true },
        ).submit(
            request(proposal),
            nowMs = 20L,
        )

        assertTrue(result is LearnedWorkflowSubmissionResult.Verified)
        result as LearnedWorkflowSubmissionResult.Verified
        assertFalse(result.replayed)
        assertEquals(2, authority.revalidations)
        assertEquals(
            listOf(
                WorkflowCandidateTransition.VALIDATION_STARTED,
                WorkflowCandidateTransition.VALIDATION_PASSED,
            ),
            store.transitions,
        )
        assertEquals(LearnedWorkflowCandidateState.VERIFIED, store.row?.state)
        assertEquals(
            LearnedWorkflowVerificationStatus.PASSED,
            store.row?.verificationReport?.status,
        )
        assertNotNull(store.row?.verifiedAtMs)
    }

    @Test
    fun `VALIDATING crash replay resumes and converges on same artifact`() = runBlocking {
        val proposal = proposal()
        val catalog = timeCatalog()
        val compiled = compiled(proposal, catalog)
        val store = FakeStore(
            compiled.copy(
                state = LearnedWorkflowCandidateState.VALIDATING,
                stateVersion = 2L,
                updatedAtMs = 11L,
            ),
        )

        val result = LearnedWorkflowSubmissionOrchestrator(
            authority = FakeAuthority(proposal.exactGrant, catalog),
            candidates = store,
            rolloutFence = { true },
        ).submit(request(proposal), nowMs = 21L)

        assertTrue(result is LearnedWorkflowSubmissionResult.Verified)
        assertTrue((result as LearnedWorkflowSubmissionResult.Verified).replayed)
        assertEquals(listOf(WorkflowCandidateTransition.VALIDATION_PASSED), store.transitions)
        assertEquals(3L, store.row?.stateVersion)
    }

    @Test
    fun `fake verifier FAILED and ABSTAIN both persist report then reject`() = runBlocking {
        val proposal = proposal()
        val catalog = timeCatalog()

        suspend fun submitWith(
            provider: HostWorkflowFixtureProvider,
        ): FakeStore {
            val store = FakeStore()
            val result = LearnedWorkflowSubmissionOrchestrator(
                authority = FakeAuthority(proposal.exactGrant, catalog),
                candidates = store,
                fixtureProvider = provider,
                rolloutFence = { true },
            ).submit(request(proposal), nowMs = 30L)
            assertTrue(result is LearnedWorkflowSubmissionResult.Rejected)
            assertEquals(LearnedWorkflowCandidateState.REJECTED, store.row?.state)
            assertNotNull(store.row?.verificationReport)
            return store
        }

        val failedStore = submitWith(HostWorkflowFixtureProvider { profile, candidate, tools ->
            val base = requireNotNull(
                ProductionHostWorkflowFixtureProvider.resolve(profile, candidate, tools),
            )
            base.copy(fixtures = base.fixtures.map { it.copy(subjectArtifactSha256 = "f".repeat(64)) })
        })
        assertEquals(
            LearnedWorkflowVerificationStatus.FAILED,
            failedStore.row?.verificationReport?.status,
        )

        val abstainedStore = submitWith(HostWorkflowFixtureProvider { profile, candidate, tools ->
            requireNotNull(
                ProductionHostWorkflowFixtureProvider.resolve(profile, candidate, tools),
            ).copy(fixtures = emptyList())
        })
        assertEquals(
            LearnedWorkflowVerificationStatus.ABSTAIN,
            abstainedStore.row?.verificationReport?.status,
        )
    }

    @Test
    fun `authority mismatch and missing explicit submit fail before candidate insert`() = runBlocking {
        val proposal = proposal()
        val catalog = timeCatalog()
        val store = FakeStore()
        val authority = FakeAuthority(
            proposal.exactGrant.copy(stateVersion = 2L, updatedAtEpochMs = 2L),
            catalog,
        )
        val service = LearnedWorkflowSubmissionOrchestrator(
            authority = authority,
            candidates = store,
            rolloutFence = { true },
        )

        val mismatch = service.submit(request(proposal), 20L)
        assertEquals(
            LearnedWorkflowSubmissionFailure.AUTHORITY_MISMATCH,
            (mismatch as LearnedWorkflowSubmissionResult.Rejected).failure,
        )
        val notExplicit = service.submit(
            request(proposal).copy(explicitUserSubmission = false),
            20L,
        )
        assertEquals(
            LearnedWorkflowSubmissionFailure.EXPLICIT_USER_SUBMISSION_REQUIRED,
            (notExplicit as LearnedWorkflowSubmissionResult.Rejected).failure,
        )
        assertEquals(null, store.row)
    }

    @Test
    fun `fixed host profile rejects a different otherwise catalogued safe tool`() = runBlocking {
        val proposal = proposal(
            actions = listOf(
                LearnedWorkflowActionProposal(
                    toolName = "show_toast",
                    args = buildJsonObject { put("text", "reviewed text") },
                    timeoutSeconds = 30,
                ),
            ),
        )
        val catalog = ToolCatalogSnapshot.fromDefinitions(
            listOf(timeTool(), toastTool()),
        )
        val store = FakeStore()

        val result = LearnedWorkflowSubmissionOrchestrator(
            authority = FakeAuthority(proposal.exactGrant, catalog),
            candidates = store,
            rolloutFence = { true },
        ).submit(request(proposal), 20L)

        assertEquals(
            LearnedWorkflowSubmissionFailure.PROFILE_NOT_APPLICABLE,
            (result as LearnedWorkflowSubmissionResult.Rejected).failure,
        )
        assertEquals(null, store.row)
    }

    @Test
    fun `sensitive or schema-invalid args are rejected before the first durable insert`() = runBlocking {
        val unsafeProposal = proposal(
            actions = listOf(
                LearnedWorkflowActionProposal(
                    toolName = "get_time_info",
                    args = buildJsonObject {
                        put("authorization", "Bearer private-fixture-token-123456789")
                    },
                    timeoutSeconds = 30,
                ),
            ),
        )
        val catalog = timeCatalog()
        val safeCandidate = compiled(proposal(), catalog)
        val safeBundle = requireNotNull(
            ProductionHostWorkflowFixtureProvider.resolve(
                HostWorkflowFixtureProfile.SAFE_TIME_INFO_V1,
                safeCandidate,
                catalog,
            ),
        )
        val store = FakeStore()

        val result = LearnedWorkflowSubmissionOrchestrator(
            authority = FakeAuthority(unsafeProposal.exactGrant, catalog),
            candidates = store,
            fixtureProvider = HostWorkflowFixtureProvider { profile, candidate, _ ->
                safeBundle.takeIf { it.profile == profile }?.copy(
                    fixtures = safeBundle.fixtures.map { fixture ->
                        fixture.copy(subjectArtifactSha256 = candidate.artifactSha256)
                    },
                )
            },
            rolloutFence = { true },
        ).submit(request(unsafeProposal), 20L)

        result as LearnedWorkflowSubmissionResult.Rejected
        assertEquals(LearnedWorkflowSubmissionFailure.VALIDATION_REJECTED, result.failure)
        assertEquals("SECRET_LITERAL", result.detailCode)
        assertEquals(null, store.row)
        assertTrue(store.transitions.isEmpty())
    }

    private fun request(proposal: LearnedPolicyProposal) = LearnedWorkflowSubmissionRequest(
        proposal = proposal,
        fixtureProfile = HostWorkflowFixtureProfile.SAFE_TIME_INFO_V1,
        explicitUserSubmission = true,
    )

    private fun proposal(
        actions: List<LearnedWorkflowActionProposal> = listOf(
            LearnedWorkflowActionProposal(
                toolName = "get_time_info",
                args = JsonObject(emptyMap()),
                timeoutSeconds = 30,
            ),
        ),
    ) = LearnedPolicyProposal(
        policyId = POLICY_ID,
        policyRevision = 2L,
        policyArtifactSha256 = "a".repeat(64),
        exactGrant = grant(),
        consumingAssistantId = ASSISTANT,
        trigger = "user explicitly requests the reviewed local lookup",
        procedure = "run the single bounded read-only lookup",
        verification = "host fake replays the exact schema-bound call",
        boundary = "manual only and never scheduled",
        evidence = listOf(
            LearnedPolicyWorkflowEvidenceAnchor(
                evidenceId = "evidence-v1:positive",
                polarity = LearnedPolicyWorkflowEvidencePolarity.POSITIVE,
                sourceRevision = 1L,
                sourceIntegritySha256 = "b".repeat(64),
            ),
        ),
        actions = actions,
        typedSlots = emptyList(),
        name = "Reviewed local time",
        description = "Disabled manual candidate",
        producerProviderIdentity = "provider-v1",
        producerModelIdentity = "model-v1",
        producerConfigurationIdentity = "configuration-v1",
        producerConfigGeneration = 1L,
        compilerVersion = "workflow-compiler-v1",
        promptVersion = "workflow-prompt-v1",
        templateVersion = "workflow-template-v1",
        validatorVersion = "workflow-validator-v1",
        verifierVersion = WORKFLOW_CANDIDATE_VERIFIER_VERSION,
        maxOutputUtf8Bytes = 1_024,
        frozenNowMs = 10L,
    )

    private fun compiled(
        proposal: LearnedPolicyProposal,
        catalog: ToolCatalogSnapshot,
    ): LearnedWorkflowCandidate =
        (LearnedWorkflowCompiler.compile(proposal, catalog) as LearnedWorkflowCompileResult.Compiled)
            .candidate

    private fun timeCatalog() = ToolCatalogSnapshot.fromDefinitions(listOf(timeTool()))

    private fun timeTool() = Tool(
        name = "get_time_info",
        description = "bounded local time fixture",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = { error("production Tool.execute must never be invoked by fake verification") },
    )

    private fun toastTool() = Tool(
        name = "show_toast",
        description = "bounded toast fixture",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("text", buildJsonObject {
                        put("type", "string")
                        put("maxLength", 2_048)
                    })
                },
                required = listOf("text"),
            )
        },
        execute = { error("production Tool.execute must never be invoked by fake verification") },
    )

    private fun grant(): PolicyGrantAuthoritySnapshot {
        val assistant = Uuid.parse(ASSISTANT)
        val scope = LearningScope.Assistant(assistant)
        return PolicyGrantAuthoritySnapshot(
            grantId = policyGrantId(STREAM, scope, assistant, POLICY_ID),
            sourceStreamId = STREAM,
            scope = scope,
            consumingAssistantId = assistant,
            policyId = POLICY_ID,
            contentRevision = 2L,
            artifactSha256 = "a".repeat(64),
            state = PolicyGrantAuthorityState.GRANTED,
            stateVersion = 1L,
            grantedAtEpochMs = 1L,
            revokedAtEpochMs = null,
            reason = PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE,
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 1L,
        )
    }

    private class FakeAuthority(
        private val snapshot: PolicyGrantAuthoritySnapshot,
        private val catalog: ToolCatalogSnapshot,
        private val exactAtRevalidation: (Int) -> Boolean = { true },
    ) : WorkflowSubmissionAuthorityPort {
        var revalidations: Int = 0

        override suspend fun loadCurrent(
            proposal: LearnedPolicyProposal,
        ) = WorkflowSubmissionAuthorityContext(
            exactGrant = snapshot,
            catalog = catalog,
            authorityResolver = LearnedWorkflowAuthorityResolver { assistant, subject ->
                assistant == proposal.consumingAssistantId &&
                    subject == (proposal.exactGrant.scope as? LearningScope.AuthoritySubject)
                    ?.authoritySubjectId
            },
        )

        override suspend fun revalidateExact(snapshot: PolicyGrantAuthoritySnapshot): Boolean {
            revalidations += 1
            return this.snapshot == snapshot && exactAtRevalidation(revalidations)
        }
    }

    private class FakeStore(initial: LearnedWorkflowCandidate? = null) : WorkflowCandidateRuntimeStore {
        var row: LearnedWorkflowCandidate? = initial
        val transitions = mutableListOf<WorkflowCandidateTransition>()

        override suspend fun insertCompiledExact(
            candidate: LearnedWorkflowCandidate,
        ): WorkflowCandidateInsertResult {
            val current = row
            if (current != null) return WorkflowCandidateInsertResult.Ready(current, false)
            row = candidate
            return WorkflowCandidateInsertResult.Ready(candidate, true)
        }

        override suspend fun readExact(candidateId: String): WorkflowCandidateReadResult =
            row?.takeIf { it.id == candidateId }
                ?.let(WorkflowCandidateReadResult::Ready)
                ?: WorkflowCandidateReadResult.Missing

        override suspend fun transitionExact(
            expected: LearnedWorkflowCandidate,
            next: LearnedWorkflowCandidate,
            transition: WorkflowCandidateTransition,
        ): WorkflowCandidateTransitionResult {
            if (row != expected) return WorkflowCandidateTransitionResult.Conflict
            row = next
            transitions += transition
            return WorkflowCandidateTransitionResult.Applied(next)
        }
    }

    private companion object {
        const val ASSISTANT = "00000000-0000-0000-0000-000000000201"
        const val STREAM = "00000000-0000-0000-0000-000000000202"
        const val POLICY_ID = "policy-v1:workflow-submission"
    }
}
