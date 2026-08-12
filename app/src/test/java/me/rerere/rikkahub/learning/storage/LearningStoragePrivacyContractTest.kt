package me.rerere.rikkahub.learning.storage

import me.rerere.rikkahub.data.db.entity.LearningOutboxEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LearningStoragePrivacyContractTest {
    @Test
    fun p0Rows_haveNoArbitraryPayloadOrRawContentField() {
        val forbiddenFragments = listOf(
            "payload",
            "json",
            "promptText",
            "rawPrompt",
            "messageText",
            "content",
            "reasoning",
            "toolArgs",
            "toolOutput",
            "uri",
            "path",
            "secret",
            "errorMessage",
        ).map(String::lowercase)
        val classes = listOf(
            LearningOutboxEntity::class.java,
            LearningInboxEventEntity::class.java,
            LearningStreamCheckpointEntity::class.java,
            LearningJobEntity::class.java,
            LearningEpisodeEntity::class.java,
            LearningTraceFeatureEntity::class.java,
            LearningEpisodeLessonEntity::class.java,
            LearningRewardWindowEntity::class.java,
            LearningSourceValidityEntity::class.java,
            LearningPolicyEntity::class.java,
            PolicyEvidenceEntity::class.java,
            PolicyRevisionEntity::class.java,
            PolicyLineageEntity::class.java,
        )
        classes.forEach { type ->
            type.declaredFields.forEach { field ->
                val name = field.name.lowercase()
                assertFalse(
                    "${type.simpleName}.${field.name} is an unbounded/private-data escape hatch",
                    forbiddenFragments.any(name::contains),
                )
            }
        }
    }

    @Test
    fun p0Rows_redactStableIdentifiersFromDefaultLogProjection() {
        val rows = listOf(
            outbox(),
            inbox(),
            job(),
            checkpoint(),
        )
        val privateValues = listOf(
            STREAM_ID,
            SCOPE_ID,
            "private-command",
            "private-event",
            "private-job",
            "private-dedupe",
            "PRIVATE_SECRET",
        )

        rows.forEach { row ->
            val rendered = row.toString()
            privateValues.forEach { privateValue ->
                assertFalse("${row::class.java.simpleName} leaked an identifier", privateValue in rendered)
            }
        }
    }

    @Test
    fun p0Rows_rejectImpossibleBasicValuesAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) { outbox().copy(seq = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            inbox().copy(ingestedAtMs = 0, createdAtMs = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            job().copy(state = LearningJobState.RUNNING.name)
        }
        assertThrows(IllegalArgumentException::class.java) {
            runningJob().copy(leaseWorkerId = "worker-safe-looking-but-not-a-uuid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            runningJob().copy(leaseProcessSessionId = NIL_UUID)
        }
        assertThrows(IllegalArgumentException::class.java) {
            checkpoint().copy(lastSeenHeadSeq = -1)
        }
    }

    private fun outbox() = LearningOutboxEntity(
        seq = 1,
        streamId = STREAM_ID,
        eventId = "private-event",
        eventType = "PRIVATE_SECRET",
        eventSchemaVersion = 1,
        terminalState = null,
        sourceType = "COMMAND",
        sourceId = "private-command",
        sourceRevision = 1,
        missingRevisionReason = null,
        scopeKind = "ASSISTANT",
        scopeId = SCOPE_ID,
        conversationId = "private-conversation",
        commandId = "private-command",
        lineageId = "private-lineage",
        parentCommandId = null,
        branchAnchorMessageId = "private-anchor",
        generationRunId = null,
        executionId = null,
        toolCallId = null,
        messageId = null,
        occurredAtMs = 1,
        createdAtMs = 1,
    )

    private fun inbox() = LearningInboxEventEntity(
        streamId = STREAM_ID,
        eventId = "private-event",
        outboxSeq = 1,
        eventTypeCode = "PRIVATE_SECRET",
        eventSchemaVersion = 1,
        terminalState = null,
        decodeState = "UNKNOWN_NO_JOB",
        interpretationVersion = 1,
        sourceType = "COMMAND",
        sourceId = "private-command",
        sourceRevision = 1,
        missingRevisionReason = null,
        scopeKind = "ASSISTANT",
        scopeId = SCOPE_ID,
        conversationId = "private-conversation",
        commandId = "private-command",
        lineageId = "private-lineage",
        parentCommandId = null,
        branchAnchorMessageId = "private-anchor",
        generationRunId = null,
        executionId = null,
        toolCallId = null,
        messageId = null,
        occurredAtMs = 1,
        createdAtMs = 1,
        ingestedAtMs = 1,
        replayGeneration = 0,
    )

    private fun job() = LearningJobEntity(
        id = "private-job",
        jobType = LearningJobType.ASSEMBLE_EPISODE_SHADOW.name,
        jobSchemaVersion = 1,
        dedupeKey = "private-dedupe",
        streamId = STREAM_ID,
        sourceEventId = "private-event",
        scopeKind = "ASSISTANT",
        scopeId = SCOPE_ID,
        state = LearningJobState.PENDING.name,
        priority = 0,
        attempts = 0,
        maxAttempts = 2,
        notBeforeMs = 1,
        leaseProcessSessionId = null,
        leaseWorkerId = null,
        leaseGeneration = 0,
        leaseUntilMs = null,
        lastErrorCode = null,
        createdAtMs = 1,
        updatedAtMs = 1,
        finishedAtMs = null,
        replayGeneration = 0,
    )

    private fun runningJob() = job().copy(
        state = LearningJobState.RUNNING.name,
        attempts = 1,
        leaseProcessSessionId = "00000000-0000-0000-0000-000000000010",
        leaseWorkerId = "00000000-0000-0000-0000-000000000011",
        leaseGeneration = 1,
        leaseUntilMs = 2,
    )

    private fun checkpoint() = LearningStreamCheckpointEntity(
        streamId = STREAM_ID,
        lastContiguousSeq = 0,
        lastSeenHeadSeq = 1,
        replayGeneration = 0,
        resetReason = LearningStreamResetReason.NEW_STREAM.name,
        bootstrapState = LearningBootstrapState.REQUIRED.name,
        bootstrapHeadSeq = 1,
        coverageStartMs = null,
        commandCoverageStartMs = null,
        executionCoverageStartMs = null,
        updatedAtMs = 1,
    )

    private companion object {
        const val STREAM_ID = "00000000-0000-0000-0000-000000000001"
        const val SCOPE_ID = "00000000-0000-0000-0000-000000000002"
        const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
    }
}
