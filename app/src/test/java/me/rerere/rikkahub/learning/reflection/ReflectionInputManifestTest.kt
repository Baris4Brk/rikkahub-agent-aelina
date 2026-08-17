package me.rerere.rikkahub.learning.reflection

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.episode.LearningEpisodeStatus
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.trace.TraceActionType
import me.rerere.rikkahub.learning.trace.TraceFeature
import me.rerere.rikkahub.learning.trace.TraceMetric
import me.rerere.rikkahub.learning.trace.TraceOutcomeClass
import me.rerere.rikkahub.learning.trace.TraceSanitizationResult
import me.rerere.rikkahub.learning.trace.TraceSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflectionInputManifestTest {
    private val stream = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val scope = LearningScope.Assistant(
        Uuid.parse("00000000-0000-0000-0000-000000000002"),
    )
    private val episode = requireNotNull(EpisodeId.parseOrNull("episode-v1:${"a".repeat(64)}"))
    private val source = LearningSourceRef(
        sourceKind = LearningSourceKind.CONVERSATION_MESSAGE,
        sourceId = "message-authority-v1",
        sourceRevision = 7,
        missingRevisionReason = null,
        databaseStreamId = stream,
        scope = scope,
        occurredAtMs = 11,
    )

    @Test
    fun identicalProviderBytesAndEvidenceProduceTheSameManifestIdentity() {
        val feature = feature()
        val first = compose(feature)
        val second = compose(feature.copy())

        assertEquals(first.inputId, second.inputId)
        assertEquals(first.payloadJson, second.payloadJson)
        assertTrue(first.inputId.startsWith("reflection-input-v2:"))
    }

    @Test
    fun everyProviderVisibleFeatureMutationChangesTheManifestIdentity() {
        val base = feature()
        val baseId = compose(base).inputId
        val variants = listOf(
            base.copy(canonicalActionName = "safe.other"),
            base.copy(toolSchemaFingerprint = "c".repeat(64)),
            base.copy(outcomeClass = TraceOutcomeClass.FAILURE),
            base.copy(errorCode = "SAFE_FAILURE"),
            base.copy(stateSummary = summary("state changed")),
            base.copy(observationSummary = summary("observation changed")),
            base.copy(inputTokens = TraceMetric.Known(12L)),
            base.copy(outputTokens = TraceMetric.Known(13L)),
            base.copy(toolCallCount = TraceMetric.Known(2)),
            base.copy(retryCount = TraceMetric.Known(1)),
            base.copy(durationMs = TraceMetric.Known(14L)),
        )

        variants.forEach { variant -> assertNotEquals(baseId, compose(variant).inputId) }
    }

    @Test
    fun evidenceRevisionChangesIdentityWithoutLeakingAuthorityIdsIntoPayload() {
        val original = compose(feature())
        val revisedSource = source.copy(sourceRevision = 8)
        val revised = compose(feature().copy(sources = listOf(revisedSource)))

        assertNotEquals(original.inputId, revised.inputId)
        assertTrue("message-authority-v1" !in original.payloadJson)
        assertTrue(scope.storageId !in original.payloadJson)
    }

    private fun compose(feature: TraceFeature): ReflectionInputBundle =
        (ReflectionInputComposer.compose(
            episodeId = episode,
            episodeStatus = LearningEpisodeStatus.SUCCESS,
            features = listOf(feature),
        ) as ReflectionInputComposeResult.Composed).input

    private fun feature() = TraceFeature(
        episodeId = episode,
        sequence = 1,
        sources = listOf(source),
        actionType = TraceActionType.TOOL,
        canonicalActionName = "safe.tool",
        toolSchemaFingerprint = "b".repeat(64),
        outcomeClass = TraceOutcomeClass.SUCCESS,
        errorCode = null,
        stateSummary = null,
        observationSummary = null,
        inputTokens = TraceMetric.Known(10L),
        outputTokens = TraceMetric.Known(11L),
        toolCallCount = TraceMetric.Known(1),
        retryCount = TraceMetric.Known(0),
        durationMs = TraceMetric.Known(12L),
        producerIdentity = "trace-v1",
        quality = null,
        createdAtMs = 12,
    )

    private fun summary(value: String) =
        (TraceSanitizer.sanitize(value) as TraceSanitizationResult.Accepted).summary
}
