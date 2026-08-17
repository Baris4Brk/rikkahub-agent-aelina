package me.rerere.rikkahub.learning.promotion

import android.content.ContentValues
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.uuid.Uuid
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import me.rerere.rikkahub.learning.grant.PolicyGrantReason
import me.rerere.rikkahub.learning.grant.policyGrantId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.storage.PolicyApplicabilityWire
import me.rerere.rikkahub.learning.storage.StoredLearningPolicyStatus
import me.rerere.rikkahub.learning.storage.entity.toEntity
import me.rerere.rikkahub.learning.storage.entity.toDomainOrNull
import me.rerere.rikkahub.learning.workflow.WorkflowArtifactCanonicalizer
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowToolSchemaFingerprint
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowVerificationReport
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowVerificationStatus
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable managed-emulator only. Never run on the Honor AAK-AN00 primary phone. */
@RunWith(AndroidJUnit4::class)
class RoomWorkflowPromotionCrashInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
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
    fun crashAfterPromotingAndWorkflowInsert_reopensAndFinishesExactDisabledPromotion() = runBlocking {
        val candidate = candidate(LearnedWorkflowCandidateState.PROMOTING)
        database.policyDao().insertPolicy(sourcePolicy())
        insertEntity("learned_workflow_candidates", candidate.toEntity())
        database.close()
        database = openDatabase() // process-death boundary: no in-memory saga state survives

        val workflowStore = CrashReplayWorkflowStore(
            initialMode = PromotionWorkflowWrite.ALREADY_EXACT,
        )
        val saga = LearnedWorkflowPromotionSaga(
            candidates = RoomWorkflowPromotionCandidateStore(
                database.learnedWorkflowCandidateDao(),
            ),
            workflows = workflowStore,
            revalidator = WorkflowPromotionRevalidator { _, _ -> true },
            rolloutFence = { true },
        )

        val result = saga.promoteVerifiedDisabled(fence(candidate), grant(candidate), 30L)

        assertTrue((result as WorkflowPromotionResult.PromotedDisabled).replayed)
        assertEquals(
            LearnedWorkflowCandidateState.PROMOTED_DISABLED,
            database.learnedWorkflowCandidateDao().find(candidate.id)!!.toDomainOrNull()!!.state,
        )
        assertFalse(workflowStore.enabled)
    }

    @Test
    fun competingPromotionCandidateCas_hasOneWinnerAndNeverCreatesEnabledWorkflow() = runBlocking {
        val candidate = candidate(LearnedWorkflowCandidateState.VERIFIED)
        database.policyDao().insertPolicy(sourcePolicy())
        insertEntity("learned_workflow_candidates", candidate.toEntity())
        val store = RoomWorkflowPromotionCandidateStore(database.learnedWorkflowCandidateDao())

        val first = store.transitionExact(
            candidate,
            LearnedWorkflowCandidateState.PROMOTING,
            40L,
        )
        val staleReplay = store.transitionExact(
            candidate,
            LearnedWorkflowCandidateState.PROMOTING,
            40L,
        )

        assertTrue(first)
        assertFalse(staleReplay)
        assertEquals(
            LearnedWorkflowCandidateState.PROMOTING,
            store.find(candidate.id)!!.state,
        )
    }

    private fun openDatabase(): LearningDatabase = Room.databaseBuilder(
        context,
        LearningDatabase::class.java,
        DB_NAME,
    ).build()

    /** Seed immutable crash snapshots without calling the transition under test. */
    private fun insertEntity(table: String, entity: Any) {
        val values = ContentValues()
        entity.javaClass.declaredFields
            .filterNot { field ->
                java.lang.reflect.Modifier.isStatic(field.modifiers) ||
                    field.name.startsWith("$") || field.isSynthetic
            }
            .forEach { field ->
                field.isAccessible = true
                values.putRoomValue(field.name.camelToSnake(), field.get(entity))
            }
        val rowId = database.openHelper.writableDatabase.insert(table, 0, values)
        check(rowId != -1L) { "Failed to seed $table crash snapshot" }
    }

    private class CrashReplayWorkflowStore(
        private val initialMode: PromotionWorkflowWrite,
    ) : PromotedWorkflowStore {
        var enabled = false

        override suspend fun ensureDisabled(definition: WorkflowDefinition): PromotionWorkflowWrite {
            check(!definition.enabled)
            return initialMode
        }

        override suspend fun enableExact(
            workflowId: String,
            candidateId: String,
            artifactSha256: String,
            grantDigest: String,
            expectedStateVersion: Long,
            nowMs: Long,
        ): Boolean = false.also { enabled = it }
    }
}

private fun ContentValues.putRoomValue(column: String, value: Any?) {
    when (value) {
        null -> putNull(column)
        is String -> put(column, value)
        is Long -> put(column, value)
        is Int -> put(column, value)
        is Boolean -> put(column, value)
        is ByteArray -> put(column, value)
        is Double -> put(column, value)
        is Float -> put(column, value)
        else -> error("Unsupported Room seed value for $column: ${value::class.java.name}")
    }
}

private fun String.camelToSnake(): String = replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
    .lowercase()

private fun candidate(state: LearnedWorkflowCandidateState): LearnedWorkflowCandidate {
    val candidateId = "workflow-candidate-v1:${"1".repeat(64)}"
    val schema = "2".repeat(64)
    val grant = grantDigest()
    val template = """{"actions":[{"args":{},"timeout_seconds":60,"tool":"show_toast","tool_schema_fingerprint":"$schema"}],"authoring_assistant_id":"$ASSISTANT","capability_snapshot":["device.toast"],"conditions":[],"cooldown_seconds":0,"created_at_ms":"10","enabled":false,"id":"$candidateId","max_runs_per_day":1,"name":"Learned","origin":"LEARNED","source_candidate_id":"$candidateId","trigger":{"type":"manual"},"updated_at_ms":"10"}"""
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

private fun sourcePolicy() = LearningPolicyEntity(
    id = POLICY_ID,
    scopeKind = LearningScope.Assistant(ASSISTANT).kind.name,
    scopeId = LearningScope.Assistant(ASSISTANT).storageId,
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
    applicableToolSchemasWire = PolicyApplicabilityWire.encodeToolSchemas(setOf("2".repeat(64))),
    applicableModelIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("5".repeat(64)),
    applicableProviderIdentityWire = PolicyApplicabilityWire.encodeExactIdentity("6".repeat(64)),
    applicableTemplateIdentity = "8".repeat(64),
    applicableConfigurationIdentity = "7".repeat(64),
    applicableConfigurationGeneration = 1L,
    applicableCapabilityDigest = "9".repeat(64),
    applicableAuthorityDigest = "0".repeat(64),
    staleReason = null,
    distinctEpisodeSupport = 1L,
    positiveEpisodeCount = 1L,
    negativeEpisodeCount = 0L,
    usageCount = 0L,
    confidence = 1.0,
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

private const val DB_NAME = "p5-promotion-crash.db"
private val ASSISTANT = Uuid.parse("10000000-0000-0000-0000-000000000001")
private val STREAM = Uuid.parse("20000000-0000-0000-0000-000000000001")
private const val POLICY_ID = "policy-1"
private val POLICY_SHA = "a".repeat(64)
