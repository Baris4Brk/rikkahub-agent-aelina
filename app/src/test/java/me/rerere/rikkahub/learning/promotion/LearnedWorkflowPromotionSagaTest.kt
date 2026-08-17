package me.rerere.rikkahub.learning.promotion

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import me.rerere.rikkahub.learning.grant.PolicyGrantReason
import me.rerere.rikkahub.learning.grant.policyGrantId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.workflow.WorkflowArtifactCanonicalizer
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowToolSchemaFingerprint
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowVerificationReport
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowVerificationStatus
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.execution.LearnedWorkflowAuthoritySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LearnedWorkflowPromotionSagaTest {
    @Test
    fun `source authority uncertainty is fail closed and cancellation remains structured`() =
        runBlocking {
            val candidate = candidate(LearnedWorkflowCandidateState.PROMOTED_DISABLED)
            assertTrue(
                LearnedWorkflowSourceAuthorityPort { true }
                    .isCurrentFailClosed(candidate),
            )
            assertFalse(
                LearnedWorkflowSourceAuthorityPort { false }
                    .isCurrentFailClosed(candidate),
            )
            assertFalse(
                LearnedWorkflowSourceAuthorityPort { error("storage unavailable") }
                    .isCurrentFailClosed(candidate),
            )
            var cancellationObserved = false
            try {
                LearnedWorkflowSourceAuthorityPort {
                    throw kotlinx.coroutines.CancellationException("cancelled")
                }.isCurrentFailClosed(candidate)
            } catch (_: kotlinx.coroutines.CancellationException) {
                cancellationObserved = true
            }
            assertTrue(cancellationObserved)
        }

    @Test
    fun `normal promotion inserts disabled then records promoted state`() = runBlocking {
        val candidate = candidate(LearnedWorkflowCandidateState.VERIFIED)
        val store = CandidateMemory(candidate)
        val workflows = WorkflowMemory()
        val saga = saga(store, workflows)

        val result = saga.promoteVerifiedDisabled(fence(candidate), grant(candidate), 20L)

        assertEquals(
            WorkflowPromotionResult.PromotedDisabled("learned:${candidate.id}", false),
            result,
        )
        assertEquals(LearnedWorkflowCandidateState.PROMOTED_DISABLED, store.current.state)
        assertFalse(workflows.definition!!.enabled)
        assertEquals(listOf("PROMOTING", "PROMOTED_DISABLED"), store.transitions)
    }

    @Test
    fun `crash after promoting resumes insert and terminal candidate CAS`() = runBlocking {
        val candidate = candidate(LearnedWorkflowCandidateState.PROMOTING)
        val store = CandidateMemory(candidate)
        val workflows = WorkflowMemory()

        val result = saga(store, workflows).promoteVerifiedDisabled(
            fence(candidate), grant(candidate), 30L,
        )

        assertTrue((result as WorkflowPromotionResult.PromotedDisabled).replayed)
        assertEquals(LearnedWorkflowCandidateState.PROMOTED_DISABLED, store.current.state)
        assertEquals(listOf("PROMOTED_DISABLED"), store.transitions)
    }

    @Test
    fun `crash after workflow insert recognizes exact duplicate`() = runBlocking {
        val candidate = candidate(LearnedWorkflowCandidateState.PROMOTING)
        val store = CandidateMemory(candidate)
        val workflows = WorkflowMemory().apply {
            mode = PromotionWorkflowWrite.ALREADY_EXACT
        }

        val result = saga(store, workflows).promoteVerifiedDisabled(
            fence(candidate), grant(candidate), 31L,
        )

        assertTrue((result as WorkflowPromotionResult.PromotedDisabled).replayed)
        assertEquals(LearnedWorkflowCandidateState.PROMOTED_DISABLED, store.current.state)
    }

    @Test
    fun `crash after promoted state remains idempotently disabled`() = runBlocking {
        val candidate = candidate(LearnedWorkflowCandidateState.PROMOTED_DISABLED)
        val store = CandidateMemory(candidate)
        val workflows = WorkflowMemory().apply { mode = PromotionWorkflowWrite.ALREADY_EXACT }

        val result = saga(store, workflows).promoteVerifiedDisabled(
            fence(candidate), grant(candidate), 32L,
        )

        assertTrue((result as WorkflowPromotionResult.PromotedDisabled).replayed)
        assertTrue(store.transitions.isEmpty())
    }

    @Test
    fun `enable requires separate confirmation and fresh exact validation`() = runBlocking {
        val candidate = candidate(LearnedWorkflowCandidateState.PROMOTED_DISABLED)
        val store = CandidateMemory(candidate)
        val workflows = WorkflowMemory()
        val saga = saga(store, workflows)

        assertTrue(saga.enableAfterExplicitConfirmation(
            fence(candidate), grant(candidate), 1L, false, 40L,
        ) is WorkflowPromotionResult.Rejected)
        assertFalse(workflows.enabled)

        assertEquals(
            WorkflowPromotionResult.Enabled("learned:${candidate.id}"),
            saga.enableAfterExplicitConfirmation(
                fence(candidate), grant(candidate), 1L, true, 40L,
            ),
        )
        assertTrue(workflows.enabled)
    }

    @Test
    fun `changed hash schema authority or verifier never reaches AppDatabase`() = runBlocking {
        val candidate = candidate(LearnedWorkflowCandidateState.VERIFIED)
        val badFences = listOf(
            fence(candidate).copy(artifactSha256 = "f".repeat(64)),
            fence(candidate).copy(toolSchemaFingerprintsWire = "[]"),
            fence(candidate).copy(verifierVersion = "other"),
            fence(candidate).copy(assistantId = OTHER_ASSISTANT.toString()),
        )
        badFences.forEach { bad ->
            val store = CandidateMemory(candidate)
            val workflows = WorkflowMemory()
            val result = saga(store, workflows).promoteVerifiedDisabled(bad, grant(candidate), 50L)
            assertEquals(
                WorkflowPromotionResult.Rejected(WorkflowPromotionResult.Reason.FENCE_MISMATCH),
                result,
            )
            assertEquals(null, workflows.definition)
        }
    }

    @Test
    fun `revoked grant and failed current revalidation stay inert`() = runBlocking {
        val candidate = candidate(LearnedWorkflowCandidateState.VERIFIED)
        val revoked = grant(candidate).copy(
            state = PolicyGrantAuthorityState.REVOKED,
            stateVersion = 2L,
            revokedAtEpochMs = 20L,
            updatedAtEpochMs = 20L,
            reason = PolicyGrantReason.USER_REVOKED_CONTEXTUAL_ADVICE,
        )
        val workflows = WorkflowMemory()
        assertTrue(saga(CandidateMemory(candidate), workflows).promoteVerifiedDisabled(
            fence(candidate), revoked, 60L,
        ) is WorkflowPromotionResult.Rejected)
        assertEquals(null, workflows.definition)

        val rejecting = LearnedWorkflowPromotionSaga(
            candidates = CandidateMemory(candidate),
            workflows = workflows,
            revalidator = WorkflowPromotionRevalidator { _, _ -> false },
            rolloutFence = { true },
        )
        assertTrue(rejecting.promoteVerifiedDisabled(
            fence(candidate), grant(candidate), 60L,
        ) is WorkflowPromotionResult.Rejected)
    }

    @Test
    fun `execution attestation compares installed definition not provenance claim alone`() =
        runBlocking {
            val candidate = candidate(LearnedWorkflowCandidateState.VERIFIED)
            val workflows = WorkflowMemory()
            saga(CandidateMemory(candidate), workflows).promoteVerifiedDisabled(
                fence(candidate), grant(candidate), 60L,
            )
            val installed = checkNotNull(workflows.definition).copy(enabled = true)
            val snapshot = LearnedWorkflowAuthoritySnapshot(
                sourceCandidateId = candidate.id,
                sourceArtifactHash = candidate.artifactSha256,
                grantDigest = candidate.sourceGrantDigest,
                authoringAssistantId = candidate.assistantId,
                installedDefinition = installed,
            )

            assertTrue(candidate.matchesInstalled(snapshot))
            assertFalse(
                candidate.matchesInstalled(
                    snapshot.copy(installedDefinition = installed.copy(name = "drifted")),
                ),
            )
        }

    private fun saga(c: CandidateMemory, w: WorkflowMemory) = LearnedWorkflowPromotionSaga(
        candidates = c,
        workflows = w,
        revalidator = WorkflowPromotionRevalidator { _, _ -> true },
        rolloutFence = { true },
    )

    private class CandidateMemory(initial: LearnedWorkflowCandidate) : WorkflowPromotionCandidateStore {
        var current = initial
        val transitions = mutableListOf<String>()
        override suspend fun find(candidateId: String) = current.takeIf { it.id == candidateId }
        override suspend fun transitionExact(
            expected: LearnedWorkflowCandidate,
            nextState: LearnedWorkflowCandidateState,
            nowMs: Long,
        ): Boolean {
            if (current != expected) return false
            current = current.copy(
                state = nextState,
                stateVersion = current.stateVersion + 1L,
                updatedAtMs = maxOf(nowMs, current.updatedAtMs),
            )
            transitions += nextState.name
            return true
        }
    }

    private class WorkflowMemory : PromotedWorkflowStore {
        var definition: WorkflowDefinition? = null
        var mode = PromotionWorkflowWrite.INSERTED
        var enabled = false
        override suspend fun ensureDisabled(definition: WorkflowDefinition): PromotionWorkflowWrite {
            if (mode != PromotionWorkflowWrite.CONFLICT) this.definition = definition
            return mode
        }
        override suspend fun enableExact(
            workflowId: String, candidateId: String, artifactSha256: String,
            grantDigest: String, expectedStateVersion: Long, nowMs: Long,
        ): Boolean {
            enabled = expectedStateVersion == 1L
            return enabled
        }
    }
}

