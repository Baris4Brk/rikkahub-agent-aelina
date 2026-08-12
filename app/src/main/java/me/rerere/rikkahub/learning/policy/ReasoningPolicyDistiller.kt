package me.rerere.rikkahub.learning.policy

import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.StrictLearningJsonKeyScanner
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import me.rerere.rikkahub.learning.trace.SanitizedTraceSummary
import me.rerere.rikkahub.learning.trace.TraceSanitizationResult
import me.rerere.rikkahub.learning.trace.TraceSanitizer

data class PolicyDistillationInput(
    val scope: LearningScope,
    val taskSignature: TaskSignatureV1,
    val evidenceAllowlist: Map<String, PolicyEvidenceHandle>,
    val toolSchemaAllowlist: Set<String>,
    val producerIdentity: String,
    val modelIdentity: String,
    val promptVersion: String,
) {
    init {
        require(evidenceAllowlist.size in 2..32)
        require(evidenceAllowlist.values.map(PolicyEvidenceHandle::episodeId).distinct().size >= 2)
        require(toolSchemaAllowlist.size <= 32)
        require(producerIdentity.matches(PolicyCandidateDraft.SAFE_VERSION))
        require(modelIdentity.matches(PolicyCandidateDraft.SAFE_VERSION))
        require(promptVersion.matches(PolicyCandidateDraft.SAFE_VERSION))
    }
}

enum class PolicyDistillationFailure {
    EMPTY,
    TOO_LARGE,
    DUPLICATE_KEY,
    INVALID_JSON,
    UNKNOWN_FIELD,
    MISSING_FIELD,
    WRONG_TYPE,
    UNKNOWN_OPERATION,
    UNKNOWN_TYPE,
    UNSAFE_TEXT,
    EVIDENCE_INVALID,
    TOOL_SCHEMA_INVALID,
    VALIDATION_REJECTED,
}

sealed interface PolicyDistillationResult {
    data object Abstained : PolicyDistillationResult
    data class Candidate(val draft: PolicyCandidateDraft) : PolicyDistillationResult
    data class Rejected(val failure: PolicyDistillationFailure) : PolicyDistillationResult
}

/** The only parser/constructor for model-produced PolicyCandidateDraft. */
object ReasoningPolicyDistiller {
    const val SCHEMA_VERSION = 1
    const val MAX_OUTPUT_UTF8_BYTES = 32 * 1_024
    private val json = Json { isLenient = false; ignoreUnknownKeys = false; coerceInputValues = false }

