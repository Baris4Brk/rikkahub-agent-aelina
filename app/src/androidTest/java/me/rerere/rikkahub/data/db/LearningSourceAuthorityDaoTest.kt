package me.rerere.rikkahub.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.entity.LearningConversationSourceAuthorityEntity
import me.rerere.rikkahub.data.db.entity.LearningMessageSourceAuthorityEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class LearningSourceAuthorityDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun messageRevisionCas_isContiguousAndTombstoneCannotRevive() = runBlocking {
        val dao = db.learningSourceAuthorityDao()
        assertTrueInsert(
            dao.insertMessageInitialIgnore(
                message(revision = 1, previous = null, state = "ACTIVE", change = "CREATED"),
            ),
        )
        assertEquals(
            -1L,
            dao.insertMessageInitialIgnore(
                message(revision = 1, previous = null, state = "ACTIVE", change = "CREATED"),
            ),
        )
        assertEquals(1, updateMessage(expected = 1, next = 2, state = "SUPERSEDED"))
        assertEquals(0, updateMessage(expected = 1, next = 2, state = "ACTIVE"))
        assertEquals(0, updateMessage(expected = 2, next = 4, state = "ACTIVE"))
        assertEquals(1, updateMessage(expected = 2, next = 3, state = "TOMBSTONED"))
        assertEquals(0, updateMessage(expected = 3, next = 4, state = "ACTIVE"))

        val tombstone = requireNotNull(dao.findMessage(SCOPE_KIND, SCOPE_ID, MESSAGE_ID))
        assertEquals(3L, tombstone.sourceRevision)
        assertEquals(2L, tombstone.previousSourceRevision)
        assertEquals("TOMBSTONED", tombstone.sourceState)
        assertNull(tombstone.payloadIntegritySha256)
        assertEquals(1, dao.countMessagesForConversation(SCOPE_KIND, SCOPE_ID, CONVERSATION_ID))
        assertEquals(
            listOf(MESSAGE_ID),
            dao.listMessagesForConversationAfter(
                SCOPE_KIND,
                SCOPE_ID,
                CONVERSATION_ID,
                afterMessageId = "",
                limit = 10,
            ).map { it.messageId },
        )
    }

    @Test
    fun conversationRevisionCas_preservesTombstoneWithoutConversationForeignKey() = runBlocking {
        val dao = db.learningSourceAuthorityDao()
        assertTrueInsert(
            dao.insertConversationInitialIgnore(
                conversation(revision = 1, previous = null, state = "ACTIVE", change = "CREATED"),
            ),
        )
        assertTrueInsert(
            dao.insertConversationInitialIgnore(
                conversation(revision = 1, previous = null, state = "ACTIVE", change = "CREATED")
                    .copy(scopeKind = "AUTHORITY_SUBJECT", scopeId = "subject-a"),
            ),
        )
        assertEquals(2, dao.countConversationScopes(CONVERSATION_ID))
        assertEquals(
            listOf("ASSISTANT:$SCOPE_ID", "AUTHORITY_SUBJECT:subject-a"),
            dao.listConversationScopesAfter(
                conversationId = CONVERSATION_ID,
                afterScopeKind = "",
                afterScopeId = "",
                limit = 10,
            ).map { "${it.scopeKind}:${it.scopeId}" },
        )
        assertEquals(
            listOf("AUTHORITY_SUBJECT:subject-a"),
            dao.listConversationScopesAfter(
                conversationId = CONVERSATION_ID,
                afterScopeKind = "ASSISTANT",
                afterScopeId = SCOPE_ID,
                limit = 10,
            ).map { "${it.scopeKind}:${it.scopeId}" },
        )
        assertEquals(
            1,
            dao.updateConversationFenced(
                scopeKind = SCOPE_KIND,
                scopeId = SCOPE_ID,
                conversationId = CONVERSATION_ID,
                expectedRevision = 1,
                nextRevision = 2,
                assistantIdSnapshot = SCOPE_ID,
                sourceState = "TOMBSTONED",
                changeKind = "CONVERSATION_DELETED",
                branchHeadMessageId = null,
                branchHeadMessageRevision = null,
                occurredAtMs = 2,
                updatedAtMs = 2,
            ),
        )
        assertEquals(
            0,
            dao.updateConversationFenced(
                scopeKind = SCOPE_KIND,
                scopeId = SCOPE_ID,
                conversationId = CONVERSATION_ID,
                expectedRevision = 2,
                nextRevision = 3,
                assistantIdSnapshot = SCOPE_ID,
                sourceState = "ACTIVE",
                changeKind = "UPDATED",
                branchHeadMessageId = MESSAGE_ID,
                branchHeadMessageRevision = 2,
                occurredAtMs = 3,
                updatedAtMs = 3,
            ),
        )
        assertEquals(
            "TOMBSTONED",
            dao.findConversation(SCOPE_KIND, SCOPE_ID, CONVERSATION_ID)?.sourceState,
        )
    }

    private suspend fun updateMessage(
        expected: Long,
        next: Long,
        state: String,
    ): Int = db.learningSourceAuthorityDao().updateMessageFenced(
        scopeKind = SCOPE_KIND,
        scopeId = SCOPE_ID,
        conversationId = CONVERSATION_ID,
        messageId = MESSAGE_ID,
        expectedRevision = expected,
        nextRevision = next,
        messageRole = "ASSISTANT",
        sourceState = state,
        changeKind = when (state) {
            "SUPERSEDED" -> "BRANCH_SUPERSEDED"
            "TOMBSTONED" -> "DELETED"
            else -> "BRANCH_SELECTED"
        },
        payloadIntegritySha256 = if (state == "TOMBSTONED") null else DIGEST,
        occurredAtMs = next,
        updatedAtMs = next,
    )

    private fun message(
        revision: Long,
        previous: Long?,
        state: String,
        change: String,
    ) = LearningMessageSourceAuthorityEntity(
        scopeKind = SCOPE_KIND,
        scopeId = SCOPE_ID,
        conversationId = CONVERSATION_ID,
        messageId = MESSAGE_ID,
        messageRole = "ASSISTANT",
        sourceRevision = revision,
        previousSourceRevision = previous,
        sourceState = state,
        changeKind = change,
        payloadIntegritySha256 = if (state == "TOMBSTONED") null else DIGEST,
        occurredAtMs = revision,
        updatedAtMs = revision,
    )

    private fun conversation(
        revision: Long,
        previous: Long?,
        state: String,
        change: String,
    ) = LearningConversationSourceAuthorityEntity(
        scopeKind = SCOPE_KIND,
        scopeId = SCOPE_ID,
        conversationId = CONVERSATION_ID,
        assistantIdSnapshot = SCOPE_ID,
        sourceRevision = revision,
        previousSourceRevision = previous,
        sourceState = state,
        changeKind = change,
        branchHeadMessageId = MESSAGE_ID,
        branchHeadMessageRevision = revision,
        occurredAtMs = revision,
        updatedAtMs = revision,
    )

    private fun assertTrueInsert(rowId: Long) {
        check(rowId != -1L) { "Expected source authority insert" }
    }

    private companion object {
        const val SCOPE_KIND = "ASSISTANT"
        const val SCOPE_ID = "00000000-0000-0000-0000-000000000001"
        const val CONVERSATION_ID = "00000000-0000-0000-0000-000000000002"
        const val MESSAGE_ID = "00000000-0000-0000-0000-000000000003"
        const val DIGEST = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
