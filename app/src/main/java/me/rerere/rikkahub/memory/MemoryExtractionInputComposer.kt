package me.rerere.rikkahub.memory

/**
 * Produces one bounded, chronological model input for an idle conversation.
 *
 * The application keeps all source turns and their real ids locally, but the remote extraction
 * provider receives short evidence references instead of a large UUID-heavy transcript. This
 * lets a long connected discussion yield one coherent proposal without letting an arbitrary
 * assistant answer or a large existing-memory list create an oversized request.
 */
internal class MemoryExtractionInputComposer(
    private val contentGuard: MemoryContentGuard,
) {
    fun compose(captures: List<MemoryCaptureRecord>): MemoryPreparedExtractionInput {
        val sources = captures.sortedBy(MemoryCaptureRecord::createdAtMs).mapNotNull { capture ->
            val user = normalize(contentGuard.redact(capture.userText).text)
            if (user.isBlank()) return@mapNotNull null
            MemoryExtractionSource(
                capture = capture,
                userText = user,
                assistantText = normalize(contentGuard.redact(capture.assistantText).text),
            )
        }
        if (sources.isEmpty()) return MemoryPreparedExtractionInput.EMPTY

        val budgetPerTurn = (MAX_MEMORY_EXTRACTION_CONTEXT_CHARS / sources.size)
            .coerceAtLeast(MIN_MEMORY_EXTRACTION_TURN_CHARS)
        val aliases = linkedMapOf<String, String>()
        val turns = sources.mapIndexed { index, source ->
            val reference = "T${index + 1}"
            aliases[reference] = source.capture.userMessageId
            val compacted = compactTurn(
                userText = source.userText,
                assistantText = source.assistantText,
                maxChars = budgetPerTurn,
            )
            MemoryExtractionTurn(
                userMessageId = source.capture.userMessageId,
                assistantMessageId = source.capture.assistantMessageId,
                userText = compacted.first,
                assistantText = compacted.second,
                evidenceRef = reference,
            )
        }
        return MemoryPreparedExtractionInput(
            turns = turns,
            evidenceRefToMessageId = aliases,
            isConversationContextCompacted = sources.size > 3 || sources.any { source ->
                source.userText.length + source.assistantText.length > budgetPerTurn
            },
        )
    }

    private fun compactTurn(
        userText: String,
        assistantText: String,
        maxChars: Int,
    ): Pair<String, String> {
        if (assistantText.isBlank()) return abbreviate(userText, maxChars) to ""
        if (userText.isBlank()) return "" to abbreviate(assistantText, maxChars)
        val preferredUserChars = (maxChars * USER_CONTEXT_SHARE).toInt().coerceAtLeast(1)
        val user = abbreviate(userText, preferredUserChars)
        val assistant = abbreviate(assistantText, (maxChars - user.length).coerceAtLeast(0))
        return user to assistant
    }
}

internal data class MemoryPreparedExtractionInput(
    val turns: List<MemoryExtractionTurn>,
    val evidenceRefToMessageId: Map<String, String>,
    val isConversationContextCompacted: Boolean,
) {
    companion object {
        val EMPTY = MemoryPreparedExtractionInput(emptyList(), emptyMap(), false)
    }
}

/** Keep relevant old memories useful without allowing them to dominate the provider request. */
internal fun compactExistingMemoriesForExtraction(
    existing: List<ExistingMemoryRecord>,
): List<ExistingMemoryRecord> = existing.take(MAX_MEMORY_EXTRACTION_EXISTING_MEMORIES).map { memory ->
    memory.copy(
        title = memory.title?.let { title -> abbreviate(normalize(title), MAX_EXISTING_MEMORY_TITLE_CHARS) },
        content = abbreviate(normalize(memory.content), MAX_EXISTING_MEMORY_CONTENT_CHARS),
        tags = memory.tags.map { tag -> abbreviate(normalize(tag), MAX_EXISTING_MEMORY_TAG_CHARS) },
    )
}

private data class MemoryExtractionSource(
    val capture: MemoryCaptureRecord,
    val userText: String,
    val assistantText: String,
)

private fun normalize(text: String): String = text.trim().replace(WHITESPACE, " ")

private fun abbreviate(text: String, maxChars: Int): String {
    if (maxChars <= 0 || text.isEmpty()) return ""
    if (text.length <= maxChars) return text
    if (maxChars < 8) return text.take(maxChars)
    val head = ((maxChars - 1) * 2) / 3
    val tail = maxChars - head - 1
    return text.take(head) + "…" + text.takeLast(tail)
}

internal const val MAX_MEMORY_EXTRACTION_CONTEXT_CHARS = 3_600
private const val MIN_MEMORY_EXTRACTION_TURN_CHARS = 96
private const val USER_CONTEXT_SHARE = 0.6f
private const val MAX_MEMORY_EXTRACTION_EXISTING_MEMORIES = 4
private const val MAX_EXISTING_MEMORY_TITLE_CHARS = 80
private const val MAX_EXISTING_MEMORY_CONTENT_CHARS = 300
private const val MAX_EXISTING_MEMORY_TAG_CHARS = 32
private val WHITESPACE = Regex("\\s+")
