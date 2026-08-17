package me.rerere.rikkahub.learning.handoff

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.dao.LearningOutboxDao
import me.rerere.rikkahub.data.db.entity.LearningOutboxEntity
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class LearningOutboxProtocolTest {
    @Test
    fun `authority transaction is mandatory`() {
        expectFailure<IllegalStateException> {
            requireLearningOutboxAuthorityTransaction(false)
        }
        requireLearningOutboxAuthorityTransaction(true)
    }

    @Test
    fun `writer resolves the only valid sentinel and rejects mixed streams`() = runBlocking {
        val healthy = FakeOutboxDao(
            sentinels = listOf(sentinel()),
            streams = listOf(STREAM.toString()),
        )
        assertEquals(STREAM, readHealthyLearningOutboxStream(healthy))

        val mixed = FakeOutboxDao(
            sentinels = listOf(sentinel()),
            streams = listOf(STREAM.toString(), OTHER_STREAM.toString()),
        )
        assertEquals(
            LearningOutboxHealthError.MIXED_STREAMS,
            expectSuspendFailure<LearningOutboxHealthException> {
                readHealthyLearningOutboxStream(mixed)
            }.errorCode,
        )
    }

    @Test
    fun `sentinel damage is classified without including stored identifiers`() = runBlocking {
        val missing = FakeOutboxDao(sentinels = emptyList(), streams = emptyList())
        assertEquals(
            LearningOutboxHealthError.MISSING_STREAM_SENTINEL,
            expectSuspendFailure<LearningOutboxHealthException> {
                readHealthyLearningOutboxStream(missing)
            }.errorCode,
        )

        val incompatible = FakeOutboxDao(
            sentinels = listOf(sentinel().copy(eventSchemaVersion = 2)),
            streams = listOf(STREAM.toString()),
        )
        val failure = expectSuspendFailure<LearningOutboxHealthException> {
            readHealthyLearningOutboxStream(incompatible)
        }
        assertEquals(LearningOutboxHealthError.INVALID_STREAM_SENTINEL, failure.errorCode)
        assertTrue(STREAM.toString() !in failure.message.orEmpty())
    }

    @Test
    fun `sentinel lookup never swallows cancellation`() = runBlocking {
        val cancellation = CancellationException("cancel-test")
        val dao = FakeOutboxDao(
            sentinels = emptyList(),
            streams = emptyList(),
            sentinelFailure = cancellation,
        )
        assertSame(
            cancellation,
            expectSuspendFailure<CancellationException> {
                readHealthyLearningOutboxStream(dao)
            },
        )
    }

    @Test
    fun `business appender rejects stream init and caller supplied lineage`() = runBlocking {
        val dao = FakeOutboxDao(sentinels = listOf(sentinel()), streams = listOf(STREAM.toString()))
        expectSuspendFailure<IllegalArgumentException> {
            appendValidatedBusinessDraft(dao, STREAM) {
                LearningOutboxDraft(
                    streamId = it,
                    eventCode = LearningEventCode(LearningEventType.STREAM_INIT.name, 1),
                    source = null,
                    correlation = LearningCorrelation(),
                    terminalStateCode = null,
                    createdAtMs = 1,
                )
            }
        }
        expectSuspendFailure<IllegalArgumentException> {
            appendValidatedBusinessDraft(dao, STREAM) {
                terminalDraft(OTHER_STREAM)
            }
        }
        Unit
    }

    @Test
    fun `insert ignore is duplicate only after every authority field matches`() = runBlocking {
        val draft = terminalDraft(STREAM)
        val exact = draft.toEntity().copy(seq = 9)
        val duplicateDao = FakeOutboxDao(
            sentinels = listOf(sentinel()),
            streams = listOf(STREAM.toString()),
            insertedSequence = -1,
            existing = exact,
        )
        assertEquals(
            LearningOutboxAppendResult.Duplicate(9),
            appendValidatedBusinessDraft(duplicateDao, STREAM) { draft },
        )

        val conflictDao = FakeOutboxDao(
            sentinels = listOf(sentinel()),
            streams = listOf(STREAM.toString()),
            insertedSequence = -1,
            existing = exact.copy(createdAtMs = exact.createdAtMs + 1),
        )
        expectSuspendFailure<LearningHandoffIdentityConflictException> {
            appendValidatedBusinessDraft(conflictDao, STREAM) { draft }
        }
        Unit
    }

    @Test
    fun `reader decoder rejects wrong stream order bounds and malformed rows`() {
        val first = terminalDraft(STREAM).toEntity().copy(seq = 2)
        assertEquals(
            listOf(2L),
            decodeRows(listOf(first), STREAM, afterSequence = 1, throughSequence = 2)
                .map { it.outboxSeq },
        )
        expectFailure<LearningOutboxHealthException> {
            decodeRows(
                listOf(first.copy(streamId = OTHER_STREAM.toString())),
                STREAM,
                afterSequence = 1,
                throughSequence = 2,
            )
        }
        expectFailure<LearningOutboxHealthException> {
            decodeRows(listOf(first, first), STREAM, afterSequence = 1, throughSequence = 2)
        }
        expectFailure<LearningOutboxHealthException> {
            decodeRows(
                listOf(first.copy(eventId = "valid-but-wrong-id")),
                STREAM,
                afterSequence = 1,
                throughSequence = 2,
            )
        }
    }

    @Test
    fun `derived reset requires both privacy ports and cannot bypass their fences`() {
        val resetter = projectFile(
            "app/src/main/java/me/rerere/rikkahub/learning/handoff/LearningInboxBatchStore.kt",
            "src/main/java/me/rerere/rikkahub/learning/handoff/LearningInboxBatchStore.kt",
        ).readText().substringAfter("class LearningDerivedStateResetter(")
            .substringBefore("private fun LearningInboxEventEntity.toInitialJob")
        val constructor = resetter.substringBefore(") {")
        assertTrue(constructor.contains("ExactScopeLearnedWorkflowErasePort,"))
        assertTrue(constructor.contains("DurableLearnedWorkflowPrivacyPort,"))
        assertTrue(!constructor.contains("?"))
        assertTrue(!constructor.contains("= null"))
        assertTrue(!resetter.contains("learnedWorkflowErasePort == null"))
        assertTrue(!resetter.contains("durableLearnedWorkflowPrivacyPort == null"))
        assertTrue(resetter.contains(
            "durableLearnedWorkflowPrivacyPort.redactAllForDerivedReset(frozenNowMs)",
        ))
        assertTrue(resetter.contains("check(durableReceipt.complete)"))
        assertTrue(resetter.contains(
            "learnedWorkflowErasePort.redactAndFence(ids, frozenNowMs)",
        ))
        assertTrue(
            resetter.indexOf("fenceLearnedWorkflowsBeforeCandidateDelete(frozenNowMs)") in
                0 until resetter.indexOf("return database.withTransaction"),
        )
    }

    private fun sentinel(): LearningOutboxEntity = LearningOutboxDraft(
        streamId = STREAM,
        eventCode = LearningEventCode(LearningEventType.STREAM_INIT.name, 1),
        source = null,
        correlation = LearningCorrelation(),
        terminalStateCode = null,
        createdAtMs = 1,
    ).toEntity().copy(seq = 1)

    private fun terminalDraft(streamId: Uuid): LearningOutboxDraft = LearningOutboxDraft(
        streamId = streamId,
        eventCode = LearningEventCode(LearningEventType.COMMAND_TERMINAL.name, 1),
        source = LearningSourceRef(
            sourceKind = LearningSourceKind.COMMAND,
            sourceId = "command-1",
            sourceRevision = 2,
            missingRevisionReason = null,
            databaseStreamId = streamId,
            scope = LearningScope.Assistant(ASSISTANT),
            occurredAtMs = 10,
        ),
        correlation = LearningCorrelation(
            conversationId = "conversation-1",
            commandId = "command-1",
            lineageId = "lineage-1",
            branchAnchorMessageId = "message-root-1",
        ),
        terminalStateCode = "COMPLETED",
        createdAtMs = 10,
    )

    private class FakeOutboxDao(
        private val sentinels: List<LearningOutboxEntity>,
        private val streams: List<String>,
        private val insertedSequence: Long = 7,
        private val existing: LearningOutboxEntity? = null,
        private val sentinelFailure: Throwable? = null,
    ) : LearningOutboxDao {
        override suspend fun insertIgnore(event: LearningOutboxEntity): Long = insertedSequence

        override suspend fun findByEventId(eventId: String): LearningOutboxEntity? = existing

        override suspend fun listAfter(
            streamId: String,
            afterSeq: Long,
            limit: Int,
        ): List<LearningOutboxEntity> = emptyList()

        override suspend fun listAfterThrough(
            streamId: String,
            afterSeq: Long,
            throughSeq: Long,
            limit: Int,
        ): List<LearningOutboxEntity> = emptyList()

        override suspend fun headSequence(streamId: String): Long? = sentinels.maxOfOrNull { it.seq }

        override suspend fun listStreamSentinels(): List<LearningOutboxEntity> {
            sentinelFailure?.let { throw it }
            return sentinels
        }

        override suspend fun listDistinctStreamIds(): List<String> = streams

        override suspend fun deletePrunablePage(
            streamId: String,
            throughMinConsumerSeq: Long,
            createdBeforeMs: Long,
            keepFromSeq: Long,
            limit: Int,
        ): Int = 0
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        val thrown = try {
            block()
            null
        } catch (failure: Throwable) {
            failure
        }
        assertTrue("Expected ${T::class.java.simpleName}, got $thrown", thrown is T)
        return thrown as T
    }

    private suspend inline fun <reified T : Throwable> expectSuspendFailure(
        crossinline block: suspend () -> Unit,
    ): T {
        val thrown = try {
            block()
            null
        } catch (failure: Throwable) {
            failure
        }
        assertTrue("Expected ${T::class.java.simpleName}, got $thrown", thrown is T)
        return thrown as T
    }

    private fun projectFile(vararg candidates: String): File =
        requireNotNull(candidates.asSequence().map(::File).firstOrNull(File::isFile)) {
            "Cannot locate ${candidates.joinToString()} from ${File(".").absolutePath}"
        }

    private companion object {
        val STREAM: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000101")
        val OTHER_STREAM: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000102")
        val ASSISTANT: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000103")
    }
}
