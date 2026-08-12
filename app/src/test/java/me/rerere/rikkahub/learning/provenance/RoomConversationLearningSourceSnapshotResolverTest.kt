package me.rerere.rikkahub.learning.provenance

import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.authority.source.ConversationSourceSnapshotFactory
import me.rerere.rikkahub.data.db.entity.LearningConversationSourceAuthorityEntity
import me.rerere.rikkahub.data.db.entity.LearningMessageSourceAuthorityEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.learning.model.LearningFeatureCapabilities
import me.rerere.rikkahub.learning.model.LearningFeatureFlagPolicy
import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.LearningFeatureFlags
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.storage.LearningInboxEventEntity
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class RoomConversationLearningSourceSnapshotResolverTest {
    @Test
    fun `exact active selected message yields job local chars and wipes on close`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.resolver.resolve(request()) as LearningSourceSnapshotResult.Available
        var observed = ""
        result.snapshot.useText { observed = it }

        assertEquals("bounded authority text", observed)
        assertEquals("E1", result.snapshot.alias)
        result.snapshot.close()
        assertTrue(result.snapshot.isClearedForTest())
        // One transaction captures the payload and authority row; a second fail-closed
        // revalidation transaction fences changes that race with out-of-transaction decoding.
        assertEquals(2, fixture.transactions)
    }

    @Test
    fun `unknown revision and disabled flags fail before payload read`() = runBlocking {
        val fixture = Fixture(flags = DISABLED_FLAGS)
        val disabled = fixture.resolver.resolve(request())
        assertEquals(
            LearningSourceSnapshotResult.Unavailable(LearningSourceReadFailure.UNAVAILABLE),
            disabled,
        )
        assertEquals(0, fixture.payloadReads)

        val enabled = Fixture()
        val unknown = enabled.resolver.resolve(
            request().copy(
                source = SOURCE.copy(
                    sourceRevision = null,
                    missingRevisionReason =
                        me.rerere.rikkahub.learning.model.MissingSourceRevisionReason.RETENTION_GAP,
                ),
            ),
        )
        assertEquals(
            LearningSourceSnapshotResult.Unavailable(LearningSourceReadFailure.REVISION_UNKNOWN),
            unknown,
        )
        assertEquals(0, enabled.payloadReads)
    }

    @Test
    fun `stale superseded unselected and digest mismatch all fail closed`() = runBlocking {
        val stale = Fixture()
        assertUnavailable(
            stale.resolver.resolve(
                request().copy(source = SOURCE.copy(sourceRevision = 2L)),
            ),
            LearningSourceReadFailure.REVISION_UNKNOWN,
        )

        val superseded = Fixture(messageState = "SUPERSEDED")
        assertUnavailable(
            superseded.resolver.resolve(request()),
            LearningSourceReadFailure.REVISION_MISMATCH,
        )

        val unselected = Fixture(selectsTarget = false)
        assertUnavailable(unselected.resolver.resolve(request()), LearningSourceReadFailure.SNAPSHOT_MISMATCH)

        val corrupt = Fixture(messageDigest = "f".repeat(64))
        assertUnavailable(corrupt.resolver.resolve(request()), LearningSourceReadFailure.SNAPSHOT_MISMATCH)
    }

    @Test
    fun `max chars is strict and unsupported source body is unavailable`() = runBlocking {
        val fixture = Fixture()
        assertUnavailable(
            fixture.resolver.resolve(request(maxChars = 4)),
            LearningSourceReadFailure.TOO_LARGE,
        )
        assertUnavailable(
            fixture.resolver.resolve(
                request().copy(source = SOURCE.copy(sourceKind = LearningSourceKind.CONVERSATION)),
            ),
            LearningSourceReadFailure.UNAVAILABLE,
        )
    }

    @Test
    fun `integrity resolver returns only exact active message digest`() = runBlocking {
        val fixture = Fixture()
        assertEquals(DIGEST, fixture.resolver.resolveSha256(inbox()))
        assertNull(fixture.resolver.resolveSha256(inbox().copy(sourceState = "SUPERSEDED")))
        assertNull(
            fixture.resolver.resolveSha256(
                inbox().copy(sourceType = LearningSourceKind.CONVERSATION.name),
            ),
        )
        assertNull(Fixture(messageState = "SUPERSEDED").resolver.resolveSha256(inbox()))
    }

    private fun assertUnavailable(
        actual: LearningSourceSnapshotResult,
        expected: LearningSourceReadFailure,
    ) = assertEquals(LearningSourceSnapshotResult.Unavailable(expected), actual)

    private fun request(maxChars: Int = 100) = LearningSourceSnapshotRequest(
        source = SOURCE,
        expectedScope = SCOPE,
        maxChars = maxChars,
        frozenNowMs = 10L,
        expiresAtMs = 100L,
    )

    private fun inbox() = LearningInboxEventEntity(
        streamId = STREAM_ID,
        eventId = "source-event-1",
        outboxSeq = 1L,
        eventTypeCode = "SOURCE_INVALIDATED",
        eventSchemaVersion = 2,
        terminalState = null,
        decodeState = "KNOWN",
        interpretationVersion = 2,
        sourceType = LearningSourceKind.CONVERSATION_MESSAGE.name,
        sourceId = MESSAGE_ID,
        sourceRevision = 3L,
        previousSourceRevision = 2L,
        sourceState = "ACTIVE",
        missingRevisionReason = null,
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        conversationId = CONVERSATION_ID,
        conversationSourceRevision = 5L,
        commandId = null,
        lineageId = null,
        parentCommandId = null,
        branchAnchorMessageId = null,
        branchAnchorMessageRevision = null,
        completionKind = null,
        generationRunId = null,
        executionId = null,
        toolCallId = null,
        toolName = null,
        toolSchemaFingerprint = null,
        messageId = MESSAGE_ID,
        messageRevision = 3L,
        occurredAtMs = 10L,
        createdAtMs = 10L,
        ingestedAtMs = 11L,
        replayGeneration = 0L,
    )

    private class Fixture(
        flags: LearningFeatureFlagSource = ENABLED_FLAGS,
        messageState: String = "ACTIVE",
        messageDigest: String = DIGEST,
        selectsTarget: Boolean = true,
    ) {
        private val selectedMessage = TEST_MESSAGE.copy(
            id = Uuid.parse(if (selectsTarget) MESSAGE_ID else OTHER_MESSAGE_ID),
        )
        private val message = LearningMessageSourceAuthorityEntity(
            scopeKind = SCOPE.kind.name,
            scopeId = SCOPE.storageId,
            conversationId = CONVERSATION_ID,
            messageId = MESSAGE_ID,
            messageRole = "USER",
            sourceRevision = 3L,
            previousSourceRevision = 2L,
            sourceState = messageState,
            changeKind = if (messageState == "ACTIVE") "UPDATED" else "BRANCH_SUPERSEDED",
            payloadIntegritySha256 = messageDigest,
            occurredAtMs = 10L,
            updatedAtMs = 10L,
        )
        private val conversation = LearningConversationSourceAuthorityEntity(
            scopeKind = SCOPE.kind.name,
            scopeId = SCOPE.storageId,
            conversationId = CONVERSATION_ID,
            assistantIdSnapshot = ASSISTANT_ID,
            sourceRevision = 5L,
            previousSourceRevision = 4L,
            sourceState = "ACTIVE",
            changeKind = "UPDATED",
            branchHeadMessageId = MESSAGE_ID,
            branchHeadMessageRevision = 3L,
            occurredAtMs = 10L,
            updatedAtMs = 10L,
        )
        var transactions = 0
        var payloadReads = 0
        val resolver = RoomConversationLearningSourceSnapshotResolver(
            transactions = object : ConversationLearningSourceTransactionRunner {
                override suspend fun <T> inTransaction(block: suspend () -> T): T {
                    transactions++
                    return block()
                }
            },
            authority = object : LearningSourceAuthorityReadPort {
                override suspend fun findConversation(
                    scopeKind: String,
                    scopeId: String,
                    conversationId: String,
                ) = conversation.takeIf {
                    it.scopeKind == scopeKind && it.scopeId == scopeId &&
                        it.conversationId == conversationId
                }

                override suspend fun findMessageAtRevision(
                    scopeKind: String,
                    scopeId: String,
                    messageId: String,
                    sourceRevision: Long,
                ) = message.takeIf {
                    it.scopeKind == scopeKind && it.scopeId == scopeId &&
                        it.messageId == messageId && it.sourceRevision == sourceRevision
                }
            },
            payloads = object : ConversationMessagePayloadReadPort {
                override suspend fun conversationExists(conversationId: String): Boolean =
                    conversationId == CONVERSATION_ID

                override suspend fun findNodesContainingMessage(
                    conversationId: String,
                    messageId: String,
                ): List<MessageNodeEntity> {
                    payloadReads++
                    return listOf(
                        MessageNodeEntity(
                            id = "node-1",
                            conversationId = CONVERSATION_ID,
                            nodeIndex = 0,
                            messages = JsonInstant.encodeToString(listOf(selectedMessage)),
                            selectIndex = 0,
                        ),
                    )
                }

                override suspend fun selectedMessageIds(conversationId: String): List<String>? =
                    listOf(selectedMessage.id.toString())
            },
            featureFlags = flags,
        )
    }

    private companion object {
        const val STREAM_ID = "00000000-0000-0000-0000-000000000001"
        const val ASSISTANT_ID = "00000000-0000-0000-0000-000000000002"
        const val CONVERSATION_ID = "00000000-0000-0000-0000-000000000003"
        const val MESSAGE_ID = "00000000-0000-0000-0000-000000000004"
        const val OTHER_MESSAGE_ID = "00000000-0000-0000-0000-000000000005"
        val SCOPE = LearningScope.Assistant(Uuid.parse(ASSISTANT_ID))
        val SOURCE = LearningSourceRef(
            sourceKind = LearningSourceKind.CONVERSATION_MESSAGE,
            sourceId = MESSAGE_ID,
            sourceRevision = 3L,
            missingRevisionReason = null,
            databaseStreamId = Uuid.parse(STREAM_ID),
            scope = SCOPE,
            occurredAtMs = 10L,
        )
        // The writer hashes the exact persisted UIMessage, including its creation timestamp.
        // Reuse one instance so the fixture authority digest cannot drift from the JSON payload.
        val TEST_MESSAGE = UIMessage.user("bounded authority text").copy(id = Uuid.parse(MESSAGE_ID))
        val DIGEST = ConversationSourceSnapshotFactory.payloadIntegritySha256(TEST_MESSAGE)
        val ENABLED_FLAGS = LearningFeatureFlagSource {
            LearningFeatureFlagPolicy.resolve(
                LearningFeatureFlags(schemaReady = true, handoff = true, jobs = true),
                LearningFeatureCapabilities(schemaReady = true, typedJobExecutionReady = true),
            )
        }
        val DISABLED_FLAGS = LearningFeatureFlagSource {
            LearningFeatureFlagPolicy.resolve(LearningFeatureFlags())
        }
    }
}
