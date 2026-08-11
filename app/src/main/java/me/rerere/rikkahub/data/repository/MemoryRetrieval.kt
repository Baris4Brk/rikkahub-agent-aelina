package me.rerere.rikkahub.data.repository

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.model.AssistantMemory
import java.util.Locale
import kotlin.math.abs
import kotlin.uuid.Uuid

const val DEFAULT_MEMORY_TOP_K = 8
const val DEFAULT_MEMORY_PROMPT_MAX_CHARS = 6_000
const val MAX_MEMORY_QUERY_CHARS = 2_048
const val MAX_MEMORY_QUERY_TERMS = 64
const val MAX_MEMORY_RETRIEVAL_TOP_K = 64
const val MAX_MEMORY_INDEX_CANDIDATES = 256

/** The exact policy inputs consumed by a retrieval attempt. */
data class MemoryRetrievalRequest(
    val assistantId: Uuid?,
    val query: String,
    val includeGlobal: Boolean,
    val limit: Int = DEFAULT_MEMORY_TOP_K,
    val maxChars: Int = DEFAULT_MEMORY_PROMPT_MAX_CHARS,
    val excludeMemoryIds: Set<Int> = emptySet(),
    val frozenNowMs: Long,
    val querySource: MemoryRetrievalQuerySource = MemoryRetrievalQuerySource.UNSPECIFIED,
)

data class MemoryRetrievalResult(
    val matches: List<MemoryMatch>,
    val trace: MemoryRetrievalTrace,
)

/** Inputs that must reach the SQLite boundary unchanged, especially the frozen clock. */
data class MemoryIndexSearchRequest(
    val scopeId: String,
    val query: String,
    val limit: Int,
    val frozenNowMs: Long,
)

/** One raw row returned by the SQLite FTS projection. */
data class MemorySearchCandidate(
    val id: Int,
    val title: String?,
    val content: String,
    val updatedAtMs: Long,
    val importance: Float,
    val ftsRank: Double,
)

/** Runtime retrieval result. Persist [MemoryRetrievalTrace], never these query-derived terms. */
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

    /**
     * New deterministic entry point. Existing in-memory adapters remain source compatible via
     * the three-argument SAM above; production indexes override this overload.
     */
    suspend fun search(request: MemoryIndexSearchRequest): List<MemorySearchCandidate> = search(
        scopeId = request.scopeId,
        query = request.query,
        limit = request.limit,
    )
}

/**
 * Scope selection, failure degradation, scoring, duplicate removal and character budgeting live
 * behind one retrieval interface. The index supplies lexical candidates; it never decides which
 * Assistant is allowed to see which memory.
 */
