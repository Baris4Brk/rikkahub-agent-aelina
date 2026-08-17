package me.rerere.rikkahub.learning.trace

import java.nio.charset.StandardCharsets
import java.text.Normalizer

private const val MAX_TRACE_SUMMARY_UTF8_BYTES = 1_024

@JvmInline
value class SanitizedTraceSummary private constructor(val value: String) {
    override fun toString(): String = "SanitizedTraceSummary(<redacted>)"

    companion object {
        internal fun create(value: String): SanitizedTraceSummary = SanitizedTraceSummary(value)
    }
}

enum class TraceSanitizationFailure {
    EMPTY,
    TOO_LARGE,
    INVALID_UNICODE,
    CONTROL_CHARACTER,
    CREDENTIAL_LIKE,
    URL_LIKE,
    ABSOLUTE_PATH_LIKE,
    PROMPT_OVERRIDE_LIKE,
    RAW_STRUCTURED_OR_TOOL_MATERIAL,
}

sealed interface TraceSanitizationResult {
    data class Accepted(val summary: SanitizedTraceSummary) : TraceSanitizationResult
    data class Rejected(val failure: TraceSanitizationFailure) : TraceSanitizationResult
}

/** Fail-closed sanitizer for optional model-derived summaries. It never logs rejected content. */
object TraceSanitizer {
    fun sanitize(candidate: String): TraceSanitizationResult {
        if (candidate.isBlank()) return TraceSanitizationResult.Rejected(TraceSanitizationFailure.EMPTY)
        if (candidate.hasUnpairedSurrogate()) {
            return TraceSanitizationResult.Rejected(TraceSanitizationFailure.INVALID_UNICODE)
        }
        val normalized = Normalizer.normalize(candidate, Normalizer.Form.NFKC)
            .replace(WHITESPACE_RUN, " ")
            .trim()
        if (normalized.isEmpty()) return TraceSanitizationResult.Rejected(TraceSanitizationFailure.EMPTY)
        if (normalized.toByteArray(StandardCharsets.UTF_8).size > MAX_TRACE_SUMMARY_UTF8_BYTES) {
            return TraceSanitizationResult.Rejected(TraceSanitizationFailure.TOO_LARGE)
        }
        if (normalized.any { it.isISOControl() }) {
            return TraceSanitizationResult.Rejected(TraceSanitizationFailure.CONTROL_CHARACTER)
        }
        if (CREDENTIAL_PATTERNS.any { it.containsMatchIn(normalized) }) {
            return TraceSanitizationResult.Rejected(TraceSanitizationFailure.CREDENTIAL_LIKE)
        }
        if (URL_PATTERN.containsMatchIn(normalized)) {
            return TraceSanitizationResult.Rejected(TraceSanitizationFailure.URL_LIKE)
        }
        if (ABSOLUTE_PATH_PATTERNS.any { it.containsMatchIn(normalized) }) {
            return TraceSanitizationResult.Rejected(TraceSanitizationFailure.ABSOLUTE_PATH_LIKE)
        }
        if (PROMPT_OVERRIDE_PATTERNS.any { it.containsMatchIn(normalized) }) {
            return TraceSanitizationResult.Rejected(TraceSanitizationFailure.PROMPT_OVERRIDE_LIKE)
        }
        if (RAW_STRUCTURED_OR_TOOL_PATTERNS.any { it.containsMatchIn(normalized) }) {
            return TraceSanitizationResult.Rejected(
                TraceSanitizationFailure.RAW_STRUCTURED_OR_TOOL_MATERIAL,
            )
        }
        return TraceSanitizationResult.Accepted(SanitizedTraceSummary.create(normalized))
    }
}

private fun String.hasUnpairedSurrogate(): Boolean {
    var index = 0
    while (index < length) {
        val char = this[index]
        when {
            char.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return true
                index += 2
            }
            char.isLowSurrogate() -> return true
            else -> index += 1
        }
    }
    return false
}

private val WHITESPACE_RUN = Regex("\\s+")
private val URL_PATTERN = Regex("(?i)(?:https?|ftp|file|data)://|www\\.")
private val CREDENTIAL_PATTERNS = listOf(
    Regex(
        "(?i)\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|" +
            "proxy[_-]?authorization|password|passwd|secret|cookie|set[_-]?cookie)\\s*[:=]",
    ),
    Regex("\\bsk-[A-Za-z0-9_-]{12,}"),
    Regex("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
    Regex("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b"),
    Regex("\\bxox[baprs]-[A-Za-z0-9-]{12,}\\b"),
    Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"),
    Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]{8,}"),
    Regex("-----BEGIN [A-Z ]+PRIVATE KEY-----"),
)
private val ABSOLUTE_PATH_PATTERNS = listOf(
    Regex("(?i)(?:^|\\s)[A-Z]:[\\\\/](?:[^\\s]+)"),
    Regex("(?:^|\\s)/(?:home|Users|data|storage|sdcard|etc|var|tmp)/[^\\s]+"),
    Regex("(?:^|\\s)\\\\\\\\[^\\s\\\\]+\\\\[^\\s]+"),
)
private val PROMPT_OVERRIDE_PATTERNS = listOf(
    Regex("(?i)ignore (?:all |the )?(?:previous|prior|system) instructions"),
    Regex("(?i)disregard (?:all |the )?(?:previous|prior|system|developer) instructions"),
    Regex("(?i)(?:system|developer)\\s*(?:prompt|message)\\s*[:=]"),
    Regex("(?i)reveal (?:the )?(?:system prompt|hidden instructions|chain of thought)"),
)
private val RAW_STRUCTURED_OR_TOOL_PATTERNS = listOf(
    Regex("(?s)^\\s*[\\[{].*[\\]}]\\s*$"),
    Regex("(?is)<\\s*/?\\s*[a-z][a-z0-9:_-]*(?:\\s+[^>]*)?/?>|<\\?xml"),
    Regex(
        "(?i)\\b(?:tool[ _-]?(?:args?|arguments?|output|result)|" +
            "tool[ _-]?(?:call|call[ _-]?id)|function[ _-]?(?:call|arguments?)|" +
            "raw[ _-]?(?:input|output|prompt|response)|" +
            "chain[ _-]?of[ _-]?thought|private[ _-]?reasoning)\\b\\s*[:=]",
    ),
)
