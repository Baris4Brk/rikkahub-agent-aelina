package me.rerere.rikkahub.learning.storage

import java.text.Normalizer
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.isSafeLearningIdentifier

internal const val MAX_LEARNING_SUMMARY_CHARS = 1_024
internal const val MAX_POLICY_SNAPSHOT_CHARS = 8_192

internal fun requireLearningScope(scopeKind: String, scopeId: String) {
    require(LearningScope.parseOrNull(scopeKind, scopeId) != null) {
        "Invalid Learning scope"
    }
}

internal fun requireLearningStorageId(value: String, label: String, maxChars: Int = 256) {
    require(isSafeLearningIdentifier(value, maxChars)) { "Invalid $label" }
}

internal fun requireLearningCode(value: String, label: String) {
    require(value.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))) { "Invalid $label" }
}

internal fun requireLearningIdentity(value: String, label: String) {
    require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,159}"))) {
        "Invalid $label"
    }
}

internal fun requireSha256(value: String, label: String) {
    require(value.matches(Regex("[0-9a-f]{64}"))) { "Invalid $label" }
}

/**
 * Last-line storage guard for L1 derived text. It cannot prove semantic redaction, but it rejects
 * common raw payload shapes, credentials, absolute paths and URL query material. Writers must
 * still use the versioned sanitizer before constructing an entity.
 */
internal fun requireBoundedRedactedText(
    value: String,
    label: String,
    maxChars: Int = MAX_LEARNING_SUMMARY_CHARS,
) {
    require(value.isNotBlank() && value.length <= maxChars) { "Invalid $label length" }
    require(value == value.trim()) { "$label is not canonical" }
    require(Normalizer.normalize(value, Normalizer.Form.NFC) == value) {
        "$label is not NFC normalized"
    }
    require(value.none { it == '\u0000' || it == '\r' || (it.isISOControl() && it != '\n' && it != '\t') }) {
        "$label contains control characters"
    }
    val trimmed = value.trimStart()
    require(!(trimmed.startsWith("{") || trimmed.startsWith("[") || RAW_XML_TAG.containsMatchIn(value))) {
        "$label resembles a raw structured payload"
    }
    require(!CREDENTIAL_MATERIAL.containsMatchIn(value)) { "$label contains credential material" }
    require(!WINDOWS_ABSOLUTE_PATH.containsMatchIn(value)) { "$label contains an absolute path" }
    require(!POSIX_PRIVATE_PATH.containsMatchIn(value)) { "$label contains an absolute path" }
    require(!UNC_PRIVATE_PATH.containsMatchIn(value)) { "$label contains an absolute path" }
    require(!NETWORK_LOCATION.containsMatchIn(value)) { "$label contains a network location" }
    require(!PROMPT_CONTROL_MATERIAL.containsMatchIn(value)) {
        "$label contains prompt-control material"
    }
    require(!RAW_TOOL_OR_REASONING_MATERIAL.containsMatchIn(value)) {
        "$label contains raw tool or reasoning material"
    }
}

internal fun requireNullableBoundedRedactedText(
    value: String?,
    label: String,
    maxChars: Int = MAX_LEARNING_SUMMARY_CHARS,
) {
    value?.let { requireBoundedRedactedText(it, label, maxChars) }
}

private val RAW_XML_TAG = Regex("<[/!?]?[A-Za-z][^>]{0,160}>")
private val CREDENTIAL_MATERIAL = Regex(
    "(?i)(?:bearer\\s+[A-Za-z0-9._~+/-]{8,}|sk-[A-Za-z0-9_-]{8,}|" +
        "(?:AKIA|ASIA)[A-Z0-9]{16}|" +
        "(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})|" +
        "xox[baprs]-[A-Za-z0-9-]{12,}|" +
        "eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}|" +
        "-----BEGIN(?: [A-Z0-9]+)* PRIVATE KEY-----|" +
        "(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|authorization|" +
        "proxy[_ -]?authorization|password|secret|cookie|set[_ -]?cookie)" +
        "\\s*[:=]\\s*\\S+)",
)
private val WINDOWS_ABSOLUTE_PATH = Regex("(?i)(?:^|\\s)[A-Z]:\\\\[^\\s]+")
private val POSIX_PRIVATE_PATH = Regex("(?:^|\\s)/(?:home|Users|data|storage|sdcard|etc)/[^\\s]+")
private val UNC_PRIVATE_PATH = Regex("(?:^|\\s)\\\\\\\\[^\\s\\\\]+\\\\[^\\s]+")
private val NETWORK_LOCATION = Regex("(?i)(?:https?|ftp|file|data)://|\\bwww\\.")
private val PROMPT_CONTROL_MATERIAL = Regex(
    "(?i)(?:ignore\\s+(?:all\\s+|the\\s+)?(?:previous|prior|system|developer|user)\\s+" +
        "(?:instructions?|messages?|prompts?)|" +
        "disregard\\s+(?:all\\s+|the\\s+)?(?:previous|prior|system|developer|user)\\s+" +
        "(?:instructions?|messages?|prompts?)|" +
        "(?:system|developer)\\s*(?:prompt|message)\\s*[:=]|" +
        "(?:reveal|print|repeat|expose|leak).{0,48}" +
        "(?:system prompt|hidden instructions?|chain[ _-]?of[ _-]?thought))",
)
private val RAW_TOOL_OR_REASONING_MATERIAL = Regex(
    "(?i)\\b(?:tool[ _-]?(?:args?|arguments?|output|result)|" +
        "tool[ _-]?(?:call|call[ _-]?id)|function[ _-]?(?:call|arguments?)|" +
        "raw[ _-]?(?:input|output|prompt|response)|" +
        "chain[ _-]?of[ _-]?thought|private[ _-]?reasoning)\\b\\s*[:=]",
)

