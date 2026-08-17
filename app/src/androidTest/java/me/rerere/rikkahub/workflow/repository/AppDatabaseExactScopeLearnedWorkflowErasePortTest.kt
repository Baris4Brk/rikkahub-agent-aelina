package me.rerere.rikkahub.workflow.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.workflow.db.WorkflowEntity
import me.rerere.rikkahub.workflow.db.WorkflowRunEntity
import me.rerere.rikkahub.workflow.model.WorkflowOrigin
import me.rerere.rikkahub.workflow.model.WorkflowAction
import me.rerere.rikkahub.workflow.model.WorkflowDefinition
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.model.WorkflowToolSchemaSnapshot
import me.rerere.rikkahub.workflow.model.TriggerSpec
import kotlin.uuid.Uuid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable managed-device/emulator only; never execute on the primary phone. */
@RunWith(AndroidJUnit4::class)
class AppDatabaseExactScopeLearnedWorkflowErasePortTest {
    private lateinit var database: AppDatabase
    private lateinit var port: AppDatabaseExactScopeLearnedWorkflowErasePort

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        port = AppDatabaseExactScopeLearnedWorkflowErasePort(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun learnedDefinitionAndRunHistoryAreRedactedAndReplayIsStable() = runBlocking {
        val candidateId = candidateId(1)
        val workflowId = LearnedWorkflowEraseTombstoneContract.workflowId(candidateId)
        database.workflowDao().insert(liveLearned(candidateId))
        database.workflowRunDao().insert(
            WorkflowRunEntity(
                workflowId = workflowId,
                firedAtMs = 8L,
                status = "FAILED",
                durationMs = 1L,
                errorMessage = "private runtime payload",
            ),
        )

        val first = port.redactAndFence(listOf(candidateId), frozenNowMs = 42L)
        val replay = port.redactAndFence(listOf(candidateId), frozenNowMs = 42L)

        assertEquals(1, first.redactedWorkflowDefinitions)
        assertEquals(0, first.insertedFenceClaims)
        assertEquals(first, replay)
        val row = requireNotNull(database.workflowDao().getById(workflowId))
        assertFalse(row.enabled)
        assertEquals("{}", row.definitionJson)
        assertNull(row.description)
        assertNull(row.authoringAssistantId)
        assertNull(row.sourceArtifactHash)
        assertNull(row.grantDigest)
        assertEquals(0, database.workflowRunDao().lastN(workflowId, 10).size)
    }

    @Test
    fun missingDefinitionGetsPermanentClaimThatRejectsLateRunAndGenericDeletion() = runBlocking {
        val candidateId = candidateId(2)
        val workflowId = LearnedWorkflowEraseTombstoneContract.workflowId(candidateId)

        val first = port.redactAndFence(listOf(candidateId), frozenNowMs = 42L)
        val replay = port.redactAndFence(listOf(candidateId), frozenNowMs = 42L)

        assertEquals(0, first.redactedWorkflowDefinitions)
        assertEquals(1, first.insertedFenceClaims)
        assertEquals(first, replay)
        val claim = requireNotNull(database.workflowDao().getById(workflowId))
        assertTrue(LearnedWorkflowEraseTombstoneContract.isExactTombstone(candidateId, claim))
        assertEquals(0, database.workflowDao().deleteById(workflowId))
        assertEquals(
            -1L,
            database.workflowRunDao().insert(
                WorkflowRunEntity(
                    workflowId = workflowId,
                    firedAtMs = 99L,
                    status = "FAILED",
                    durationMs = 1L,
                    errorMessage = "late payload",
                ),
            ),
        )
        assertEquals(0, database.workflowRunDao().lastN(workflowId, 10).size)
    }

    @Test
    fun userCollisionFailsTheWholeBatchWithoutRedactingAnotherLearnedRow() = runBlocking {
        val learnedCandidateId = candidateId(3)
        val userCollisionId = candidateId(4)
        val learned = liveLearned(learnedCandidateId)
        val user = liveLearned(userCollisionId).copy(origin = WorkflowOrigin.USER.name)
        database.workflowDao().insert(learned)
        database.workflowDao().insert(user)

        val failure = runCatching {
            port.redactAndFence(listOf(learnedCandidateId, userCollisionId), frozenNowMs = 42L)
        }.exceptionOrNull()

        assertTrue(failure is ExactScopeLearnedWorkflowEraseConflictException)
        assertEquals(learned, database.workflowDao().getById(learned.id))
        assertEquals(user, database.workflowDao().getById(user.id))
    }

    @Test
    fun durableScopeProvenanceWorksAfterCandidateRowsAreGone() = runBlocking {
        val assistant = "00000000-0000-4000-8000-000000000001"
        val assistantRow = validLearned(candidateId(5), assistant, authoritySubjectId = null)
        val authorityRow = validLearned(candidateId(6), assistant, "authority-subject-6")
        val retainedRow = validLearned(candidateId(7), assistant, "authority-subject-7")
        database.workflowDao().insert(assistantRow)
        database.workflowDao().insert(authorityRow)
        database.workflowDao().insert(retainedRow)

        val assistantReceipt = port.redactExactScope(
            LearningScope.Assistant(Uuid.parse(assistant)),
            frozenNowMs = 50L,
        )
        assertEquals(1, assistantReceipt.redactedExactScopeDefinitions)
        assertTrue(LearnedWorkflowEraseTombstoneContract.isSanitizedDefinitionTombstone(
            database.workflowDao().getById(assistantRow.id),
        ))
        assertEquals(authorityRow, database.workflowDao().getById(authorityRow.id))

        val authorityReceipt = port.redactExactScope(
            LearningScope.AuthoritySubject("authority-subject-6"),
            frozenNowMs = 51L,
        )
        assertEquals(1, authorityReceipt.redactedExactScopeDefinitions)
        assertTrue(LearnedWorkflowEraseTombstoneContract.isSanitizedDefinitionTombstone(
            database.workflowDao().getById(authorityRow.id),
        ))
        assertEquals(retainedRow, database.workflowDao().getById(retainedRow.id))
    }

    @Test
    fun globalDerivedResetRedactsEveryLearnedOrphanButPreservesUserAndReplays() = runBlocking {
        val learned = validLearned(
            candidateId(8),
            "00000000-0000-4000-8000-000000000001",
            null,
        )
        val corruptOrphan = liveLearned(candidateId(9))
        val user = liveLearned(candidateId(10)).copy(origin = WorkflowOrigin.USER.name)
        val forgedMarkerOrphan = liveLearned(candidateId(11)).copy(
            staleReason = LearnedWorkflowEraseTombstoneContract.DEFINITION_TOMBSTONE_REASON,
        )
        database.workflowDao().insert(learned)
        database.workflowDao().insert(corruptOrphan)
        database.workflowDao().insert(user)
        database.workflowDao().insert(forgedMarkerOrphan)
        database.workflowRunDao().insertRaw(
            WorkflowRunEntity(
                workflowId = forgedMarkerOrphan.id,
                firedAtMs = 59L,
                status = "FAILED",
                durationMs = 1L,
                errorMessage = "private forged-marker payload",
            ),
        )

        val first = port.redactAllForDerivedReset(60L)
        val replay = port.redactAllForDerivedReset(60L)

        assertEquals(3, first.redactedLearnedDefinitions)
        assertEquals(0, replay.redactedLearnedDefinitions)
        assertTrue(first.complete)
        assertTrue(replay.complete)
        assertTrue(LearnedWorkflowEraseTombstoneContract.isSanitizedDefinitionTombstone(
            database.workflowDao().getById(learned.id),
        ))
        assertTrue(LearnedWorkflowEraseTombstoneContract.isSanitizedDefinitionTombstone(
            database.workflowDao().getById(corruptOrphan.id),
        ))
        assertTrue(LearnedWorkflowEraseTombstoneContract.isSanitizedDefinitionTombstone(
            database.workflowDao().getById(forgedMarkerOrphan.id),
        ))
        assertEquals(0, database.workflowRunDao().lastN(forgedMarkerOrphan.id, 10).size)
        assertEquals(user, database.workflowDao().getById(user.id))
    }

    private fun liveLearned(candidateId: String) = WorkflowEntity(
        id = LearnedWorkflowEraseTombstoneContract.workflowId(candidateId),
        name = "private name",
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

    private fun candidateId(value: Int): String =
        "workflow-candidate-v1:${value.toString(16).padStart(64, '0')}"

    private fun validLearned(
        candidateId: String,
        assistantId: String,
        authoritySubjectId: String?,
    ): WorkflowEntity {
        val definition = WorkflowDefinition(
            id = LearnedWorkflowEraseTombstoneContract.workflowId(candidateId),
            name = "private name",
            description = "private description",
            enabled = false,
            trigger = TriggerSpec.Manual,
            actions = listOf(
                WorkflowAction(
                    tool = "show_toast",
                    args = buildJsonObject { put("text", JsonPrimitive("private")) },
                    toolSchemaFingerprint = "c".repeat(64),
                ),
            ),
            createdAtMs = 1L,
            updatedAtMs = 2L,
            authoringAssistantId = assistantId,
            capabilitySnapshot = setOf("tool.show_toast"),
            origin = WorkflowOrigin.LEARNED,
            sourceCandidateId = candidateId,
            sourceArtifactHash = "a".repeat(64),
            grantDigest = "b".repeat(64),
            authoritySubjectId = authoritySubjectId,
        )
        return WorkflowEntity(
            id = definition.id,
            name = definition.name,
            description = definition.description,
            enabled = false,
            definitionJson = requireNotNull(WorkflowJson.encodeForLearned(definition)),
            createdAtMs = definition.createdAtMs,
            updatedAtMs = definition.updatedAtMs,
            stateVersion = 1L,
            origin = WorkflowOrigin.LEARNED.name,
            sourceCandidateId = candidateId,
            sourceArtifactHash = definition.sourceArtifactHash,
            grantDigest = definition.grantDigest,
            authoringAssistantId = assistantId,
            capabilitySnapshotJson = "[\"tool.show_toast\"]",
            toolSchemaFingerprintsJson =
                WorkflowToolSchemaSnapshot.canonicalProjection(definition.actions),
        )
    }
}