class MemoryRetriever(
    private val index: MemorySearchIndex,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun retrieve(request: MemoryRetrievalRequest): MemoryRetrievalResult {
        val startedAt = nanoTime()
        val composedQuery = composeMemoryQuery(request.query)
        val effectiveLimit = request.limit.coerceIn(1, MAX_MEMORY_RETRIEVAL_TOP_K)
        val scopeKind = when {
            request.includeGlobal -> MemoryRetrievalScopeKind.GLOBAL
            request.assistantId != null -> MemoryRetrievalScopeKind.ASSISTANT
            else -> MemoryRetrievalScopeKind.NONE
        }
        val scopeId = when (scopeKind) {
            MemoryRetrievalScopeKind.GLOBAL -> MemoryRepository.GLOBAL_MEMORY_ID
            MemoryRetrievalScopeKind.ASSISTANT -> request.assistantId.toString()
            MemoryRetrievalScopeKind.NONE -> null
        }
        val candidateLimit = ((effectiveLimit + request.excludeMemoryIds.size.coerceAtMost(256)) * 4)
            .coerceIn(effectiveLimit, MAX_MEMORY_INDEX_CANDIDATES)

        fun emptyResult(status: MemoryRetrievalStatus): MemoryRetrievalResult =
            MemoryRetrievalResult(
                matches = emptyList(),
                trace = MemoryRetrievalTrace(
                    scopeKind = scopeKind,
                    querySource = request.querySource,
                    status = status,
                    queryChars = composedQuery.originalChars,
                    effectiveQueryChars = composedQuery.indexQuery.length,
                    queryTermCount = composedQuery.terms.size,
                    queryTruncated = composedQuery.truncated,
                    querySanitized = composedQuery.sanitized,
                    requestedTopK = request.limit,
                    candidateLimit = if (status == MemoryRetrievalStatus.INDEX_UNAVAILABLE) {
                        candidateLimit
                    } else {
                        0
                    },
                    rawCandidateCount = 0,
                    selectedCount = 0,
                    timings = MemoryRetrievalTimings(
                        totalMicros = elapsedMicros(startedAt, nanoTime()),
                    ),
                ),
            )

        if (composedQuery.indexQuery.isEmpty()) {
            return emptyResult(MemoryRetrievalStatus.SKIPPED_EMPTY_QUERY)
        }
        if (scopeId == null) {
            return emptyResult(MemoryRetrievalStatus.SKIPPED_NO_SCOPE)
        }
        if (request.limit <= 0 || request.maxChars <= 0) {
            return emptyResult(MemoryRetrievalStatus.SKIPPED_INVALID_BUDGET)
        }

        val indexStartedAt = nanoTime()
        val candidates = try {
            index.search(
                MemoryIndexSearchRequest(
                    scopeId = scopeId,
                    query = composedQuery.indexQuery,
                    limit = candidateLimit,
                    frozenNowMs = request.frozenNowMs,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MemoryRetrievalResult(
                matches = emptyList(),
                trace = MemoryRetrievalTrace(
                    scopeKind = scopeKind,
                    querySource = request.querySource,
                    status = MemoryRetrievalStatus.INDEX_UNAVAILABLE,
                    queryChars = composedQuery.originalChars,
                    effectiveQueryChars = composedQuery.indexQuery.length,
                    queryTermCount = composedQuery.terms.size,
                    queryTruncated = composedQuery.truncated,
                    querySanitized = composedQuery.sanitized,
                    requestedTopK = request.limit,
                    candidateLimit = candidateLimit,
                    rawCandidateCount = 0,
                    selectedCount = 0,
                    timings = MemoryRetrievalTimings(
                        indexMicros = elapsedMicros(indexStartedAt, nanoTime()),
                        totalMicros = elapsedMicros(startedAt, nanoTime()),
                    ),
                ),
            )
        }
        val indexFinishedAt = nanoTime()
        val rankStartedAt = indexFinishedAt
        val indexedCandidates = candidates.mapIndexed { index, candidate ->
            IndexedMemorySearchCandidate(index + 1, candidate)
        }
        val excluded = indexedCandidates.filter { it.candidate.id in request.excludeMemoryIds }
        val ranked = indexedCandidates
            .filterNot { it.candidate.id in request.excludeMemoryIds }
            .map { indexed ->
                ScoredMemorySearchCandidate(
                    ordinal = indexed.ordinal,
                    candidate = indexed.candidate,
                    match = indexed.candidate.toScoredMatch(
                        terms = composedQuery.terms,
                        query = composedQuery.query,
                        nowMs = request.frozenNowMs,
                    ),
                )
            }
            .sortedWith(
                compareByDescending<ScoredMemorySearchCandidate> { it.match.score }
                    .thenBy { it.candidate.id }
                    .thenBy { it.ordinal },
            )

        val seenContent = hashSetOf<String>()
        val bounded = arrayListOf<MemoryMatch>()
        val decisions = mutableMapOf<Int, MemoryRetrievalCandidateTrace>()
        excluded.forEach { indexed ->
            decisions[indexed.ordinal] = MemoryRetrievalCandidateTrace(
                candidateOrdinal = indexed.ordinal,
                rawRank = indexed.ordinal,
                decision = MemoryRetrievalDecision.EXCLUDED_ID,
            )
        }
        var remaining = request.maxChars
        ranked.forEachIndexed { rankedIndex, scored ->
            val candidate = scored.candidate
            val match = scored.match
            val normalizedContent = match.memory.content
                .trim()
                .lowercase(Locale.ROOT)
                .replace(MEMORY_WHITESPACE_REGEX, " ")
            val decision = when {
                match.memory.content.isBlank() -> MemoryRetrievalDecision.EMPTY_CONTENT
                !seenContent.add(normalizedContent) -> MemoryRetrievalDecision.DUPLICATE_CONTENT
                bounded.size >= effectiveLimit -> MemoryRetrievalDecision.TOP_K_EXCEEDED
                match.memory.content.length > remaining -> MemoryRetrievalDecision.BUDGET_EXCEEDED
                else -> MemoryRetrievalDecision.SELECTED
            }
            val finalRank = if (decision == MemoryRetrievalDecision.SELECTED) {
                bounded += match
                remaining -= match.memory.content.length
                bounded.size
            } else {
                null
            }
            decisions[scored.ordinal] = MemoryRetrievalCandidateTrace(
                candidateOrdinal = scored.ordinal,
                rawRank = scored.ordinal,
                rerankedRank = rankedIndex + 1,
                finalRank = finalRank,
                score = match.score,
                decision = decision,
            )
        }
        val rankFinishedAt = nanoTime()
        return MemoryRetrievalResult(
            matches = bounded,
            trace = MemoryRetrievalTrace(
                scopeKind = scopeKind,
                querySource = request.querySource,
                status = MemoryRetrievalStatus.SUCCESS,
                queryChars = composedQuery.originalChars,
                effectiveQueryChars = composedQuery.indexQuery.length,
                queryTermCount = composedQuery.terms.size,
                queryTruncated = composedQuery.truncated,
                querySanitized = composedQuery.sanitized,
                requestedTopK = request.limit,
                candidateLimit = candidateLimit,
                rawCandidateCount = candidates.size,
                selectedCount = bounded.size,
                candidates = decisions.values.sortedBy { it.candidateOrdinal },
                timings = MemoryRetrievalTimings(
                    indexMicros = elapsedMicros(indexStartedAt, indexFinishedAt),
                    rankMicros = elapsedMicros(rankStartedAt, rankFinishedAt),
                    totalMicros = elapsedMicros(startedAt, rankFinishedAt),
                ),
            ),
        )
    }

    suspend fun queryRelevant(
        assistantId: Uuid?,
        query: String,
        includeGlobal: Boolean,
        limit: Int = DEFAULT_MEMORY_TOP_K,
        maxChars: Int = DEFAULT_MEMORY_PROMPT_MAX_CHARS,
        excludeMemoryIds: Set<Int> = emptySet(),
    ): List<MemoryMatch> = retrieve(
        MemoryRetrievalRequest(
            assistantId = assistantId,
            query = query,
            includeGlobal = includeGlobal,
            limit = limit,
            maxChars = maxChars,
            excludeMemoryIds = excludeMemoryIds,
            frozenNowMs = nowMs(),
        ),
    ).matches
}

internal data class MemoryQueryComposition(
    val query: String,
    /** Bounded lexical payload that is actually handed to FTS/jieba_query. */
    val indexQuery: String,
    val originalChars: Int,
    val terms: List<String>,
    val truncated: Boolean,
    val sanitized: Boolean,
)

internal fun composeMemoryQuery(query: String): MemoryQueryComposition {
    // Bound before regex/token normalization so adversarial whitespace or CJK input cannot make
    // query construction scale with an untrusted chat-message length.
    val bounded = query.takeWholeCodePoints(MAX_MEMORY_QUERY_CHARS)
    val containedControlCharacters = bounded.any { char -> Character.isISOControl(char.code) }
    val sanitized = buildString(bounded.length) {
        bounded.forEach { char ->
            append(if (Character.isISOControl(char.code)) ' ' else char)
        }
    }
    val normalized = MEMORY_WHITESPACE_REGEX.replace(sanitized, " ").trim()
    val terms = memoryQueryTerms(normalized)
    return MemoryQueryComposition(
        query = normalized,
        indexQuery = terms.joinToString(" ").takeWholeCodePoints(MAX_MEMORY_QUERY_CHARS),
        originalChars = query.length,
        terms = terms,
        truncated = query.length > MAX_MEMORY_QUERY_CHARS,
        sanitized = containedControlCharacters,
    )
}

internal fun memoryQueryTerms(query: String): List<String> {
    val terms = linkedSetOf<String>()
    for (match in Regex("[\\p{L}\\p{N}_]+").findAll(query.lowercase(Locale.ROOT))) {
        val token = match.value
        if (token.length <= 128) terms += token
        if (token.any(::isHanCharacter) && token.length >= 2) {
            for (bigram in token.windowed(size = 2, step = 1)) {
                terms += bigram
                if (terms.size >= MAX_MEMORY_QUERY_TERMS) break
            }
        }
        if (terms.size >= MAX_MEMORY_QUERY_TERMS) break
    }
    return terms.take(MAX_MEMORY_QUERY_TERMS)
}

private fun String.takeWholeCodePoints(maxChars: Int): String {
    if (length <= maxChars) return this
    var end = maxChars.coerceAtLeast(0)
    if (end in 1 until length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) {
        end--
    }
    return substring(0, end)
}

private fun elapsedMicros(startNanos: Long, endNanos: Long): Long =
    ((endNanos - startNanos).coerceAtLeast(0L)) / 1_000L

private val MEMORY_WHITESPACE_REGEX = Regex("\\s+")

private data class IndexedMemorySearchCandidate(
    val ordinal: Int,
    val candidate: MemorySearchCandidate,
)

private data class ScoredMemorySearchCandidate(
    val ordinal: Int,
    val candidate: MemorySearchCandidate,
    val match: MemoryMatch,
)

private fun isHanCharacter(char: Char): Boolean =
    Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN

private fun MemorySearchCandidate.toScoredMatch(
    terms: List<String>,
    query: String,
    nowMs: Long,
): MemoryMatch {
    val normalizedTitle = title.orEmpty().trim().lowercase(Locale.ROOT)
    val normalizedContent = content.lowercase(Locale.ROOT)
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val matchedTerms = terms.filter { term ->
        normalizedTitle.contains(term) || normalizedContent.contains(term)
    }.distinct()
    val lexicalScore = when {
        !ftsRank.isFinite() -> 0.0
        ftsRank <= 0.0 -> -ftsRank
        else -> 1.0 / (1.0 + abs(ftsRank))
    }
    val titleBoost = when {
        normalizedTitle.isNotEmpty() && normalizedTitle == normalizedQuery -> 2.0
        normalizedTitle.isNotEmpty() && matchedTerms.any(normalizedTitle::contains) -> 1.0
        else -> 0.0
    }
    val ageMs = (nowMs.toDouble() - updatedAtMs.toDouble()).coerceAtLeast(0.0)
    val recency = if (updatedAtMs <= 0L) 0.0 else {
        (1.0 - ageMs / (30L * 24 * 60 * 60 * 1_000L)).coerceIn(0.0, 1.0)
    }
    val safeImportance = importance.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val score = (lexicalScore + titleBoost + safeImportance * 0.25 + recency * 0.15)
        .takeIf(Double::isFinite)
        ?: 0.0
    val reason = buildList {
        if (titleBoost > 0.0) add("title match")
        if (matchedTerms.isNotEmpty()) add("terms: ${matchedTerms.joinToString(", ")}")
        if (isEmpty()) add("fts match")
    }.joinToString("; ")
    return MemoryMatch(
        memory = AssistantMemory(id = id, content = content, title = title),
        score = score,
        matchedTerms = matchedTerms,
        reason = reason,
    )
}
