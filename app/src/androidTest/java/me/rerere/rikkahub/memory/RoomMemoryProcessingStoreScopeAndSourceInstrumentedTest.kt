package me.rerere.rikkahub.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MemoryV2Dao
import me.rerere.rikkahub.data.db.entity.MemoryCandidateEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryEvidenceEntity
import me.rerere.rikkahub.data.db.entity.MemoryRelationCandidateEntity
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryRetriever
import me.rerere.rikkahub.data.repository.MemorySearchIndex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class RoomMemoryProcessingStoreScopeAndSourceInstrumentedTest {
    private lateinit var database: AppDatabase
    private lateinit var memoryDao: MemoryDAO
    private lateinit var memoryV2Dao: MemoryV2Dao
    private lateinit var store: RoomMemoryProcessingStore
    private val json = Json {}
    private var generatedId = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        memoryDao = database.memoryDao()
        memoryV2Dao = database.memoryV2Dao()
        store = RoomMemoryProcessingStore(
            database = database,
            memoryDao = memoryDao,
            memoryV2Dao = memoryV2Dao,
            retriever = MemoryRetriever(
                index = MemorySearchIndex { _, _, _ -> emptyList() },
            ),
            json = json,
            idGenerator = { "store-id-${generatedId++}" },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun mutationsAndCandidateReviewsCannotCrossAssistantOrGlobalScope() = runBlocking {
        val assistantMemory = insertMemory(scopeId = ASSISTANT_A, content = "assistant A memory")
        val globalMemory = insertMemory(scopeId = GLOBAL_SCOPE, content = "global memory")

        assertEquals(
            MemoryMutationResult.NotFound,
            updateMutation(assistantMemory.id, ASSISTANT_B, assistantMemory.revision),
        )
        assertEquals(
            MemoryMutationResult.NotFound,
            updateMutation(assistantMemory.id, GLOBAL_SCOPE, assistantMemory.revision),
        )
        assertEquals(
            MemoryMutationResult.NotFound,
            updateMutation(globalMemory.id, ASSISTANT_A, globalMemory.revision),
        )
        assertEquals(assistantMemory, requireMemory(assistantMemory.id, ASSISTANT_A))
        assertEquals(globalMemory, requireMemory(globalMemory.id, GLOBAL_SCOPE))

        val assistantToB = pendingCandidate(
            id = "candidate-assistant-to-b",
            scopeId = ASSISTANT_A,
            sourceConversationId = "scope-review-a-b",
            action = MemoryCandidateAction.UPDATE,
            targetIds = listOf(assistantMemory.id),
            expectedRevisions = listOf(assistantMemory.revision),
        )
        val assistantToGlobal = assistantToB.copy(
            id = "candidate-assistant-to-global",
            sourceConversationId = "scope-review-a-global",
        )
        val globalToAssistant = pendingCandidate(
            id = "candidate-global-to-assistant",
            scopeId = GLOBAL_SCOPE,
            sourceConversationId = "scope-review-global-a",
            action = MemoryCandidateAction.UPDATE,
            targetIds = listOf(globalMemory.id),
            expectedRevisions = listOf(globalMemory.revision),
        )
        memoryV2Dao.insertCandidate(assistantToB)
        memoryV2Dao.insertCandidate(assistantToGlobal)
        memoryV2Dao.insertCandidate(globalToAssistant)

        assertEquals(
            MemoryReviewResult.NotFound,
            store.review(
                MemoryReviewCommand.Accept(assistantToB.id, ASSISTANT_B),
                nowMs = 100L,
            ),
        )
        assertEquals(
            MemoryReviewResult.NotFound,
            store.review(
                MemoryReviewCommand.Accept(assistantToGlobal.id, GLOBAL_SCOPE),
                nowMs = 101L,
            ),
        )
        assertEquals(
            MemoryReviewResult.NotFound,
            store.review(
                MemoryReviewCommand.Accept(globalToAssistant.id, ASSISTANT_A),
                nowMs = 102L,
            ),
        )

        assertEquals(
            MemoryCandidateStatus.PENDING_REVIEW.name,
            memoryV2Dao.findCandidate(assistantToB.id, ASSISTANT_A)?.status,
        )
        assertEquals(
            MemoryCandidateStatus.PENDING_REVIEW.name,
            memoryV2Dao.findCandidate(assistantToGlobal.id, ASSISTANT_A)?.status,
        )
        assertEquals(
            MemoryCandidateStatus.PENDING_REVIEW.name,
            memoryV2Dao.findCandidate(globalToAssistant.id, GLOBAL_SCOPE)?.status,
        )
    }

    @Test
    fun acceptedUpdateUsesNewCandidateLineageAndDeletedConversationCannotBeRestoredOrInjected() =
        runBlocking {
            val oldIdentity = sourceIdentity(OLD_CONVERSATION, "old-update-message", 'a', "old-update")
            val target = insertMemory(
                scopeId = ASSISTANT_A,
                content = "old update content",
                sourceConversationId = OLD_CONVERSATION,
                sourceIdentities = listOf(oldIdentity),
            )
            val newIdentity = sourceIdentity(
                UPDATE_CONVERSATION,
                UPDATE_MESSAGE,
                'b',
                "new-update",
            )
            val candidate = pendingCandidate(
                id = "candidate-update",
                scopeId = ASSISTANT_A,
                sourceConversationId = UPDATE_CONVERSATION,
                action = MemoryCandidateAction.UPDATE,
                targetIds = listOf(target.id),
                expectedRevisions = listOf(target.revision),
                title = "Updated title",
                content = "updated content from the new conversation",
                evidenceMessageIds = listOf(UPDATE_MESSAGE),
            )
            memoryV2Dao.insertCandidate(candidate)
            memoryV2Dao.insertEvidence(listOf(candidateEvidence(candidate.id, newIdentity)))

            assertEquals(
                MemoryReviewResult.Applied(target.id),
                store.review(
                    MemoryReviewCommand.Accept(candidate.id, ASSISTANT_A),
                    nowMs = 200L,
                ),
            )
            val accepted = requireMemory(target.id, ASSISTANT_A)
            assertEquals(UPDATE_CONVERSATION, accepted.sourceConversationId)
            assertEquals(listOf(UPDATE_MESSAGE), decodeStrings(accepted.sourceMessageIdsJson))
            assertEquals(
                listOf(newIdentity),
                json.decodeFromString<List<MemorySourceIdentity>>(accepted.sourceIdentitiesJson),
            )
            assertFalse(accepted.sourceIdentitiesJson.contains(OLD_CONVERSATION))

            assertEquals(
                1,
                store.invalidateSourceConversation(
                    scopeId = ASSISTANT_A,
                    conversationId = UPDATE_CONVERSATION,
                    nowMs = 300L,
                ),
            )
            val invalidated = requireMemory(target.id, ASSISTANT_A)
            assertNull(
                "A formally sourced row must fail closed after its accepted source is deleted",
                memoryDao.getActiveConfirmedMemoryById(target.id, ASSISTANT_A, nowMs = 300L),
            )
            assertRestoreRevisionRejected(
                memory = invalidated,
                revision = accepted.revision,
                nowMs = 301L,
            )
        }

    @Test
    fun acceptedMergeUsesNewCandidateLineageAndDeletedVersionCannotBeRestoredOrInjected() =
        runBlocking {
            val first = insertMemory(
                scopeId = ASSISTANT_A,
                content = "first merge source",
                sourceConversationId = OLD_CONVERSATION,
                sourceIdentities = listOf(
                    sourceIdentity(OLD_CONVERSATION, "old-merge-one", 'c', "old-merge-one"),
                ),
            )
            val second = insertMemory(
                scopeId = ASSISTANT_A,
                content = "second merge source",
                sourceConversationId = OLD_CONVERSATION,
                sourceIdentities = listOf(
                    sourceIdentity(OLD_CONVERSATION, "old-merge-two", 'd', "old-merge-two"),
                ),
            )
            val newIdentity = sourceIdentity(
                MERGE_CONVERSATION,
                MERGE_MESSAGE,
                'e',
                "new-merge",
            )
            val candidate = pendingCandidate(
                id = "candidate-merge",
                scopeId = ASSISTANT_A,
                sourceConversationId = MERGE_CONVERSATION,
                action = MemoryCandidateAction.MERGE,
                targetIds = listOf(first.id, second.id),
                expectedRevisions = listOf(first.revision, second.revision),
                title = "Merged title",
                content = "merged content supported by the new conversation",
                evidenceMessageIds = listOf(MERGE_MESSAGE),
            )
            memoryV2Dao.insertCandidate(candidate)
            memoryV2Dao.insertEvidence(listOf(candidateEvidence(candidate.id, newIdentity)))

            assertEquals(
                MemoryReviewResult.Applied(first.id),
                store.review(
                    MemoryReviewCommand.Accept(candidate.id, ASSISTANT_A),
                    nowMs = 400L,
                ),
            )
            val accepted = requireMemory(first.id, ASSISTANT_A)
            assertEquals(MERGE_CONVERSATION, accepted.sourceConversationId)
            assertEquals(listOf(MERGE_MESSAGE), decodeStrings(accepted.sourceMessageIdsJson))
            assertEquals(
                listOf(newIdentity),
                json.decodeFromString<List<MemorySourceIdentity>>(accepted.sourceIdentitiesJson),
            )
            assertEquals(
                MemoryLifecycleStatus.ARCHIVED.name,
                requireMemory(second.id, ASSISTANT_A).lifecycleStatus,
            )

            assertEquals(
                1,
                store.invalidateSourceVersions(
                    scopeId = ASSISTANT_A,
                    conversationId = MERGE_CONVERSATION,
                    sourceVersions = setOf(
                        MemorySourceVersion(MERGE_MESSAGE, newIdentity.consumedTextDigest),
                    ),
                    nowMs = 500L,
                ),
            )
            val invalidated = requireMemory(first.id, ASSISTANT_A)
            assertNull(
                "A formally sourced row must fail closed after its exact source version is gone",
                memoryDao.getActiveConfirmedMemoryById(first.id, ASSISTANT_A, nowMs = 500L),
            )
            assertRestoreRevisionRejected(
                memory = invalidated,
                revision = accepted.revision,
                nowMs = 501L,
            )
        }

    @Test
    fun lastTruthRelationEvidenceCreatesOneScopedReconciliationAndReviewReactivatesIt() =
        runBlocking {
            val source = insertMemory(scopeId = ASSISTANT_A, content = "newer corrected fact")
            val target = insertMemory(scopeId = ASSISTANT_A, content = "older superseded fact")
            val relationCandidate = MemoryRelationCandidateEntity(
                id = "relation-candidate",
                batchId = "relation-batch",
                sourceMemoryId = source.id,
                targetMemoryId = target.id,
                relationType = MemoryRelationType.SUPERSEDES.name,
                weight = 0.9f,
                description = "newer fact supersedes older fact",
                evidenceMessageIdsJson = json.encodeToString(
                    listOf(RELATION_MESSAGE_ONE, RELATION_MESSAGE_TWO),
                ),
                status = MemoryRelationCandidateStatus.PENDING.name,
                createdAtMs = 600L,
                scopeId = ASSISTANT_A,
                createdByAssistantId = ASSISTANT_A,
                sourceExpectedRevision = source.revision,
                targetExpectedRevision = target.revision,
                updatedAtMs = 600L,
            )
            val relationIdentityOne = sourceIdentity(
                RELATION_CONVERSATION,
                RELATION_MESSAGE_ONE,
                'f',
                "relation-evidence-one",
            )
            val relationIdentityTwo = sourceIdentity(
                RELATION_CONVERSATION,
                RELATION_MESSAGE_TWO,
                '1',
                "relation-evidence-two",
            )
            memoryV2Dao.insertRelationCandidate(relationCandidate)
            memoryV2Dao.insertEvidence(
                listOf(
                    relationEvidence(relationCandidate.id, relationIdentityOne),
                    relationEvidence(relationCandidate.id, relationIdentityTwo),
                ),
            )

            val initialReview = store.reviewRelation(
                MemoryRelationReviewCommand.Accept(relationCandidate.id, ASSISTANT_A),
                nowMs = 601L,
            )
            assertTrue(initialReview is MemoryRelationReviewResult.Applied)
            val linkId = (initialReview as MemoryRelationReviewResult.Applied).linkId
            assertEquals(
                MemoryTruthStatus.SUPERSEDED.name,
                requireMemory(target.id, ASSISTANT_A).truthStatus,
            )

            assertEquals(
                0,
                store.invalidateSourceMessages(
                    scopeId = ASSISTANT_A,
                    conversationId = RELATION_CONVERSATION,
                    messageIds = setOf(RELATION_MESSAGE_ONE),
                    nowMs = 650L,
                ),
            )
            assertEquals(
                MemoryLinkLifecycleStatus.ACTIVE.name,
                memoryV2Dao.findLink(linkId, ASSISTANT_A)?.lifecycleStatus,
            )
            assertTrue(memoryV2Dao.observePendingRelationCandidates(ASSISTANT_A).first().isEmpty())

            assertEquals(
                1,
                store.invalidateSourceMessages(
                    scopeId = ASSISTANT_A,
                    conversationId = RELATION_CONVERSATION,
                    messageIds = setOf(RELATION_MESSAGE_TWO),
                    nowMs = 700L,
                ),
            )
            assertEquals(
                MemoryLinkLifecycleStatus.INVALIDATED.name,
                memoryV2Dao.findLink(linkId, ASSISTANT_A)?.lifecycleStatus,
            )
            val pending = memoryV2Dao.observePendingRelationCandidates(ASSISTANT_A).first()
            assertEquals(1, pending.size)
            val reconciliation = pending.single()
            assertEquals(linkId, reconciliation.resolvedLinkId)
            assertEquals(
                "RELATION_SOURCE_INVALIDATED_REVIEW_REQUIRED",
                reconciliation.resolutionError,
            )

            assertEquals(
                MemoryRelationReviewResult.NotFound,
                store.reviewRelation(
                    MemoryRelationReviewCommand.Accept(reconciliation.id, ASSISTANT_B),
                    nowMs = 701L,
                ),
            )
            assertEquals(
                MemoryRelationReviewResult.Applied(linkId),
                store.reviewRelation(
                    MemoryRelationReviewCommand.Accept(reconciliation.id, ASSISTANT_A),
                    nowMs = 702L,
                ),
            )
            assertEquals(
                MemoryLinkLifecycleStatus.ACTIVE.name,
                memoryV2Dao.findLink(linkId, ASSISTANT_A)?.lifecycleStatus,
            )
            assertEquals(1, memoryV2Dao.countValidEvidenceForLink(linkId))
            assertEquals(
                1,
                countRows(
                    "SELECT COUNT(*) FROM memory_evidence " +
                        "WHERE link_id = ? AND quality = 'USER_REVIEWED_RELATION' " +
                        "AND conversation_id = '__relation_user_review__' AND excerpt = ''",
                    arrayOf<Any?>(linkId),
                ),
            )

            store.invalidateSourceMessages(
                scopeId = ASSISTANT_A,
                conversationId = RELATION_CONVERSATION,
                messageIds = setOf(RELATION_MESSAGE_TWO),
                nowMs = 800L,
            )
            assertTrue(memoryV2Dao.observePendingRelationCandidates(ASSISTANT_A).first().isEmpty())
            assertEquals(
                1,
                countRows(
                    "SELECT COUNT(*) FROM memory_relation_candidates " +
                        "WHERE batch_id LIKE 'source-reconciliation-%'",
                ),
            )
        }

    private suspend fun updateMutation(
        memoryId: Int,
        expectedScopeId: String,
        expectedRevision: Int,
    ): MemoryMutationResult = store.mutate(
        MemoryMutationCommand.Update(
            memoryId = memoryId,
            expectedScopeId = expectedScopeId,
            expectedRevision = expectedRevision,
            content = "scope crossing update must not apply",
            approvalSource = MemoryApprovalSource.MEMORY_TOOL,
        ),
        nowMs = 50L,
    )

    private suspend fun insertMemory(
        scopeId: String,
        content: String,
        sourceConversationId: String? = null,
        sourceIdentities: List<MemorySourceIdentity> = emptyList(),
    ): MemoryEntity {
        val memory = MemoryEntity(
            assistantId = scopeId,
            title = content,
            content = content,
            contentHash = memoryContentHash(content),
            createdAtMs = 1L,
            updatedAtMs = 1L,
            sourceConversationId = sourceConversationId,
            sourceMessageIdsJson = json.encodeToString(sourceIdentities.map { it.messageId }),
            sourceIdentitiesJson = json.encodeToString(sourceIdentities),
            lifecycleStatus = MemoryLifecycleStatus.ACTIVE.name,
            truthStatus = MemoryTruthStatus.CONFIRMED.name,
            approvalSource = MemoryApprovalSource.AUTO_SAFE.name,
            revision = 1,
        )
        return memory.copy(id = memoryDao.insertMemory(memory).toInt())
    }

    private fun pendingCandidate(
        id: String,
        scopeId: String,
        sourceConversationId: String,
        action: MemoryCandidateAction,
        targetIds: List<Int>,
        expectedRevisions: List<Int>,
        title: String = "Candidate title",
        content: String = "candidate content long enough for reviewed validation",
        evidenceMessageIds: List<String> = emptyList(),
    ) = MemoryCandidateEntity(
        id = id,
        scopeId = scopeId,
        assistantId = ASSISTANT_A,
        sourceConversationId = sourceConversationId,
        captureIdsJson = "[]",
        action = action.name,
        targetMemoryIdsJson = json.encodeToString(targetIds),
        expectedRevisionsJson = json.encodeToString(expectedRevisions),
        title = title,
        content = content,
        memoryKind = MemoryKind.OTHER.name,
        tagsJson = "[]",
        importance = 0.5f,
        confidence = 0.9f,
        riskFlagsJson = "[]",
        reason = "instrumented contract",
        evidenceMessageIdsJson = json.encodeToString(evidenceMessageIds),
        status = MemoryCandidateStatus.PENDING_REVIEW.name,
        createdAtMs = 10L,
        updatedAtMs = 10L,
    )

    private fun candidateEvidence(
        candidateId: String,
        identity: MemorySourceIdentity,
    ) = MemoryEvidenceEntity(
        id = "evidence-candidate-$candidateId",
        candidateId = candidateId,
        conversationId = identity.conversationId,
        messageId = identity.messageId,
        role = identity.role.name,
        excerpt = "candidate source excerpt",
        contentHash = identity.consumedTextDigest,
        capturedAtMs = 20L,
        evidenceGroupId = identity.evidenceGroupId,
        sourceDigest = identity.consumedTextDigest,
        sourceKind = identity.sourceKind.name,
    )

    private fun relationEvidence(
        relationCandidateId: String,
        identity: MemorySourceIdentity,
    ) = MemoryEvidenceEntity(
        id = "evidence-relation-$relationCandidateId-${identity.messageId}",
        relationCandidateId = relationCandidateId,
        conversationId = identity.conversationId,
        messageId = identity.messageId,
        role = identity.role.name,
        excerpt = "relation source excerpt",
        contentHash = identity.consumedTextDigest,
        capturedAtMs = 600L,
        evidenceGroupId = identity.evidenceGroupId,
        sourceDigest = identity.consumedTextDigest,
        sourceKind = identity.sourceKind.name,
    )

    private fun sourceIdentity(
        conversationId: String,
        messageId: String,
        digestCharacter: Char,
        evidenceGroupId: String,
    ) = MemorySourceIdentity(
        conversationId = conversationId,
        messageId = messageId,
        role = MemorySourceRole.USER,
        consumedTextDigest = digestCharacter.toString().repeat(64),
        evidenceGroupId = evidenceGroupId,
    )

    private suspend fun requireMemory(id: Int, scopeId: String): MemoryEntity =
        requireNotNull(memoryDao.getMemoryById(id, scopeId))

    private suspend fun assertRestoreRevisionRejected(
        memory: MemoryEntity,
        revision: Int,
        nowMs: Long,
    ) {
        val result = store.mutate(
            MemoryMutationCommand.RestoreRevision(
                memoryId = memory.id,
                expectedScopeId = memory.assistantId,
                expectedCurrentRevision = memory.revision,
                revision = revision,
                approvalSource = MemoryApprovalSource.USER_REVIEWED,
            ),
            nowMs = nowMs,
        )
        assertTrue(result is MemoryMutationResult.Rejected)
        assertEquals("memory_revision_empty", (result as MemoryMutationResult.Rejected).code)
    }

    private fun decodeStrings(raw: String): List<String> = json.decodeFromString(raw)

    private fun countRows(
        sql: String,
        args: Array<Any?> = emptyArray(),
    ): Int = database.openHelper.readableDatabase.query(sql, args).use { cursor ->
        assertTrue(cursor.moveToFirst())
        cursor.getInt(0)
    }

    private companion object {
        const val ASSISTANT_A = "00000000-0000-0000-0000-00000000000a"
        const val ASSISTANT_B = "00000000-0000-0000-0000-00000000000b"
        const val GLOBAL_SCOPE = MemoryRepository.GLOBAL_MEMORY_ID
        const val OLD_CONVERSATION = "old-conversation"
        const val UPDATE_CONVERSATION = "new-update-conversation"
        const val UPDATE_MESSAGE = "new-update-message"
        const val MERGE_CONVERSATION = "new-merge-conversation"
        const val MERGE_MESSAGE = "new-merge-message"
        const val RELATION_CONVERSATION = "relation-conversation"
        const val RELATION_MESSAGE_ONE = "relation-message-one"
        const val RELATION_MESSAGE_TWO = "relation-message-two"
    }
}
