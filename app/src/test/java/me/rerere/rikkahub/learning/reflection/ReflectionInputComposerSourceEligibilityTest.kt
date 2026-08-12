package me.rerere.rikkahub.learning.reflection

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.episode.LearningEpisodeStatus
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.model.MissingSourceRevisionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.learning.trace.TraceActionType
import me.rerere.rikkahub.learning.trace.TraceFeature
import me.rerere.rikkahub.learning.trace.TraceMetric
import me.rerere.rikkahub.learning.trace.TraceOutcomeClass
import me.rerere.rikkahub.learning.trace.TraceUnknownReason

class ReflectionInputComposerSourceEligibilityTest {
    @Test
    fun executionObservationMayEnterRedactedFeaturesButNeverEvidenceAllowlist() {
        val stream = Uuid.parse("00000000-0000-0000-0000-000000000001")
        val scope = LearningScope.Assistant(
            Uuid.parse("00000000-0000-0000-0000-000000000002"),
        )
        val episode = requireNotNull(EpisodeId.parseOrNull("episode-v1:${"a".repeat(64)}"))
        val conversation = LearningSourceRef(
            LearningSourceKind.CONVERSATION_MESSAGE,
            "message-source",
            1,
            null,
            stream,
            scope,
            1,
        )
        val execution = LearningSourceRef(
            LearningSourceKind.EXECUTION_EVENT,
            "execution-source",
            null,
            MissingSourceRevisionReason.RETENTION_GAP,
            stream,
            scope,
            2,
        )
        val result = ReflectionInputComposer.compose(
            episodeId = episode,
            episodeStatus = LearningEpisodeStatus.SUCCESS,
            features = listOf(
                feature(episode, 1, TraceActionType.COMMAND, conversation),
                feature(episode, 2, TraceActionType.TOOL, execution),
            ),
        ) as ReflectionInputComposeResult.Composed

        assertEquals(listOf(conversation), result.input.allowedEvidence.values.toList())
        assertTrue("execution-source" !in result.input.payloadJson)
    }

    private fun feature(
        episode: EpisodeId,
        sequence: Long,
        type: TraceActionType,
        source: LearningSourceRef,
    ) = TraceFeature(
        episodeId = episode,
        sequence = sequence,
        sources = listOf(source),
        actionType = type,
        canonicalActionName = "safe.tool".takeIf { type == TraceActionType.TOOL },
        toolSchemaFingerprint = "b".repeat(64).takeIf { type == TraceActionType.TOOL },
        outcomeClass = TraceOutcomeClass.SUCCESS,
        errorCode = null,
        stateSummary = null,
        observationSummary = null,
        inputTokens = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
        outputTokens = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
        toolCallCount = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
        retryCount = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
        durationMs = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
        producerIdentity = "trace-v1",
        quality = null,
        createdAtMs = sequence,
    )
}