private fun candidate(state: LearnedWorkflowCandidateState): LearnedWorkflowCandidate {
    val candidateId = "workflow-candidate-v1:${"1".repeat(64)}"
    val schema = "2".repeat(64)
    val grant = grantDigest()
    val template = """{"actions":[{"args":{},"timeout_seconds":60,"tool":"show_toast","tool_schema_fingerprint":"$schema"}],"authoring_assistant_id":"$ASSISTANT","authority_subject_id":null,"capability_snapshot":["device.toast"],"conditions":[],"cooldown_seconds":0,"created_at_ms":"10","enabled":false,"id":"$candidateId","max_runs_per_day":1,"name":"Learned","origin":"LEARNED","source_candidate_id":"$candidateId","trigger":{"type":"manual"},"updated_at_ms":"10"}"""
    val slots = emptyList<me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowTypedSlot>()
    val schemas = listOf(LearnedWorkflowToolSchemaFingerprint(0, "show_toast", schema))
    val capabilities = listOf("device.toast")
    val artifact = WorkflowArtifactCanonicalizer.artifactSha256(
        template,
        WorkflowArtifactCanonicalizer.canonicalSlots(slots),
        WorkflowArtifactCanonicalizer.canonicalCapabilities(capabilities),
        WorkflowArtifactCanonicalizer.canonicalToolSchemas(schemas),
        ASSISTANT.toString(), null, POLICY_ID, 1L, POLICY_SHA, grant, "compiler-v1", "template-v1",
    )
    return LearnedWorkflowCandidate(
        candidateId, 1L, 2L, state, ASSISTANT.toString(), null, POLICY_ID, 1L,
        POLICY_SHA, grant, "evidence-1", listOf("evidence-1"), template, slots,
        capabilities, schemas, "provider", "model", "config", 1L, "compiler-v1",
        "prompt-v1", "template-v1", "validator-v1", "verifier-v1", 1024, artifact,
        LearnedWorkflowVerificationReport(
            "verifier-v1", "3".repeat(64), LearnedWorkflowVerificationStatus.PASSED,
            1, 0, emptyList(), 12L,
        ),
        12L, null, 10L, 12L,
    )
}

