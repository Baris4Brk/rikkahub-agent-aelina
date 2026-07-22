package me.rerere.rikkahub.ui.pages.memory

/**
 * Reduces persisted extraction failures to a short, user-visible diagnostic.
 *
 * Provider responses are outside our trust boundary, so this second redaction layer protects
 * the settings surface even when a non-OpenAI adapter supplied the failure text.
 */
internal fun formatMemoryFailureDetail(
    code: String?,
    message: String?,
): String? {
    val safeCode = code
        ?.trim()
        ?.replace(FAILURE_CODE_UNSAFE_CHARS, "_")
        ?.take(MAX_MEMORY_FAILURE_CODE_CHARS)
        ?.takeIf { it.isNotEmpty() }
    val safeMessage = message
        ?.sanitizeMemoryFailureDetail()
        ?.take(MAX_MEMORY_FAILURE_MESSAGE_CHARS)
        ?.takeIf { it.isNotEmpty() }
    return listOfNotNull(safeCode, safeMessage).joinToString(": ").takeIf { it.isNotEmpty() }
}

private const val MAX_MEMORY_FAILURE_CODE_CHARS = 96
private const val MAX_MEMORY_FAILURE_MESSAGE_CHARS = 320

private fun String.sanitizeMemoryFailureDetail(): String =
    replace(HEADER_CREDENTIAL) { match ->
        "${match.groupValues[1]}[redacted]"
    }
        .replace(QUERY_CREDENTIAL) { match ->
            "${match.groupValues[1]}[redacted]"
        }
        .replace(BEARER_CREDENTIAL, "Bearer [redacted]")
        .replace(NAMED_CREDENTIAL) { match ->
            "${match.groupValues[1]}[redacted]"
        }
        .replace(OPAQUE_PROVIDER_CREDENTIAL, "[redacted]")
        .replace(JWT_CREDENTIAL, "[redacted]")
        .replace(WHITESPACE, " ")
        .trim()

private val FAILURE_CODE_UNSAFE_CHARS = Regex("""[^A-Za-z0-9_.-]+""")
private val HEADER_CREDENTIAL = Regex(
    """(?i)(["']?(?:(?:proxy[-_ ]?)?authorization|(?:set[-_ ]?)?cookie)["']?\s*[:=]\s*)""" +
        """(?:"[^"]*"|'[^']*'|[^\r\n}\]]+)""",
)
private val QUERY_CREDENTIAL = Regex(
    """(?i)([?&](?:key|api[-_]?key|access[-_]?token|token|signature|sig)=)[^&#\s,;}\]"']+""",
)
private val BEARER_CREDENTIAL = Regex("""(?i)\bbearer\s+['\"]?[^\s,;}\]]+['\"]?""")
private val NAMED_CREDENTIAL = Regex(
    """(?i)(["']?(?:(?:x[-_ ]?)?api[-_ ]?key|(?:access|refresh|id|session)[-_ ]?token|""" +
        """token|secret|password|credential|jwt|client[-_ ]?secret|private[-_ ]?key|signature|sig)["']?\s*[:=]\s*)""" +
        """(?:"[^"]*"|'[^']*'|[^\s,;}\]]+)""",
)
private val OPAQUE_PROVIDER_CREDENTIAL = Regex(
    """(?i)\b(?:sk-[A-Za-z0-9_-]{8,}|AIza[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}|""" +
        """xox[baprs]-[A-Za-z0-9-]{16,})\b""",
)
private val JWT_CREDENTIAL = Regex(
    """(?i)\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b""",
)
private val WHITESPACE = Regex("""\s+""")
