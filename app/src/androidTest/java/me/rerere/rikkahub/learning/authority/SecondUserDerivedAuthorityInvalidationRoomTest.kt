package me.rerere.rikkahub.learning.authority

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.assistant.SecondUserDerivedAuthorityInvalidationRequest
import me.rerere.rikkahub.assistant.SecondUserDerivedAuthorityInvalidationResult
import me.rerere.rikkahub.assistant.SecondUserLearningAuthorityRevocationFence
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.storage.PolicyApplicabilityWire
import me.rerere.rikkahub.learning.storage.PolicyRevisionEntity
import me.rerere.rikkahub.learning.storage.StoredLearningPolicyStatus
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateEntity
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class SecondUserDerivedAuthorityInvalidationRoomTest {
    private lateinit var database: LearningDatabase
    private val fence = SecondUserLearningAuthorityRevocationFence(
        assistantId = ASSISTANT_ID,
        conversationId = CONVERSATION_ID,
        authorityEpoch = 12L,
        frozenNowMs = 50L,
    )
    private val scope get() = LearningScope.AuthoritySubject(fence.authoritySubjectId)

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LearningDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun exactPolicyAndWorkflowBecomeAuditablyStaleAndReplayIsNoOp() = runBlocking {
        database.policyDao().insertPolicy(policy())
        database.policyDao().insertRevision(
            PolicyRevisionEntity(
                policyId = POLICY_ID,
                revision = 1L,
                beforeSnapshot = null,
                afterSnapshot = "Validated candidate snapshot.",
                beforeArtifactSha256 = null,
                afterArtifactSha256 = POLICY_SHA,
                reasonCode = "CREATE",
                actor = "SYSTEM",
                createdAtMs = 10L,
            ),
        )
        database.learnedWorkflowCandidateDao().insertCompiled(workflowCandidate())
        val port = RoomSecondUserDerivedAuthorityInvalidationPort(database)
        val request = SecondUserDerivedAuthorityInvalidationRequest(
            fence = fence,
            authoritySubjectId = fence.authoritySubjectId,
        )

        val first = port.invalidateExactAuthorityBatch(request)
            as SecondUserDerivedAuthorityInvalidationResult.Ready
        val replay = port.invalidateExactAuthorityBatch(request)
            as SecondUserDerivedAuthorityInvalidationResult.Ready

        assertEquals(1, first.batch.policiesMadeStale)
        assertEquals(1, first.batch.workflowCandidatesMadeStale)
        assertTrue(first.batch.complete)
        assertEquals(0, replay.batch.policiesMadeStale)
        assertEquals(0, replay.batch.workflowCandidatesMadeStale)
        assertTrue(replay.batch.complete)

        val stalePolicy = requireNotNull(database.policyDao().findPolicy(POLICY_ID))
        assertEquals(StoredLearningPolicyStatus.STALE_AUTHORITY.name, stalePolicy.status)
        assertEquals("AUTHORITY_CHANGED", stalePolicy.staleReason)
        assertEquals(2L, stalePolicy.stateVersion)
        val policyAudit = requireNotNull(database.policyDao().findRevision(POLICY_ID, 2L))
        assertEquals("AUTHORITY_CHANGED", policyAudit.reasonCode)
        assertEquals("AUTHORITY_RECONCILER", policyAudit.actor)
        assertTrue("lifecycle_evidence_kind=AUTHORITY_DRIFT" in policyAudit.afterSnapshot)
        assertTrue("lifecycle_evidence_digest=" in policyAudit.afterSnapshot)

        val staleWorkflow = requireNotNull(
            database.learnedWorkflowCandidateDao().find(WORKFLOW_ID),
        )
        assertEquals(LearnedWorkflowCandidateState.STALE_AUTHORITY.name, staleWorkflow.state)
        assertEquals(2L, staleWorkflow.stateVersion)
        val workflowAudit = requireNotNull(
            database.learnedWorkflowCandidateDao().findRevision(WORKFLOW_ID, 2L),
        )
        assertEquals("AUTHORITY_DRIFT", workflowAudit.reasonCode)
        assertEquals("AUTHORITY_RECONCILER", workflowAudit.actor)
    }

    private fun policy() = LearningPolicyEntity(
        id = POLICY_ID,
        scopeKind = scope.kind.name,
        scopeId = scope.storageId,
        taskSignature = "task-signature-v1",
        policyType = "PROCEDURE",
        triggerSummary = "Use the exact verified trigger.",
        procedureSummary = "Apply the bounded verified procedure.",
        verificationSummary = "Verify the structured terminal state.",
        boundarySummary = "Only the exact authority scope is eligible.",
        failureModeSummary = "Unknown authority fails closed.",
        stateVersion = 1L,
        contentRevision = 1L,
        artifactSha256 = POLICY_SHA,
        compilerAbi = "policy-compiler-v1",
        status = StoredLearningPolicyStatus.CANDIDATE.name,
        sourceValid = true,
        schemaValid = true,
        applicableToolSchemasWire = PolicyApplicabilityWire.encodeToolSchemas(emptySet()),
        applicableModelIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("model-v1"),
        applicableProviderIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("provider-v1"),
        applicableTemplateIdentity = "1".repeat(64),
        applicableConfigurationIdentity = "2".repeat(64),
        applicableConfigurationGeneration = 1L,
        applicableCapabilityDigest = "3".repeat(64),
        applicableAuthorityDigest = "4".repeat(64),
        staleReason = null,
        distinctEpisodeSupport = 0L,
        positiveEpisodeCount = 0L,
        negativeEpisodeCount = 0L,
        usageCount = 0L,
        confidence = 0.0,
        observedUtilityDelta = null,
        utilityUncertainty = null,
        producerModelIdentity = "5".repeat(64),
        producerProviderIdentity = "6".repeat(64),
        producerProviderKind = "local_litert",
        producerConfigurationIdentity = "7".repeat(64),
        producerConfigGeneration = 1L,
        producerPromptIdentity = "distiller-prompt-v1",
        producerTemplateIdentity = "policy-template-v1",
        producerSchemaIdentity = "policy-schema-v1",
        createdAtMs = 10L,
        updatedAtMs = 10L,
        lastUsedAtMs = null,
    )

    private fun workflowCandidate() = LearnedWorkflowCandidateEntity(
        id = WORKFLOW_ID,
        candidateVersion = 1L,
        stateVersion = 1L,
        state = LearnedWorkflowCandidateState.PROPOSED.name,
        assistantId = ASSISTANT_ID.toString(),
        authoritySubjectId = fence.authoritySubjectId,
        sourcePolicyId = POLICY_ID,
        sourcePolicyRevision = 1L,
        sourcePolicyArtifactSha256 = POLICY_SHA,
        sourceGrantDigest = "8".repeat(64),
        positiveAnchorEvidenceId = "evidence-1",
        evidenceIdsWire = "[]",
        canonicalTemplateJson = "{}",
        typedSlotsWire = "[]",
        capabilitySnapshotWire = "[]",
        toolSchemaFingerprintsWire = "[]",
        producerProviderIdentity = "provider-v1",
        producerModelIdentity = "model-v1",
        producerConfigurationIdentity = "config-v1",
        producerConfigGeneration = 1L,
        compilerVersion = "compiler-v1",
        promptVersion = "prompt-v1",
        templateVersion = "template-v1",
        validatorVersion = "validator-v1",
        verifierVersion = "verifier-v1",
        maxOutputUtf8Bytes = 1024,
        artifactSha256 = "9".repeat(64),
        verificationReportWire = null,
        verifiedAtMs = null,
        archivedAtMs = null,
        createdAtMs = 10L,
        updatedAtMs = 10L,
    )

    private companion object {
        val ASSISTANT_ID: Uuid = Uuid.parse("10000000-0000-0000-0000-000000000001")
        val CONVERSATION_ID: Uuid = Uuid.parse("20000000-0000-0000-0000-000000000002")
        const val POLICY_ID = "policy-authority-revocation-v1"
        val POLICY_SHA: String = "a".repeat(64)
        const val WORKFLOW_ID =
            "workflow-candidate-v1:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}