private fun fence(c: LearnedWorkflowCandidate) = WorkflowPromotionFence(
    c.id, c.candidateVersion, c.artifactSha256, c.sourceGrantDigest,
    WorkflowArtifactCanonicalizer.canonicalToolSchemas(c.toolSchemaFingerprints),
    c.verifierVersion, c.assistantId, c.authoritySubjectId,
)

private fun grant(c: LearnedWorkflowCandidate): PolicyGrantAuthoritySnapshot {
    val scope = LearningScope.Assistant(ASSISTANT)
    val stream = STREAM.toString()
    return PolicyGrantAuthoritySnapshot(
        policyGrantId(stream, scope, ASSISTANT, c.sourcePolicyId), stream, scope, ASSISTANT,
        c.sourcePolicyId, 1L, POLICY_SHA, PolicyGrantAuthorityState.GRANTED, 1L,
        10L, null, PolicyGrantReason.USER_APPROVED_CONTEXTUAL_ADVICE, 10L, 10L,
    )
}

private fun grantDigest() = WorkflowArtifactCanonicalizer.grantDigest(
    policyGrantId(STREAM.toString(), LearningScope.Assistant(ASSISTANT), ASSISTANT, POLICY_ID),
    STREAM.toString(), 1L, 1L, POLICY_SHA,
)

private val ASSISTANT = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val OTHER_ASSISTANT = Uuid.parse("10000000-0000-0000-0000-000000000002")
private val STREAM = Uuid.parse("20000000-0000-0000-0000-000000000001")
private const val POLICY_ID = "policy-1"
private val POLICY_SHA = "a".repeat(64)
