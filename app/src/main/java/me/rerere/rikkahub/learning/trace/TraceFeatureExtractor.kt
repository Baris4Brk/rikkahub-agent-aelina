package me.rerere.rikkahub.learning.trace

import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.model.LearningSourceRef

data class TraceFeatureInput(
    val episodeId: EpisodeId,
    val sequence: Long,
    val sources: List<LearningSourceRef>,
    val actionType: TraceActionType,
    val canonicalActionName: String? = null,
    val toolSchemaFingerprint: String? = null,
    val outcomeClass: TraceOutcomeClass,
    val errorCode: String? = null,
    val redactedStateSummaryCandidate: String? = null,
    val redactedObservationSummaryCandidate: String? = null,
    val inputTokens: TraceMetric<Long> = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
    val outputTokens: TraceMetric<Long> = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
    val toolCallCount: TraceMetric<Int> = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
    val retryCount: TraceMetric<Int> = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
    val durationMs: TraceMetric<Long> = TraceMetric.Unknown(TraceUnknownReason.NOT_OBSERVED),
    val producerIdentity: String? = null,
    val quality: Double? = null,
    val createdAtMs: Long,
)

enum class TraceFeatureExtractionFailure {
    UNSAFE_STATE_SUMMARY,
    UNSAFE_OBSERVATION_SUMMARY,
    INVALID_FEATURE,
}

sealed interface TraceFeatureExtractionResult {
    data class Extracted(val feature: TraceFeature) : TraceFeatureExtractionResult
    data class Rejected(val failure: TraceFeatureExtractionFailure) : TraceFeatureExtractionResult
}

object TraceFeatureExtractor {
    fun extract(input: TraceFeatureInput): TraceFeatureExtractionResult {
        val state = when (val candidate = input.redactedStateSummaryCandidate) {
            null -> null
            else -> when (val result = TraceSanitizer.sanitize(candidate)) {
                is TraceSanitizationResult.Accepted -> result.summary
                is TraceSanitizationResult.Rejected ->
                    return TraceFeatureExtractionResult.Rejected(
                        TraceFeatureExtractionFailure.UNSAFE_STATE_SUMMARY,
                    )
            }
        }
        val observation = when (val candidate = input.redactedObservationSummaryCandidate) {
            null -> null
            else -> when (val result = TraceSanitizer.sanitize(candidate)) {
                is TraceSanitizationResult.Accepted -> result.summary
                is TraceSanitizationResult.Rejected ->
                    return TraceFeatureExtractionResult.Rejected(
                        TraceFeatureExtractionFailure.UNSAFE_OBSERVATION_SUMMARY,
                    )
            }
        }
        return try {
            TraceFeatureExtractionResult.Extracted(
                TraceFeature(
                    episodeId = input.episodeId,
                    sequence = input.sequence,
                    sources = input.sources,
                    actionType = input.actionType,
                    canonicalActionName = input.canonicalActionName,
                    toolSchemaFingerprint = input.toolSchemaFingerprint,
                    outcomeClass = input.outcomeClass,
                    errorCode = input.errorCode,
                    stateSummary = state,
                    observationSummary = observation,
                    inputTokens = input.inputTokens,
                    outputTokens = input.outputTokens,
                    toolCallCount = input.toolCallCount,
                    retryCount = input.retryCount,
                    durationMs = input.durationMs,
                    producerIdentity = input.producerIdentity,
                    quality = input.quality,
                    createdAtMs = input.createdAtMs,
                ),
            )
        } catch (_: IllegalArgumentException) {
            TraceFeatureExtractionResult.Rejected(TraceFeatureExtractionFailure.INVALID_FEATURE)
        }
    }
}
