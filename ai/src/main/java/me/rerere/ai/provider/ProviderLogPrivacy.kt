package me.rerere.ai.provider

/**
 * Produces content-free provider diagnostics. Provider parse exceptions can embed the raw
 * JSON/SSE input in their message, so callers must never pass the exception itself to Log.
 */
internal object ProviderLogPrivacy {
    fun parseFailure(
        eventChars: Int,
        eventType: String?,
        error: Throwable,
    ): String = "provider event parse failed " +
        "(chars=${eventChars.coerceAtLeast(0)}, " +
        "type=${eventType.safeToken("unknown")}, " +
        "error=${error.safeClassName()})"

    fun errorBodyParseFailure(
        bodyChars: Int,
        error: Throwable,
    ): String = "provider error body parse failed " +
        "(chars=${bodyChars.coerceAtLeast(0)}, error=${error.safeClassName()})"

    fun parseException(
        eventChars: Int,
        eventType: String?,
        error: Throwable,
    ): Exception = ProviderContentParseException(
        parseFailure(eventChars, eventType, error),
    )

    fun errorBodyParseException(
        bodyChars: Int,
        error: Throwable,
    ): Exception = ProviderContentParseException(
        errorBodyParseFailure(bodyChars, error),
    )

    fun transportFailure(
        statusCode: Int?,
        error: Throwable?,
    ): String = "provider transport failed " +
        "(status=${statusCode ?: "none"}, error=${error?.safeClassName() ?: "none"})"

    fun encodingFailure(
        contentKind: String,
        error: Throwable,
    ): String = "provider content encoding failed " +
        "(kind=${contentKind.safeToken("unknown")}, error=${error.safeClassName()})"

    private fun String?.safeToken(fallback: String): String {
        val candidate = this ?: return fallback
        return candidate.takeIf { value ->
            value.isNotEmpty() && value.length <= MAX_SAFE_TOKEN_LENGTH &&
                value.all { it.isLetterOrDigit() || it in SAFE_TOKEN_PUNCTUATION }
        } ?: fallback
    }

    private fun Throwable.safeClassName(): String =
        javaClass.simpleName.safeToken("Throwable")

    private const val MAX_SAFE_TOKEN_LENGTH = 64
    private val SAFE_TOKEN_PUNCTUATION = setOf('.', '_', '-', ':')
}

private class ProviderContentParseException(message: String) : Exception(message)
