package me.rerere.rikkahub.learning.storage

import me.rerere.rikkahub.learning.trace.TraceFeature
import me.rerere.rikkahub.learning.trace.TraceMetric

/** Deterministic domain-to-storage mapper; one action sequence may reference several sources. */
object TraceFeatureEntityMapper {
    fun map(feature: TraceFeature): List<LearningTraceFeatureEntity> =
        feature.sources.sortedWith(
            compareBy({ it.sourceKind.name }, { it.sourceId }, { it.sourceRevision }),
        ).mapIndexed { ordinal, source ->
            LearningTraceFeatureEntity(
                episodeId = feature.episodeId.value,
                sequence = feature.sequence,
                sourceOrdinal = ordinal,
                sourceType = source.sourceKind.name,
                sourceId = source.sourceId,
                sourceRevision = source.sourceRevision,
                missingRevisionReason = source.missingRevisionReason?.name,
                actionType = feature.actionType.name,
                actionName = feature.canonicalActionName,
                toolSchemaFingerprint = feature.toolSchemaFingerprint,
                outcomeClass = feature.outcomeClass.name,
                errorCode = feature.errorCode,
                stateSummary = feature.stateSummary?.value,
                observationSummary = feature.observationSummary?.value,
                inputTokenCount = feature.inputTokens.knownOrNull(),
                outputTokenCount = feature.outputTokens.knownOrNull(),
                toolCount = feature.toolCallCount.knownOrNull(),
                retryCount = feature.retryCount.knownOrNull(),
                durationMs = feature.durationMs.knownOrNull(),
                alpha = null,
                quality = feature.quality,
                featureSchemaIdentity = feature.producerIdentity ?: "trace-feature-v1",
                createdAtMs = feature.createdAtMs,
            )
        }
}

private fun <T> TraceMetric<T>.knownOrNull(): T? = (this as? TraceMetric.Known<T>)?.value
