package me.rerere.rikkahub.learning.exposure

import me.rerere.rikkahub.learning.episode.LearningCompletionKind
import me.rerere.rikkahub.learning.model.LearningEventDecodeState
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.storage.LearningEpisodeBoundaryReason
import me.rerere.rikkahub.learning.storage.LearningEpisodeEntity
import me.rerere.rikkahub.learning.storage.LearningInboxEventEntity
import me.rerere.rikkahub.learning.storage.StoredLearningEpisodeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyExposureOutcomeLinkPolicyTest {
    @Test
    fun `final saved maps only the exact committed assistant message authority`() {
        val plan = requireNotNull(
            PolicyExposureOutcomeLinkPolicy.plan(terminalEvent(), terminalEpisode()),
        )

        assertEquals(LearningSourceKind.CONVERSATION_MESSAGE, plan.authority.sourceKind)
        assertEquals(RESULT_MESSAGE_ID, plan.authority.sourceId)
        assertEquals(RESULT_MESSAGE_REVISION, plan.authority.sourceRevision)
        assertEquals(LOGICAL_RUN_ID, plan.logicalRunId)
        assertEquals(30L, plan.linkObservedAtMs)
    }

    @Test
    fun `provider failure maps to exact command authority without claiming utility`() {
        val event = terminalEvent().copy(
            terminalState = "FAILED",
            completionKind = LearningCompletionKind.FAILED_OTHER.name,
            messageId = null,
            messageRevision = null,
        )
        val episode = terminalEpisode().copy(
            resultAssistantMessageId = null,
            resultAssistantMessageRevision = null,
            status = StoredLearningEpisodeStatus.FAILURE.name,
            boundaryReason = LearningEpisodeBoundaryReason.UNKNOWN.name,
        )

        val plan = requireNotNull(PolicyExposureOutcomeLinkPolicy.plan(event, episode))
        assertEquals(LearningSourceKind.COMMAND, plan.authority.sourceKind)
        assertEquals(FINAL_COMMAND_ID, plan.authority.sourceId)
        assertEquals(FINAL_COMMAND_REVISION, plan.authority.sourceRevision)
    }

    @Test
    fun `censored and superseded retain command outcome without deciding utility`() {
        val mappings = listOf(
            LearningCompletionKind.CENSORED_CANCELLED to Pair(
                StoredLearningEpisodeStatus.CENSORED,
                LearningEpisodeBoundaryReason.STOPPED,
            ),
            LearningCompletionKind.SUPERSEDED_REGENERATE to Pair(
                StoredLearningEpisodeStatus.SUPERSEDED,
                LearningEpisodeBoundaryReason.REGENERATED_BRANCH,
            ),
        )

        mappings.forEach { (kind, expectedEpisode) ->
            val event = terminalEvent().copy(
                terminalState = "CANCELLED",
                completionKind = kind.name,
                messageId = null,
                messageRevision = null,
            )
            val episode = terminalEpisode().copy(
                resultAssistantMessageId = null,
                resultAssistantMessageRevision = null,
                status = expectedEpisode.first.name,
                boundaryReason = expectedEpisode.second.name,
            )
            val plan = requireNotNull(PolicyExposureOutcomeLinkPolicy.plan(event, episode))
            assertEquals(LearningSourceKind.COMMAND, plan.authority.sourceKind)
            assertEquals(FINAL_COMMAND_ID, plan.authority.sourceId)
            assertEquals(FINAL_COMMAND_REVISION, plan.authority.sourceRevision)
        }
    }

    @Test
    fun `waiting fast control and final-save-failed never form outcome links`() {
        val excluded = setOf(
            LearningCompletionKind.GENERATION_WAITING_APPROVAL,
            LearningCompletionKind.FAST_PATH_HANDLED,
            LearningCompletionKind.CONTROL_ONLY,
            LearningCompletionKind.FAILED_FINAL_SAVE,
        )

        excluded.forEach { kind ->
            val event = terminalEvent().copy(
                completionKind = kind.name,
                messageId = null,
                messageRevision = null,
            )
            val episode = terminalEpisode().copy(
                resultAssistantMessageId = null,
                resultAssistantMessageRevision = null,
                status = StoredLearningEpisodeStatus.UNKNOWN.name,
                boundaryReason = LearningEpisodeBoundaryReason.UNKNOWN.name,
            )
            assertNull(kind.name, PolicyExposureOutcomeLinkPolicy.plan(event, episode))
        }
    }

    @Test
    fun `stream replay episode run and source revision are all exact fences`() {
        val event = terminalEvent()
        val episode = terminalEpisode()

        assertNull(
            PolicyExposureOutcomeLinkPolicy.plan(
                event.copy(generationRunId = OTHER_LOGICAL_RUN_ID),
                episode,
            ),
        )
        assertNull(
            PolicyExposureOutcomeLinkPolicy.plan(
                event.copy(sourceRevision = FINAL_COMMAND_REVISION + 1),
                episode,
            ),
        )
        assertNull(
            PolicyExposureOutcomeLinkPolicy.plan(
                event.copy(replayGeneration = event.replayGeneration + 1),
                episode,
            ),
        )
        assertNull(
            PolicyExposureOutcomeLinkPolicy.plan(
                event.copy(eventTypeCode = "COMMAND_WAITING_APPROVAL"),
                episode,
            ),
        )
        assertTrue(PolicyExposureOutcomeLinkPolicy.plan(event, episode) != null)
    }

    private fun terminalEvent() = LearningInboxEventEntity(
        streamId = STREAM_ID,
        eventId = "terminal-event",
        outboxSeq = 2,
        eventTypeCode = "COMMAND_TERMINAL",
        eventSchemaVersion = 2,
        terminalState = "COMPLETED",
        decodeState = LearningEventDecodeState.KNOWN.name,
        interpretationVersion = 1,
        sourceType = LearningSourceKind.COMMAND.name,
        sourceId = FINAL_COMMAND_ID,
        sourceRevision = FINAL_COMMAND_REVISION,
        previousSourceRevision = 1,
        sourceState = "COMPLETED",
        missingRevisionReason = null,
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        conversationId = CONVERSATION_ID,
        conversationSourceRevision = CONVERSATION_REVISION,
        commandId = FINAL_COMMAND_ID,
        lineageId = LINEAGE_ID,
        parentCommandId = null,
        branchAnchorMessageId = BRANCH_MESSAGE_ID,
        branchAnchorMessageRevision = BRANCH_MESSAGE_REVISION,
        completionKind = LearningCompletionKind.GENERATION_FINAL_SAVED.name,
        generationRunId = LOGICAL_RUN_ID,
        executionId = null,
        toolCallId = null,
        toolName = null,
        toolSchemaFingerprint = null,
        messageId = RESULT_MESSAGE_ID,
        messageRevision = RESULT_MESSAGE_REVISION,
        occurredAtMs = 20,
        createdAtMs = 20,
        ingestedAtMs = 30,
        replayGeneration = 3,
    )

    private fun terminalEpisode() = LearningEpisodeEntity(
        id = EPISODE_ID,
        streamId = STREAM_ID,
        replayGeneration = 3,
        scopeKind = SCOPE.kind.name,
        scopeId = SCOPE.storageId,
        conversationId = CONVERSATION_ID,
        conversationRevision = CONVERSATION_REVISION,
        rootCommandId = LINEAGE_ID,
        rootCommandRevision = 1,
        finalCommandId = FINAL_COMMAND_ID,
        finalCommandRevision = FINAL_COMMAND_REVISION,
        lineageId = LINEAGE_ID,
        branchAnchorMessageId = BRANCH_MESSAGE_ID,
        branchAnchorMessageRevision = BRANCH_MESSAGE_REVISION,
        resultAssistantMessageId = RESULT_MESSAGE_ID,
        resultAssistantMessageRevision = RESULT_MESSAGE_REVISION,
        generationRunId = LOGICAL_RUN_ID,
        executionId = null,
        taskSignature = "task-signature-v1",
        status = StoredLearningEpisodeStatus.SUCCESS.name,
        boundaryReason = LearningEpisodeBoundaryReason.FINAL_SAVED.name,
        revision = 2,
        startedAtMs = 10,
        finalizedAtMs = 20,
        createdAtMs = 10,
        updatedAtMs = 20,
    )

    private companion object {
        const val STREAM_ID = "00000000-0000-0000-0000-000000000101"
        const val LOGICAL_RUN_ID = "00000000-0000-0000-0000-000000000102"
        const val OTHER_LOGICAL_RUN_ID = "00000000-0000-0000-0000-000000000104"
        const val EPISODE_ID =
            "episode-v1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val CONVERSATION_ID = "conversation-one"
        const val CONVERSATION_REVISION = 5L
        const val LINEAGE_ID = "root-command"
        const val FINAL_COMMAND_ID = "final-command"
        const val FINAL_COMMAND_REVISION = 2L
        const val BRANCH_MESSAGE_ID = "branch-message"
        const val BRANCH_MESSAGE_REVISION = 1L
        const val RESULT_MESSAGE_ID = "result-message"
        const val RESULT_MESSAGE_REVISION = 3L
        val SCOPE = LearningScope.Assistant(
            kotlin.uuid.Uuid.parse("00000000-0000-0000-0000-000000000103"),
        )
    }
}
