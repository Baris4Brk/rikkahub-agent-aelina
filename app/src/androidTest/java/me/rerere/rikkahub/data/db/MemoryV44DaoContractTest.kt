package me.rerere.rikkahub.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.MemoryV2Dao
import me.rerere.rikkahub.data.db.entity.MemoryCaptureEntity
import me.rerere.rikkahub.data.db.entity.MemoryEntity
import me.rerere.rikkahub.data.db.entity.MemoryEvidenceEntity
import me.rerere.rikkahub.data.db.entity.MemoryRevisionEntity
import me.rerere.rikkahub.data.db.entity.MemorySourceTombstoneEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class MemoryV44DaoContractTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: MemoryV2Dao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.memoryV2Dao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun captureIdentity_isUniqueWithinScopeButIndependentAcrossAssistantAndGlobalScopes() =
        runBlocking {
            val assistantCapture = capture(id = "assistant-capture", scopeId = "assistant-a")
            val globalCapture = capture(id = "global-capture", scopeId = "__global__")

            assertTrue(dao.insertCapture(assistantCapture) != -1L)
            assertTrue(dao.insertCapture(globalCapture) != -1L)

            val duplicateInAssistantScope = dao.insertCapture(
                assistantCapture.copy(id = "assistant-capture-duplicate"),
            )
            assertEquals(-1L, duplicateInAssistantScope)

            assertEquals(
                "assistant-capture",
                dao.findCaptureByTurn(
                    scopeId = "assistant-a",
                    conversationId = SOURCE_CONVERSATION,
                    assistantMessageId = SOURCE_ASSISTANT_MESSAGE,
                    captureSource = CAPTURE_SOURCE,
                )?.id,
            )
            assertEquals(
                "global-capture",
                dao.findCaptureByTurn(
                    scopeId = "__global__",
                    conversationId = SOURCE_CONVERSATION,
                    assistantMessageId = SOURCE_ASSISTANT_MESSAGE,
                    captureSource = CAPTURE_SOURCE,
                )?.id,
            )
        }

    @Test
    fun sourceTombstone_isIdempotentForOneDigestButKeepsDifferentMessageVersions() = runBlocking {
        val versionOne = tombstone(sourceDigest = "digest-v1", tombstonedAtMs = 100L)

        assertTrue(dao.insertSourceTombstones(listOf(versionOne)).single() != -1L)
        assertEquals(
            -1L,
            dao.insertSourceTombstones(
                listOf(versionOne.copy(reasonCode = "REPLAY", tombstonedAtMs = 200L)),
            ).single(),
        )
        assertTrue(
            dao.insertSourceTombstones(
                listOf(tombstone(sourceDigest = "digest-v2", tombstonedAtMs = 300L)),
            ).single() != -1L,
        )

        val persisted = dao.getSourceTombstones(
            scopeId = "assistant-a",
            conversationId = SOURCE_CONVERSATION,
        )
        assertEquals(2, persisted.size)
        assertEquals(setOf("digest-v1", "digest-v2"), persisted.map { it.sourceDigest }.toSet())
        val persistedVersionOne = persisted.single { it.sourceDigest == "digest-v1" }
        assertEquals("SOURCE_MESSAGE_DELETED", persistedVersionOne.reasonCode)
        assertEquals(100L, persistedVersionOne.tombstonedAtMs)
    }

    @Test
    fun validEvidenceCount_rejectsWholeEvidenceGroupWhenOneMemberIsSourceDeleted() = runBlocking {
        val memoryId = insertMemory(scopeId = "assistant-a")
        val groupId = "evidence-group"
        val evidence = listOf(
            evidence(
                id = "evidence-user",
                memoryId = memoryId,
                messageId = "message-user",
                sourceDigest = "digest-user",
                role = "USER",
                groupId = groupId,
            ),
            evidence(
                id = "evidence-assistant",
                memoryId = memoryId,
                messageId = "message-assistant",
                sourceDigest = "digest-assistant",
                role = "ASSISTANT",
                groupId = groupId,
            ),
        )
        assertTrue(dao.insertEvidence(evidence).all { it != -1L })
        assertEquals(1, dao.countValidEvidence(memoryId))

        assertEquals(
            1,
            dao.invalidateEvidenceForSourceVersion(
                scopeId = "assistant-a",
                conversationId = SOURCE_CONVERSATION,
                messageId = "message-user",
                sourceDigest = "digest-user",
            ),
        )
        assertEquals(0, dao.countValidEvidence(memoryId))
    }

    @Test
    fun retentionScrub_removesRawCaptureTextAndSourceIdentitiesTogether() = runBlocking {
        val capture = capture(
            id = "processed-capture",
            scopeId = "assistant-a",
            state = "PROCESSED",
            processedAtMs = 100L,
            sourceIdentitiesJson = SOURCE_IDENTITIES_JSON,
        )
        assertTrue(dao.insertCapture(capture) != -1L)

        assertEquals(
            1,
            dao.purgeProcessedCapturePayloads(
                processedBeforeMs = 100L,
                nowMs = 1_000L,
            ),
        )
        val scrubbed = dao.findCaptureByTurn(
            scopeId = "assistant-a",
            conversationId = SOURCE_CONVERSATION,
            assistantMessageId = SOURCE_ASSISTANT_MESSAGE,
            captureSource = CAPTURE_SOURCE,
        )
        assertNotNull(scrubbed)
        assertEquals("", scrubbed?.userText)
        assertEquals("", scrubbed?.assistantText)
        assertEquals("[]", scrubbed?.sourceIdentitiesJson)
        assertEquals(1_000L, scrubbed?.payloadPurgedAtMs)
    }

    @Test
    fun revisionTombstone_removesSnapshotsAndAllSourceIdentityPayloadsTogether() = runBlocking {
        val memoryId = insertMemory(scopeId = "assistant-a")
        dao.insertRevision(
            MemoryRevisionEntity(
                id = "revision-1",
                memoryId = memoryId,
                revision = 1,
                operation = "UPDATE",
                beforeSnapshotJson = "{\"content\":\"before\"}",
                afterSnapshotJson = "{\"content\":\"after\"}",
                actor = "SYSTEM",
                sourceConversationId = SOURCE_CONVERSATION,
                sourceMessageIdsJson = "[\"source-message\"]",
                sourceIdentitiesJson = SOURCE_IDENTITIES_JSON,
                createdAtMs = 100L,
            ),
        )

        assertEquals(
            1,
            dao.tombstoneRevisionPayload(
                revisionId = "revision-1",
                memoryId = memoryId,
                reasonCode = "SOURCE_MESSAGE_DELETED",
            ),
        )
        val tombstoned = dao.findRevision(
            memoryId = memoryId,
            revision = 1,
            scopeId = "assistant-a",
        )
        assertNotNull(tombstoned)
        assertNull(tombstoned?.beforeSnapshotJson)
        assertNull(tombstoned?.afterSnapshotJson)
        assertNull(tombstoned?.sourceConversationId)
        assertEquals("[]", tombstoned?.sourceMessageIdsJson)
        assertEquals("[]", tombstoned?.sourceIdentitiesJson)
        assertEquals("SOURCE_MESSAGE_DELETED", tombstoned?.reasonCode)
    }

    private fun capture(
        id: String,
        scopeId: String,
        state: String = "PENDING",
        processedAtMs: Long? = null,
        sourceIdentitiesJson: String = "[]",
    ) = MemoryCaptureEntity(
        id = id,
        assistantId = "assistant-a",
        scopeId = scopeId,
        conversationId = SOURCE_CONVERSATION,
        userMessageId = "source-user-message",
        assistantMessageId = SOURCE_ASSISTANT_MESSAGE,
        origin = "APP_UI",
        captureSource = CAPTURE_SOURCE,
        autoSaveMode = "SAFE_NEW_ONLY",
        userText = "raw user text",
        assistantText = "raw assistant text",
        sourceIdentitiesJson = sourceIdentitiesJson,
        state = state,
        createdAtMs = 10L,
        updatedAtMs = 100L,
        processedAtMs = processedAtMs,
    )

    private fun tombstone(
        sourceDigest: String,
        tombstonedAtMs: Long,
    ) = MemorySourceTombstoneEntity(
        scopeId = "assistant-a",
        conversationId = SOURCE_CONVERSATION,
        sourceKind = "MESSAGE",
        sourceId = "source-message",
        sourceDigest = sourceDigest,
        reasonCode = "SOURCE_MESSAGE_DELETED",
        tombstonedAtMs = tombstonedAtMs,
    )

    private suspend fun insertMemory(scopeId: String): Int = db.memoryDao().insertMemory(
        MemoryEntity(
            assistantId = scopeId,
            content = "memory content",
            contentHash = "memory-hash",
            sourceIdentitiesJson = SOURCE_IDENTITIES_JSON,
        ),
    ).toInt()

    private fun evidence(
        id: String,
        memoryId: Int,
        messageId: String,
        sourceDigest: String,
        role: String,
        groupId: String,
    ) = MemoryEvidenceEntity(
        id = id,
        memoryId = memoryId,
        conversationId = SOURCE_CONVERSATION,
        messageId = messageId,
        role = role,
        excerpt = "source excerpt",
        contentHash = "evidence-content-hash",
        capturedAtMs = 100L,
        evidenceGroupId = groupId,
        sourceDigest = sourceDigest,
        sourceKind = "TEXT",
    )

    private companion object {
        const val SOURCE_CONVERSATION = "conversation-1"
        const val SOURCE_ASSISTANT_MESSAGE = "assistant-message-1"
        const val CAPTURE_SOURCE = "AUTOMATIC_TURN"
        const val SOURCE_IDENTITIES_JSON =
            "[{\"conversationId\":\"conversation-1\",\"messageId\":\"source-message\"," +
                "\"role\":\"USER\",\"consumedTextDigest\":\"digest\"," +
                "\"evidenceGroupId\":\"evidence-group\",\"sourceKind\":\"TEXT\"}]"
    }
}
