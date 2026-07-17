package me.rerere.rikkahub.data.model

internal const val REGEX_CACHE_TTL_MILLIS: Long = 10 * 60 * 1_000L

/** Caches both valid and invalid user patterns so rendering cannot repeatedly recompile them. */
internal class RegexCompilationCache(
    private val ttlMillis: Long = REGEX_CACHE_TTL_MILLIS,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val compiler: (String) -> Regex = ::Regex,
) {
    private data class Entry(
        val result: Result<Regex>,
        val expiresAtMillis: Long,
    )

    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun get(pattern: String): Result<Regex> {
        val now = clockMillis()
        entries[pattern]?.takeIf { now < it.expiresAtMillis }?.let { return it.result }

        val result = runCatching { compiler(pattern) }
        entries[pattern] = Entry(
            result = result,
            expiresAtMillis = (now + ttlMillis).coerceAtLeast(now),
        )
        return result
    }
}

private val assistantRegexCompilationCache = RegexCompilationCache()

internal fun compiledAssistantRegex(pattern: String): Result<Regex> =
    assistantRegexCompilationCache.get(pattern)