    fun distill(raw: String, input: PolicyDistillationInput): PolicyDistillationResult {
        if (raw.isBlank()) return rejected(PolicyDistillationFailure.EMPTY)
        if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_OUTPUT_UTF8_BYTES) {
            return rejected(PolicyDistillationFailure.TOO_LARGE)
        }
        when (StrictLearningJsonKeyScanner.scan(raw)) {
            StrictLearningJsonKeyScanner.Result.DUPLICATE -> return rejected(PolicyDistillationFailure.DUPLICATE_KEY)
            StrictLearningJsonKeyScanner.Result.INVALID -> return rejected(PolicyDistillationFailure.INVALID_JSON)
            StrictLearningJsonKeyScanner.Result.VALID -> Unit
        }
        val root = try {
            json.parseToJsonElement(raw) as? JsonObject ?: return rejected(PolicyDistillationFailure.INVALID_JSON)
        } catch (_: Exception) {
            return rejected(PolicyDistillationFailure.INVALID_JSON)
        }
        return try {
            parse(root, input)
        } catch (rejection: Rejection) {
            rejected(rejection.failure)
        } catch (_: Exception) {
            rejected(PolicyDistillationFailure.INVALID_JSON)
        }
    }

    private fun parse(root: JsonObject, input: PolicyDistillationInput): PolicyDistillationResult {
        val operation = root.string("op")
        if (operation == "ABSTAIN") {
            root.exact(setOf("schema_version", "op"))
            root.schema()
            return PolicyDistillationResult.Abstained
        }
        if (operation != "CANDIDATE") reject(PolicyDistillationFailure.UNKNOWN_OPERATION)
        root.exact(
            setOf(
                "schema_version", "op", "type", "trigger", "procedure", "verification",
                "boundary", "failure_mode", "evidence_ids", "tool_schema_fingerprints",
            ),
        )
        root.schema()
        val type = PolicyCandidateType.entries.firstOrNull { it.name == root.string("type") }
            ?: reject(PolicyDistillationFailure.UNKNOWN_TYPE)
        val evidenceIds = root.arrayStrings("evidence_ids", 1, 16)
        if (evidenceIds.distinct().size != evidenceIds.size || evidenceIds.any { it !in input.evidenceAllowlist }) {
            reject(PolicyDistillationFailure.EVIDENCE_INVALID)
        }
        val evidence = evidenceIds.map(input.evidenceAllowlist::getValue)
        val schemas = root.arrayStrings("tool_schema_fingerprints", 0, 16).toSet()
        if (schemas.any { it !in input.toolSchemaAllowlist }) {
            reject(PolicyDistillationFailure.TOOL_SCHEMA_INVALID)
        }
        val trigger = root.summary("trigger")
        val procedure = root.summary("procedure")
        val verification = root.summary("verification")
        val boundary = root.summary("boundary")
        val failureMode = root.summary("failure_mode")
        val inputSetHash = PolicyCandidateIdFactory.inputSetHash(evidence)
        val artifactHash = LearningCanonicalId.digest(
            domainVersion = "policy-artifact-v1",
            fields = listOf(
                type.name,
                trigger.value,
                procedure.value,
                verification.value,
                boundary.value,
                failureMode.value,
                *schemas.sorted().toTypedArray(),
            ),
        )
        val draft = PolicyCandidateDraft(
            candidateId = PolicyCandidateIdFactory.candidateId(
                input.scope,
                input.taskSignature,
                inputSetHash,
                input.producerIdentity,
                input.modelIdentity,
                input.promptVersion,
                SCHEMA_VERSION,
            ),
            scope = input.scope,
            taskSignature = input.taskSignature,
            type = type,
            trigger = trigger,
            procedure = procedure,
            verification = verification,
            boundary = boundary,
            failureMode = failureMode,
            evidence = evidence,
            applicableToolSchemas = schemas,
            inputSetHash = inputSetHash,
            artifactHash = artifactHash,
            producerIdentity = input.producerIdentity,
            modelIdentity = input.modelIdentity,
            promptVersion = input.promptVersion,
            schemaVersion = SCHEMA_VERSION,
        )
        return when (
            val validation = PolicyCandidateValidator.validate(
                draft,
                PolicyCandidateValidationContext(
                    allowedEvidenceById = input.evidenceAllowlist.values.associateBy {
                        it.lessonId
                    },
                    allowedToolSchemaFingerprints = input.toolSchemaAllowlist,
                ),
            )
        ) {
            is PolicyCandidateValidationResult.Valid -> PolicyDistillationResult.Candidate(validation.draft)
            is PolicyCandidateValidationResult.Rejected -> rejected(PolicyDistillationFailure.VALIDATION_REJECTED)
        }
    }

    private fun JsonObject.schema() {
        val primitive = this["schema_version"] as? JsonPrimitive
            ?: reject(PolicyDistillationFailure.MISSING_FIELD)
        if (primitive.isString || primitive.content != SCHEMA_VERSION.toString()) {
            reject(PolicyDistillationFailure.WRONG_TYPE)
        }
    }

    private fun JsonObject.exact(keysExpected: Set<String>) {
        if (!keys.containsAll(keysExpected)) reject(PolicyDistillationFailure.MISSING_FIELD)
        if (keys.any { it !in keysExpected }) reject(PolicyDistillationFailure.UNKNOWN_FIELD)
    }

    private fun JsonObject.string(key: String): String {
        val primitive = this[key] as? JsonPrimitive
            ?: reject(if (key in keys) PolicyDistillationFailure.WRONG_TYPE else PolicyDistillationFailure.MISSING_FIELD)
        if (!primitive.isString) reject(PolicyDistillationFailure.WRONG_TYPE)
        return primitive.content
    }

    private fun JsonObject.arrayStrings(key: String, min: Int, max: Int): List<String> {
        val array = this[key] as? JsonArray
            ?: reject(if (key in keys) PolicyDistillationFailure.WRONG_TYPE else PolicyDistillationFailure.MISSING_FIELD)
        if (array.size !in min..max) reject(PolicyDistillationFailure.WRONG_TYPE)
        return array.map {
            val primitive = it as? JsonPrimitive ?: reject(PolicyDistillationFailure.WRONG_TYPE)
            if (!primitive.isString) reject(PolicyDistillationFailure.WRONG_TYPE)
            primitive.content
        }
    }

    private fun JsonObject.summary(key: String): SanitizedTraceSummary = when (
        val sanitized = TraceSanitizer.sanitize(string(key))
    ) {
        is TraceSanitizationResult.Accepted -> sanitized.summary
        is TraceSanitizationResult.Rejected -> reject(PolicyDistillationFailure.UNSAFE_TEXT)
    }

    private fun rejected(failure: PolicyDistillationFailure) = PolicyDistillationResult.Rejected(failure)
    private fun reject(failure: PolicyDistillationFailure): Nothing = throw Rejection(failure)
    private class Rejection(val failure: PolicyDistillationFailure) : RuntimeException()
}
