package me.rerere.rikkahub.learning.reflection

import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.learning.model.StrictLearningJsonEnvelope
import me.rerere.rikkahub.learning.model.StrictLearningJsonKeyScanner
import me.rerere.rikkahub.learning.trace.SanitizedTraceSummary
import me.rerere.rikkahub.learning.trace.TraceSanitizationResult
import me.rerere.rikkahub.learning.trace.TraceSanitizer

enum class EpisodeLessonType {
    SUCCESS_PATTERN,
    AVOID,
    FAILURE_MODE,
    UNKNOWN,
}

enum class ReflectionParseFailure {
    EMPTY,
    TOO_LARGE,
    DUPLICATE_KEY,
    INVALID_JSON,
    ROOT_NOT_OBJECT,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    WRONG_TYPE,
    SCHEMA_MISMATCH,
    INPUT_ID_MISMATCH,
    UNKNOWN_OPERATION,
    UNKNOWN_LESSON_TYPE,
    INVALID_QUALITY,
    EMPTY_EVIDENCE,
    TOO_MANY_EVIDENCE,
    UNKNOWN_EVIDENCE,
    DUPLICATE_EVIDENCE,
    UNSAFE_TEXT,
}

data class ValidatedEpisodeLessonDraft(
    val inputId: String,
    val lessonType: EpisodeLessonType,
    val trigger: SanitizedTraceSummary,
    val observation: SanitizedTraceSummary,
    val lesson: SanitizedTraceSummary,
    val boundary: SanitizedTraceSummary,
    val evidence: List<LearningSourceRef>,
    val quality: Double,
    val artifactHash: String,
) {
    init {
        require(evidence.isNotEmpty() && evidence.size <= 16)
        require(evidence.distinct().size == evidence.size)
        require(quality.isFinite() && quality in 0.0..1.0)
        require(artifactHash.matches(Regex("[0-9a-f]{64}")))
    }

    override fun toString(): String =
        "ValidatedEpisodeLessonDraft(type=$lessonType, evidence=${evidence.size}, " +
            "quality=$quality, text=<redacted>, ids=<redacted>)"
}

sealed interface ReflectionParseResult {
    data object Abstained : ReflectionParseResult
    data class Lesson(val draft: ValidatedEpisodeLessonDraft) : ReflectionParseResult
    data class Rejected(val failure: ReflectionParseFailure) : ReflectionParseResult
}

object ReflectionParser {
    private const val OUTPUT_SCHEMA_VERSION = 1
    private const val MAX_OUTPUT_BYTES = 16 * 1_024
    private val json = Json {
        isLenient = false
        ignoreUnknownKeys = false
        coerceInputValues = false
    }

