package me.rerere.rikkahub.learning.trace

import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.model.LearningSourceRef

enum class TraceActionType {
    COMMAND,
    PROVIDER_ATTEMPT,
    TOOL,
    APPROVAL,
    RESUME,
    RETRY,
    OTHER,
}

enum class TraceOutcomeClass {
    SUCCESS,
    PARTIAL,
    FAILURE,
    CANCELLED,
    TIMEOUT,
    CENSORED,
    UNKNOWN,
}

enum class TraceUnknownReason {
    NOT_OBSERVED,
    RETENTION_GAP,
    PROCESS_RESTART,
    SOURCE_UNAVAILABLE,
    CENSORED,
}

sealed interface TraceMetric<out T> {
    data class Known<T>(val value: T) : TraceMetric<T>
    data class Unknown(val reason: TraceUnknownReason) : TraceMetric<Nothing>
}

/** Content-free or explicitly sanitized feature row; raw prompts/args/output cannot enter it. */
data class TraceFeature(
    val episodeId: EpisodeId,
    val sequence: Long,
    val sources: List<LearningSourceRef>,
    val actionType: TraceActionType,
    val canonicalActionName: String?,
    val toolSchemaFingerprint: String?,
    val outcomeClass: TraceOutcomeClass,
    val errorCode: String?,
    val stateSummary: SanitizedTraceSummary?,
    val observationSummary: SanitizedTraceSummary?,
    val inputTokens: TraceMetric<Long>,
    val outputTokens: TraceMetric<Long>,
    val toolCallCount: TraceMetric<Int>,
    val retryCount: TraceMetric<Int>,
    val durationMs: TraceMetric<Long>,
    val producerIdentity: String?,
    val quality: Double?,
    val createdAtMs: Long,
) {
    init {
        require(sequence >= 0L)
        require(sources.isNotEmpty() && sources.size <= 16)
        require(sources.distinct().size == sources.size)
        require(canonicalActionName == null || canonicalActionName.matches(SAFE_ACTION_NAME))
        require(toolSchemaFingerprint == null || toolSchemaFingerprint.matches(LOWER_SHA256))
        require((canonicalActionName == null) == (actionType != TraceActionType.TOOL)) {
            "Only tool features carry a canonical action name"
        }
        require((toolSchemaFingerprint == null) == (actionType != TraceActionType.TOOL)) {
            "Only tool features carry a schema fingerprint"
        }
        require(errorCode == null || errorCode.matches(SAFE_ERROR_CODE))
        requireMetric(inputTokens) { it >= 0L }
        requireMetric(outputTokens) { it >= 0L }
        requireMetric(toolCallCount) { it in 0..1_024 }
        requireMetric(retryCount) { it in 0..128 }
        requireMetric(durationMs) { it in 0L..24L * 60L * 60L * 1_000L }
        require(producerIdentity == null || producerIdentity.matches(SAFE_PRODUCER_IDENTITY))
        require(quality == null || quality.isFinite() && quality in 0.0..1.0)
        require(createdAtMs >= 0L)
    }

    override fun toString(): String =
        "TraceFeature(action=$actionType, outcome=$outcomeClass, sequence=$sequence, " +
            "summary=${stateSummary != null || observationSummary != null}, sources=${sources.size}, " +
            "ids=<redacted>)"
}

private inline fun <T> requireMetric(metric: TraceMetric<T>, predicate: (T) -> Boolean) {
    if (metric is TraceMetric.Known) require(predicate(metric.value)) { "Invalid trace metric" }
}

private val SAFE_ACTION_NAME = Regex("[a-z][a-z0-9_.-]{0,95}")
private val SAFE_ERROR_CODE = Regex("[A-Z][A-Z0-9_]{0,63}")
private val SAFE_PRODUCER_IDENTITY = Regex("[a-z0-9][a-z0-9._-]{0,95}")
private val LOWER_SHA256 = Regex("[0-9a-f]{64}")
