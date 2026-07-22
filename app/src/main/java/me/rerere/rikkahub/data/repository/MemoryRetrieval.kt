package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.AssistantMemory
import kotlin.math.abs
import kotlin.uuid.Uuid

const val DEFAULT_MEMORY_TOP_K = 8
const val DEFAULT_MEMORY_PROMPT_MAX_CHARS = 6_000

/** One raw row returned by the SQLite FTS projection. */
data class MemorySearchCandidate(
    val id: Int,
    val title: String?,
    val content: String,
    val updatedAtMs: Long,
    val importance: Float,
    val ftsRank: Double,
)

/** Stable retrieval result exposed to diagnostics and future memory-query tools. */
data class MemoryMatch(
    val memory: AssistantMemory,
    val score: Double,
    val matchedTerms: List<String>,
    val reason: String,
)

/** Local-substitutable seam: SQLite in production, an in-memory adapter in policy tests. */
fun interface MemorySearchIndex {
    suspend fun search(
        scopeId: String,
        query: String,
        limit: Int,
    ): List<MemorySearchCandidate>
}

/**
 * Scope selection, failure degradation, scoring, duplicate removal and character budgeting live
 * behind one retrieval interface. The index supplies lexical candidates; it never decides which
 * Assistant is allowed to see which memory.
 */
class MemoryRetriever(
    private val index: MemorySearchIndex,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun queryRelevant(
        assistantId: Uuid?,
        query: String,
        includeGlobal: Boolean,
        limit: Int = DEFAULT_MEMORY_TOP_K,
        maxChars: Int = DEFAULT_MEMORY_PROMPT_MAX_CHARS,
        excludeMemoryIds: Set<Int> = emptySet(),
    ): List<MemoryMatch> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty() || limit <= 0 || maxChars <= 0) return emptyList()
        val scopeId = when {
            includeGlobal -> MemoryRepository.GLOBAL_MEMORY_ID
            assistantId != null -> assistantId.toString()
            else -> return emptyList()
        }
        val candidates = runCatching {
            index.search(
                scopeId = scopeId,
                query = normalizedQuery,
                limit = ((limit + excludeMemoryIds.size) * 4).coerceIn(limit, 256),
            )
        }.getOrElse { return emptyList() }
        val terms = memoryQueryTerms(normalizedQuery)
        val now = nowMs()
        val ranked = candidates
            .filterNot { it.id in excludeMemoryIds }
            .map { candidate -> candidate.toScoredMatch(terms, normalizedQuery, now) }
            .sortedWith(compareByDescending<MemoryMatch> { it.score }.thenBy { it.memory.id })

        val seenContent = hashSetOf<String>()
        val bounded = arrayListOf<MemoryMatch>()
        var remaining = maxChars
        for (match in ranked) {
            if (bounded.size >= limit || remaining <= 0) break
            val duplicateKey = match.memory.content
                .trim()
                .lowercase()
                .replace(Regex("\\s+"), " ")
            if (!seenContent.add(duplicateKey)) continue
            val content = match.memory.content.take(remaining)
            if (content.isEmpty()) continue
            bounded += match.copy(memory = match.memory.copy(content = content))
            remaining -= content.length
        }
        return bounded
    }
}

internal fun memoryQueryTerms(query: String): List<String> = buildSet {
    Regex("[\\p{L}\\p{N}_]+")
        .findAll(query.lowercase())
        .map { it.value }
        .forEach { token ->
            add(token)
            if (token.any(::isHanCharacter) && token.length >= 2) {
                token.windowed(size = 2, step = 1).forEach(::add)
            }
        }
}.filter { it.isNotBlank() }

private fun isHanCharacter(char: Char): Boolean =
    Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN

private fun MemorySearchCandidate.toScoredMatch(
    terms: List<String>,
    query: String,
    nowMs: Long,
): MemoryMatch {
    val normalizedTitle = title.orEmpty().trim().lowercase()
    val normalizedContent = content.lowercase()
    val normalizedQuery = query.trim().lowercase()
    val matchedTerms = terms.filter { term ->
        normalizedTitle.contains(term) || normalizedContent.contains(term)
    }.distinct()
    val lexicalScore = if (ftsRank <= 0.0) -ftsRank else 1.0 / (1.0 + abs(ftsRank))
    val titleBoost = when {
        normalizedTitle.isNotEmpty() && normalizedTitle == normalizedQuery -> 2.0
        normalizedTitle.isNotEmpty() && matchedTerms.any(normalizedTitle::contains) -> 1.0
        else -> 0.0
    }
    val ageMs = (nowMs - updatedAtMs).coerceAtLeast(0L)
    val recency = if (updatedAtMs <= 0L) 0.0 else {
        (1.0 - ageMs.toDouble() / (30L * 24 * 60 * 60 * 1_000L)).coerceIn(0.0, 1.0)
    }
    val score = lexicalScore + titleBoost +
        importance.coerceIn(0f, 1f) * 0.25 + recency * 0.15
    val reason = buildList {
        if (titleBoost > 0.0) add("title match")
        if (matchedTerms.isNotEmpty()) add("terms: ${matchedTerms.joinToString(", ")}")
        if (isEmpty()) add("fts match")
    }.joinToString("; ")
    return MemoryMatch(
        memory = AssistantMemory(id = id, content = content),
        score = score,
        matchedTerms = matchedTerms,
        reason = reason,
    )
}
