package me.rerere.rikkahub.learning.reflection

import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.learning.episode.EpisodeId
import me.rerere.rikkahub.learning.episode.LearningEpisodeStatus
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.trace.TraceFeature
import me.rerere.rikkahub.learning.trace.TraceMetric

const val REFLECTION_INPUT_SCHEMA_VERSION = 2
const val REFLECTION_INPUT_MAX_FEATURES = 64
const val REFLECTION_INPUT_MAX_EVIDENCE = 32
const val REFLECTION_INPUT_MAX_UTF8_BYTES = 96 * 1_024

data class ReflectionInputBundle(
    val inputId: String,
    val episodeId: EpisodeId,
    val allowedEvidence: Map<String, LearningSourceRef>,
    val payloadJson: String,
) {
    init {
        require(inputId.matches(Regex("reflection-input-v2:[0-9a-f]{64}")))
        require(allowedEvidence.isNotEmpty() && allowedEvidence.size <= REFLECTION_INPUT_MAX_EVIDENCE)
        require(allowedEvidence.keys.toList() == allowedEvidence.keys.sorted())
        require(allowedEvidence.keys.all { it.matches(Regex("E[1-9][0-9]?")) })
        require(allowedEvidence.values.distinct().size == allowedEvidence.size)
        require(payloadJson.toByteArray(StandardCharsets.UTF_8).size <= REFLECTION_INPUT_MAX_UTF8_BYTES)
    }

    override fun toString(): String =
        "ReflectionInputBundle(evidence=${allowedEvidence.size}, payload=<redacted>, ids=<redacted>)"
}

sealed interface ReflectionInputComposeResult {
    data class Composed(val input: ReflectionInputBundle) : ReflectionInputComposeResult
    data class Rejected(val failure: ReflectionInputFailure) : ReflectionInputComposeResult
}

enum class ReflectionInputFailure {
    NO_FEATURES,
    TOO_MANY_FEATURES,
    WRONG_EPISODE,
    NO_VERSIONED_EVIDENCE,
    TOO_MANY_EVIDENCE,
    PAYLOAD_TOO_LARGE,
}

object ReflectionInputComposer {
    fun compose(
        episodeId: EpisodeId,
        episodeStatus: LearningEpisodeStatus,
        features: List<TraceFeature>,
    ): ReflectionInputComposeResult {
        if (features.isEmpty()) return ReflectionInputComposeResult.Rejected(ReflectionInputFailure.NO_FEATURES)
        if (features.size > REFLECTION_INPUT_MAX_FEATURES) {
            return ReflectionInputComposeResult.Rejected(ReflectionInputFailure.TOO_MANY_FEATURES)
        }
        if (features.any { it.episodeId != episodeId }) {
            return ReflectionInputComposeResult.Rejected(ReflectionInputFailure.WRONG_EPISODE)
        }
        val sources = features.flatMap { it.sources }
            .filter(LearningSourceRef::eligibleForPersistentPolicyEvidence)
            .distinct()
            .sortedWith(SOURCE_ORDER)
        if (sources.isEmpty()) {
            return ReflectionInputComposeResult.Rejected(ReflectionInputFailure.NO_VERSIONED_EVIDENCE)
        }
        if (sources.size > REFLECTION_INPUT_MAX_EVIDENCE) {
            return ReflectionInputComposeResult.Rejected(ReflectionInputFailure.TOO_MANY_EVIDENCE)
        }
        val aliases = sources.mapIndexed { index, source -> "E${index + 1}" to source }.toMap()
        val aliasBySource = aliases.entries.associate { (alias, source) -> source to alias }
        val featurePayload = buildJsonArray {
            features.sortedBy { it.sequence }.forEach { feature ->
                add(feature.toJson(aliasBySource))
            }
        }
        val payloadCore = buildJsonObject {
            put("schema_version", REFLECTION_INPUT_SCHEMA_VERSION)
            put("episode_status", episodeStatus.name)
            put("features", featurePayload)
        }
        val evidenceManifestDigest = LearningCanonicalId.digest(
            domainVersion = "reflection-evidence-manifest-v2",
            fields = sources.flatMap { source ->
                listOf(
                    source.sourceKind.name,
                    source.sourceId,
                    source.sourceRevision?.toString(),
                    source.missingRevisionReason?.name,
                    source.databaseStreamId.toString(),
                    source.scope.kind.name,
                    source.scope.storageId,
                    source.occurredAtMs.toString(),
                )
            },
        )
        val inputId = "reflection-input-v2:" + LearningCanonicalId.digest(
            domainVersion = "reflection-provider-input-v2",
            fields = listOf(
                episodeId.value,
                evidenceManifestDigest,
                payloadCore.toString(),
            ),
        )
        val payload = buildJsonObject {
            put("schema_version", REFLECTION_INPUT_SCHEMA_VERSION)
            put("input_id", inputId)
            put("episode_status", episodeStatus.name)
            put("features", featurePayload)
        }.toString()
        if (payload.toByteArray(StandardCharsets.UTF_8).size > REFLECTION_INPUT_MAX_UTF8_BYTES) {
            return ReflectionInputComposeResult.Rejected(ReflectionInputFailure.PAYLOAD_TOO_LARGE)
        }
        return ReflectionInputComposeResult.Composed(
            ReflectionInputBundle(inputId, episodeId, aliases, payload),
        )
    }

    private fun TraceFeature.toJson(aliasBySource: Map<LearningSourceRef, String>): JsonObject =
        buildJsonObject {
            put("seq", sequence)
            put("action_type", actionType.name)
            put("action_name", canonicalActionName?.let(::JsonPrimitive) ?: JsonNull)
            put("tool_schema_fingerprint", toolSchemaFingerprint?.let(::JsonPrimitive) ?: JsonNull)
            put("outcome_class", outcomeClass.name)
            put("error_code", errorCode?.let(::JsonPrimitive) ?: JsonNull)
            put("state_summary", stateSummary?.value?.let(::JsonPrimitive) ?: JsonNull)
            put("observation_summary", observationSummary?.value?.let(::JsonPrimitive) ?: JsonNull)
            put("input_tokens", inputTokens.toLongJson())
            put("output_tokens", outputTokens.toLongJson())
            put("tool_call_count", toolCallCount.toIntJson())
            put("retry_count", retryCount.toIntJson())
            put("duration_ms", durationMs.toLongJson())
            put(
                "evidence",
                JsonArray(sources.mapNotNull(aliasBySource::get).map(::JsonPrimitive)),
            )
        }

    private fun TraceMetric<Long>.toLongJson(): JsonObject = when (this) {
        is TraceMetric.Known -> buildJsonObject {
            put("known", true)
            put("value", value)
        }
        is TraceMetric.Unknown -> buildJsonObject {
            put("known", false)
            put("reason", reason.name)
        }
    }

    private fun TraceMetric<Int>.toIntJson(): JsonObject = when (this) {
        is TraceMetric.Known -> buildJsonObject {
            put("known", true)
            put("value", value)
        }
        is TraceMetric.Unknown -> buildJsonObject {
            put("known", false)
            put("reason", reason.name)
        }
    }

    private val SOURCE_ORDER = compareBy<LearningSourceRef>(
        { it.sourceKind.ordinal },
        { it.sourceId },
        { it.sourceRevision },
    )
}