    fun parse(raw: String, input: ReflectionInputBundle): ReflectionParseResult {
        if (raw.isBlank()) return ReflectionParseResult.Rejected(ReflectionParseFailure.EMPTY)
        if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_OUTPUT_BYTES) {
            return ReflectionParseResult.Rejected(ReflectionParseFailure.TOO_LARGE)
        }
        val document = StrictLearningJsonEnvelope.unwrapSingleDocument(raw)
            ?: return ReflectionParseResult.Rejected(ReflectionParseFailure.INVALID_JSON)
        when (StrictLearningJsonKeyScanner.scan(document)) {
            StrictLearningJsonKeyScanner.Result.DUPLICATE ->
                return ReflectionParseResult.Rejected(ReflectionParseFailure.DUPLICATE_KEY)
            StrictLearningJsonKeyScanner.Result.INVALID ->
                return ReflectionParseResult.Rejected(ReflectionParseFailure.INVALID_JSON)
            StrictLearningJsonKeyScanner.Result.VALID -> Unit
        }
        val root = try {
            json.parseToJsonElement(document) as? JsonObject
                ?: return ReflectionParseResult.Rejected(ReflectionParseFailure.ROOT_NOT_OBJECT)
        } catch (_: Exception) {
            return ReflectionParseResult.Rejected(ReflectionParseFailure.INVALID_JSON)
        }
        return try {
            parseRoot(root, input)
        } catch (rejected: Rejected) {
            ReflectionParseResult.Rejected(rejected.failure)
        } catch (_: Exception) {
            ReflectionParseResult.Rejected(ReflectionParseFailure.INVALID_JSON)
        }
    }

    private fun parseRoot(root: JsonObject, input: ReflectionInputBundle): ReflectionParseResult {
        val operation = root.requiredString("op")
        return when (operation) {
            "ABSTAIN" -> {
                root.exactKeys(setOf("schema_version", "input_id", "op"))
                validateEnvelope(root, input)
                ReflectionParseResult.Abstained
            }
            "LESSON" -> {
                root.exactKeys(
                    setOf(
                        "schema_version", "input_id", "op", "lesson_type", "trigger",
                        "observation", "lesson", "boundary", "evidence_aliases", "quality",
                    ),
                )
                validateEnvelope(root, input)
                val type = EpisodeLessonType.entries.firstOrNull {
                    it.name == root.requiredString("lesson_type")
                } ?: reject(ReflectionParseFailure.UNKNOWN_LESSON_TYPE)
                val aliases = root.requiredArray("evidence_aliases").map { element ->
                    val primitive = element as? JsonPrimitive
                        ?: reject(ReflectionParseFailure.WRONG_TYPE)
                    if (!primitive.isString) reject(ReflectionParseFailure.WRONG_TYPE)
                    primitive.content
                }
                if (aliases.isEmpty()) reject(ReflectionParseFailure.EMPTY_EVIDENCE)
                if (aliases.size > 16) reject(ReflectionParseFailure.TOO_MANY_EVIDENCE)
                if (aliases.distinct().size != aliases.size) {
                    reject(ReflectionParseFailure.DUPLICATE_EVIDENCE)
                }
                if (aliases.any { it !in input.allowedEvidence }) {
                    reject(ReflectionParseFailure.UNKNOWN_EVIDENCE)
                }
                val qualityPrimitive = root["quality"] as? JsonPrimitive
                    ?: reject(ReflectionParseFailure.WRONG_TYPE)
                if (qualityPrimitive.isString) reject(ReflectionParseFailure.WRONG_TYPE)
                val quality = qualityPrimitive.doubleOrNull
                    ?: reject(ReflectionParseFailure.WRONG_TYPE)
                if (!quality.isFinite() || quality !in 0.0..1.0) {
                    reject(ReflectionParseFailure.INVALID_QUALITY)
                }
                val trigger = root.sanitized("trigger")
                val observation = root.sanitized("observation")
                val lesson = root.sanitized("lesson")
                val boundary = root.sanitized("boundary")
                val artifactHash = LearningCanonicalId.digest(
                    domainVersion = "episode-lesson-v1",
                    fields = listOf(
                        input.inputId,
                        type.name,
                        trigger.value,
                        observation.value,
                        lesson.value,
                        boundary.value,
                        *aliases.sorted().toTypedArray(),
                        quality.toString(),
                    ),
                )
                ReflectionParseResult.Lesson(
                    ValidatedEpisodeLessonDraft(
                        inputId = input.inputId,
                        lessonType = type,
                        trigger = trigger,
                        observation = observation,
                        lesson = lesson,
                        boundary = boundary,
                        evidence = aliases.map(input.allowedEvidence::getValue),
                        quality = quality,
                        artifactHash = artifactHash,
                    ),
                )
            }
            else -> reject(ReflectionParseFailure.UNKNOWN_OPERATION)
        }
    }

    private fun validateEnvelope(root: JsonObject, input: ReflectionInputBundle) {
        val schemaPrimitive = root["schema_version"] as? JsonPrimitive
            ?: reject(ReflectionParseFailure.MISSING_FIELD)
        if (schemaPrimitive.isString) reject(ReflectionParseFailure.WRONG_TYPE)
        if (schemaPrimitive.intOrNull != OUTPUT_SCHEMA_VERSION) {
            reject(ReflectionParseFailure.SCHEMA_MISMATCH)
        }
        if (root.requiredString("input_id") != input.inputId) {
            reject(ReflectionParseFailure.INPUT_ID_MISMATCH)
        }
    }

    private fun JsonObject.exactKeys(expected: Set<String>) {
        if (!keys.containsAll(expected)) reject(ReflectionParseFailure.MISSING_FIELD)
        if (keys.any { it !in expected }) reject(ReflectionParseFailure.UNKNOWN_FIELD)
    }

    private fun JsonObject.requiredString(key: String): String {
        val primitive = this[key] as? JsonPrimitive
            ?: reject(if (key in keys) ReflectionParseFailure.WRONG_TYPE else ReflectionParseFailure.MISSING_FIELD)
        if (!primitive.isString) reject(ReflectionParseFailure.WRONG_TYPE)
        return primitive.content
    }

    private fun JsonObject.requiredArray(key: String): JsonArray =
        this[key] as? JsonArray
            ?: reject(if (key in keys) ReflectionParseFailure.WRONG_TYPE else ReflectionParseFailure.MISSING_FIELD)

    private fun JsonObject.sanitized(key: String): SanitizedTraceSummary =
        when (val result = TraceSanitizer.sanitize(requiredString(key))) {
            is TraceSanitizationResult.Accepted -> result.summary
            is TraceSanitizationResult.Rejected -> reject(ReflectionParseFailure.UNSAFE_TEXT)
        }

    private fun reject(failure: ReflectionParseFailure): Nothing = throw Rejected(failure)
    private class Rejected(val failure: ReflectionParseFailure) : RuntimeException()
}
