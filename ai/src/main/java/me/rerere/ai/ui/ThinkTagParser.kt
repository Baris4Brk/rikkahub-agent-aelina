package me.rerere.ai.ui

sealed interface ThinkTagSegment {
    data class Text(val text: String) : ThinkTagSegment

    data class Reasoning(
        val reasoning: String,
        val malformed: Boolean,
        val closed: Boolean = true,
    ) : ThinkTagSegment
}

data class ThinkTagParseResult(
    val segments: List<ThinkTagSegment>,
    val malformed: Boolean,
    val hasOpenReasoning: Boolean = false,
)

/**
 * Parses the cumulative text produced by a streaming provider without guessing where an
 * unclosed `<think>` block ends. Callers may parse the latest cumulative buffer on every
 * visual update; a tag split across network chunks becomes valid as soon as the closing bytes
 * arrive. Persisted conversion should happen only when generation finishes.
 */
object ThinkTagParser {
    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    fun parse(text: String, isFinal: Boolean = true): ThinkTagParseResult {
        if (text.isEmpty()) return ThinkTagParseResult(emptyList(), malformed = false)

        val segments = mutableListOf<ThinkTagSegment>()
        var cursor = 0
        var malformed = false
        var hasOpenReasoning = false

        while (cursor < text.length) {
            val openIndex = text.indexOf(OPEN, startIndex = cursor)
            val orphanCloseIndex = text.indexOf(CLOSE, startIndex = cursor)
            if (orphanCloseIndex >= 0 && (openIndex < 0 || orphanCloseIndex < openIndex)) {
                malformed = true
                segments.addText(text.substring(cursor, orphanCloseIndex + CLOSE.length))
                cursor = orphanCloseIndex + CLOSE.length
                continue
            }
            if (openIndex < 0) {
                val remainder = text.substring(cursor)
                val pendingTagPrefixLength = if (isFinal) 0 else remainder.pendingTagPrefixLength()
                segments.addText(remainder.dropLast(pendingTagPrefixLength))
                break
            }

            segments.addText(text.substring(cursor, openIndex))
            val reasoningStart = openIndex + OPEN.length
            val closeIndex = text.indexOf(CLOSE, startIndex = reasoningStart)
            if (closeIndex < 0) {
                hasOpenReasoning = true
                val segmentMalformed = isFinal ||
                    text.indexOf(OPEN, startIndex = reasoningStart) >= reasoningStart
                malformed = malformed || segmentMalformed
                segments += ThinkTagSegment.Reasoning(
                    reasoning = text.substring(reasoningStart),
                    malformed = segmentMalformed,
                    closed = false,
                )
                break
            }

            val nestedOpen = text.indexOf(OPEN, startIndex = reasoningStart)
            val segmentMalformed = nestedOpen in reasoningStart until closeIndex
            malformed = malformed || segmentMalformed
            segments += ThinkTagSegment.Reasoning(
                reasoning = text.substring(reasoningStart, closeIndex),
                malformed = segmentMalformed,
            )
            cursor = closeIndex + CLOSE.length
        }

        return ThinkTagParseResult(
            segments = segments.mergeAdjacent(),
            malformed = malformed,
            hasOpenReasoning = hasOpenReasoning,
        )
    }

    private fun MutableList<ThinkTagSegment>.addText(text: String) {
        if (text.isNotEmpty()) add(ThinkTagSegment.Text(text))
    }

    private fun String.pendingTagPrefixLength(): Int =
        (1 until OPEN.length).lastOrNull { length ->
            endsWith(OPEN.take(length)) || endsWith(CLOSE.take(length))
        } ?: 0

    private fun List<ThinkTagSegment>.mergeAdjacent(): List<ThinkTagSegment> =
        fold(emptyList()) { result, segment ->
            val previous = result.lastOrNull()
            when {
                previous is ThinkTagSegment.Text && segment is ThinkTagSegment.Text ->
                    result.dropLast(1) + previous.copy(text = previous.text + segment.text)
                previous is ThinkTagSegment.Reasoning && segment is ThinkTagSegment.Reasoning &&
                    previous.malformed == segment.malformed && previous.closed == segment.closed ->
                    result.dropLast(1) + previous.copy(
                        reasoning = previous.reasoning + segment.reasoning,
                    )
                else -> result + segment
            }
        }
}
