package me.rerere.rikkahub.learning.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.policy.PolicyLifecycleReason
import me.rerere.rikkahub.learning.policy.PolicyMutationActor
import me.rerere.rikkahub.learning.policy.PolicyMutationConflict
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.policy.PolicyMutationRequest
import me.rerere.rikkahub.learning.policy.PolicyMutationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable managed-emulator only. Never run on the Honor AAK-AN00 primary phone. */
@RunWith(AndroidJUnit4::class)
class RoomPolicyLifecycleMutationCrashReplayTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: LearningDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun headAndRevisionCommitAtomically_exactReplaySurvivesReopen_andRevisionCrashRollsBackHead() =
        runBlocking {
            val replayPolicy = policy(REPLAY_POLICY_ID, "a".repeat(64))
            seedCandidate(replayPolicy)
            val replayRequest = shadowRequest(replayPolicy)

            assertEquals(
                PolicyMutationResult.Applied(
                    replayPolicy.id,
                    2L,
                    LearningPolicyStatus.SHADOW,
                ),
                RoomPolicyLifecycleMutationStore(database).mutate(replayRequest),
            )

            reopen()
            assertEquals(
                PolicyMutationResult.Duplicate(replayPolicy.id, 2L),
                RoomPolicyLifecycleMutationStore(database).mutate(replayRequest),
            )
            val durableHead = requireNotNull(database.policyDao().findPolicy(replayPolicy.id))
            assertEquals(2L, durableHead.stateVersion)
            assertEquals(StoredLearningPolicyStatus.SHADOW.name, durableHead.status)
            val durableRevision = requireNotNull(
                database.policyDao().findRevision(replayPolicy.id, 2L),
            )
            assertEquals(PolicyLifecycleReason.SHADOW_ELIGIBLE.name, durableRevision.reasonCode)
            assertEquals(PolicyMutationActor.SHADOW_GATE.name, durableRevision.actor)
            assertEquals(2, database.policyDao().listRevisions(replayPolicy.id, 100).size)
            assertEquals(
                PolicyMutationResult.Conflict(PolicyMutationConflict.REVISION_CONFLICT),
                RoomPolicyLifecycleMutationStore(database).mutate(
                    replayRequest.copy(frozenNowMs = 21L),
                ),
            )
            assertEquals(2L, database.policyDao().findPolicy(replayPolicy.id)?.stateVersion)
            assertEquals(2, database.policyDao().listRevisions(replayPolicy.id, 100).size)

            val crashPolicy = policy(CRASH_POLICY_ID, "9".repeat(64))
            seedCandidate(crashPolicy)
            database.policyDao().insertRevision(
                PolicyRevisionEntity(
                    policyId = crashPolicy.id,
                    revision = 2L,
                    beforeSnapshot = "Candidate snapshot before simulated crash.",
                    afterSnapshot = "Pre-existing conflicting revision boundary.",
                    beforeArtifactSha256 = crashPolicy.artifactSha256,
                    afterArtifactSha256 = crashPolicy.artifactSha256,
                    reasonCode = LearningPolicyRevisionReason.SHADOW_ELIGIBLE.name,
                    actor = LearningPolicyRevisionActor.SHADOW_GATE.name,
                    createdAtMs = 20L,
                ),
            )

            val failure = runCatching {
                RoomPolicyLifecycleMutationStore(database).mutate(shadowRequest(crashPolicy))
            }.exceptionOrNull()
            assertNotNull(failure)

            reopen()
            assertEquals(crashPolicy, database.policyDao().findPolicy(crashPolicy.id))
            assertEquals(1L, database.policyDao().findPolicy(crashPolicy.id)?.stateVersion)
            assertEquals(
                StoredLearningPolicyStatus.CANDIDATE.name,
                database.policyDao().findPolicy(crashPolicy.id)?.status,
            )
            assertNotNull(database.policyDao().findRevision(crashPolicy.id, 1L))
            assertNotNull(database.policyDao().findRevision(crashPolicy.id, 2L))
            assertEquals(2, database.policyDao().listRevisions(crashPolicy.id, 100).size)
            assertTrue(
                database.policyDao().findRevision(crashPolicy.id, 2L)?.afterSnapshot
                    ?.contains("Pre-existing conflicting revision boundary.") == true,
            )
        }

    private suspend fun seedCandidate(entity: LearningPolicyEntity) {
        database.policyDao().insertPolicy(entity)
        database.policyDao().insertRevision(
            PolicyRevisionEntity(
                policyId = entity.id,
                revision = 1L,
                beforeSnapshot = null,
                afterSnapshot = "Validated candidate snapshot.",
                beforeArtifactSha256 = null,
                afterArtifactSha256 = entity.artifactSha256,
                reasonCode = LearningPolicyRevisionReason.CREATE.name,
                actor = LearningPolicyRevisionActor.SYSTEM.name,
                createdAtMs = entity.createdAtMs,
            ),
        )
    }

    private fun shadowRequest(entity: LearningPolicyEntity) = PolicyMutationRequest.Transition(
        fence = PolicyMutationFence(
            policyId = entity.id,
            scope = SCOPE,
            expectedRevision = 1L,
            expectedContentRevision = entity.contentRevision,
            expectedArtifactHash = entity.artifactSha256,
        ),
        target = LearningPolicyStatus.SHADOW,
        reason = PolicyLifecycleReason.SHADOW_ELIGIBLE,
        frozenNowMs = 20L,
        actor = PolicyMutationActor.SHADOW_GATE,
    )

    private fun policy(id: String, artifact: String) = LearningPolicyEntity(
        id = id,
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        taskSignature = "task-signature-v1",
        policyType = "PROCEDURE",
        triggerSummary = "Use the exact bounded trigger.",
        procedureSummary = "Apply the verified bounded procedure.",
        verificationSummary = "Verify the structured outcome.",
        boundarySummary = "Use only in the exact assistant scope.",
        failureModeSummary = "Abstain when an authority fence is missing.",
        stateVersion = 1L,
        contentRevision = 1L,
        artifactSha256 = artifact,
        compilerAbi = "policy-room-atomic-v1",
        status = StoredLearningPolicyStatus.CANDIDATE.name,
        sourceValid = true,
        schemaValid = true,
        applicableToolSchemasWire = PolicyApplicabilityWire.encodeToolSchemas(emptySet()),
        applicableModelIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("b".repeat(64)),
        applicableProviderIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("c".repeat(64)),
        applicableTemplateIdentity = "d".repeat(64),
        applicableConfigurationIdentity = "e".repeat(64),
        applicableConfigurationGeneration = 1L,
        applicableCapabilityDigest = "f".repeat(64),
        applicableAuthorityDigest = "8".repeat(64),
        staleReason = null,
        distinctEpisodeSupport = 1L,
        positiveEpisodeCount = 1L,
        negativeEpisodeCount = 0L,
        usageCount = 0L,
        confidence = 0.8,
        observedUtilityDelta = null,
        utilityUncertainty = null,
        producerModelIdentity = "1".repeat(64),
        producerProviderIdentity = "2".repeat(64),
        producerProviderKind = "local_litert",
        producerConfigurationIdentity = "3".repeat(64),
        producerConfigGeneration = 1L,
        producerPromptIdentity = "policy-room-prompt-v1",
        producerTemplateIdentity = "policy-room-template-v1",
        producerSchemaIdentity = "policy-room-schema-v1",
        createdAtMs = 10L,
        updatedAtMs = 10L,
        lastUsedAtMs = null,
    )

    private fun reopen() {
        database.close()
        database = openDatabase()
    }

    private fun openDatabase(): LearningDatabase = Room.databaseBuilder(
        context,
        LearningDatabase::class.java,
        DB_NAME,
    ).build()
}

private const val DB_NAME = "p5-policy-lifecycle-crash-replay.db"
private const val REPLAY_POLICY_ID = "policy-room-replay"
private const val CRASH_POLICY_ID = "policy-room-atomic-crash"
private val SCOPE = LearningScope.Assistant(
    Uuid.parse("72000000-0000-4000-8000-000000000001"),
)