enum class StoredLearningEpisodeStatus {
    OPEN,
    SUCCESS,
    PARTIAL,
    FAILURE,
    ABORTED,
    TIMEOUT,
    CENSORED,
    SUPERSEDED,
    UNKNOWN,
}

enum class LearningEpisodeBoundaryReason {
    COMMAND_ADMITTED,
    WAITING_APPROVAL,
    FINAL_SAVED,
    REGENERATED_BRANCH,
    STOPPED,
    INTERRUPTED,
    TIMED_OUT,
    FINAL_SAVE_FAILED,
    SOURCE_INVALIDATED,
    RETENTION_EXPIRED,
    UNKNOWN,
}

enum class LearningLessonType {
    ABSTAIN,
    SUCCESS_PATTERN,
    AVOID,
    FAILURE_MODE,
    UNKNOWN,
}

enum class LearningLessonState {
    VALID,
    STALE_SOURCE,
    REJECTED,
}

enum class LearningRewardWindowState {
    OPEN,
    CLOSED,
    EXPIRED,
}

enum class LearningRewardKnowledge {
    KNOWN,
    UNKNOWN,
    CENSORED,
}

enum class LearningSourceValidityState {
    VALID,
    INVALIDATED,
    TOMBSTONED,
    SUPERSEDED,
    UNKNOWN,
}

enum class StoredLearningPolicyStatus {
    CANDIDATE,
    SHADOW,
    PROBATION,
    ACTIVE,
    SUSPENDED,
    SUSPENDED_PENDING_REVIEW,
    STALE_SCHEMA,
    STALE_SOURCE,
    STALE_AUTHORITY,
    ARCHIVED,
}

enum class LearningPolicyEvidencePolarity {
    POSITIVE,
    NEGATIVE,
    NEUTRAL,
    UNKNOWN,
}

enum class LearningPolicyRevisionReason {
    CREATE,
    CREATED_FROM_VALIDATED_DRAFT,
    APPLICABILITY_REBUILT,
    EVIDENCE_ADDED,
    SHADOW_PROMOTION,
    SHADOW_ELIGIBLE,
    USER_APPROVED_CONTEXTUAL_ADVICE,
    USER_SUSPENDED,
    USER_RESTORED_REVISION,
    USER_ARCHIVED,
    RETENTION_EXPIRED,
    EXPOSURE_DISPATCHED,
    SAFETY_REVIEW_REQUIRED,
    SOURCE_INVALIDATED,
    SCHEMA_INVALIDATED,
    TOOL_SCHEMA_CHANGED,
    CAPABILITY_CHANGED,
    AUTHORITY_INVALIDATED,
    AUTHORITY_CHANGED,
    ARCHIVE,
    MERGE,
    SUPERSEDE,
    CURATOR_UPDATE,
    CURATOR_SPLIT,
    CURATOR_ROLLBACK,
    UNKNOWN,
}

enum class LearningPolicyRevisionActor {
    SYSTEM,
    USER,
    IMPORT,
    DISTILLER,
    SHADOW_GATE,
    CURATOR_REVIEW,
    GRANT_BINDER,
    SAFETY_GOVERNOR,
    AUTHORITY_RECONCILER,
    SOURCE_INVALIDATOR,
    RETENTION,
}

enum class LearningPolicyLineageRelation {
    DERIVED_FROM,
    SUPERSEDES,
    MERGED_FROM,
    CONTRADICTS,
}
