package me.rerere.highlight

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.highlight.core.HighlightEngine
import me.rerere.highlight.languages.builtinLanguages
import java.util.concurrent.atomic.AtomicLong

internal const val HIGHLIGHT_MAX_CODE_BYTES = 1024 * 1024

/**
 * Pure Kotlin syntax highlighter backed by the highlight.js-compatible mode-stack parser.
 * Unsupported languages are returned unchanged.
 */
class CodeHighlighter {
    private val engine = HighlightEngine(builtinLanguages())

    fun highlight(code: String, language: String): List<HighlightToken> {
        if (code.isEmpty()) return emptyList()
        if (exceedsHighlightBudget(code)) return listOf(HighlightToken.Plain(code))

        val normalized = language.trim().lowercase()
        if (normalized == "regex" || normalized == "regexp") {
            return highlightRegex(code)
        }
        return engine.highlight(code, language) ?: listOf(HighlightToken.Plain(code))
    }

    fun supports(language: String): Boolean {
        val normalized = language.trim().lowercase()
        return normalized == "regex" || normalized == "regexp" || engine.supports(language)
    }
}

/**
 * Compatibility facade retained for the app's existing DI and Compose call sites.
 * [Context] is intentionally unused now that highlighting no longer boots a QuickJS runtime.
 */
class Highlighter @JvmOverloads constructor(
    @Suppress("UNUSED_PARAMETER") context: Context? = null,
) {
    private val delegate = CodeHighlighter()
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    suspend fun highlight(code: String, language: String): List<HighlightToken> =
        withContext(dispatcher) { delegate.highlight(code, language) }

    fun supports(language: String): Boolean = delegate.supports(language)

    /** Kept for binary/source compatibility; the Kotlin engine owns no native resources. */
    fun destroy() = Unit
}

internal class HighlightRequestTracker {
    private val sequence = AtomicLong(0)

    fun next(): Long = sequence.incrementAndGet()

    fun isCurrent(requestId: Long): Boolean = sequence.get() == requestId
}

internal fun exceedsHighlightBudget(
    code: String,
    maxBytes: Int = HIGHLIGHT_MAX_CODE_BYTES,
): Boolean {
    if (code.length > maxBytes) return true
    var bytes = 0
    var index = 0
    while (index < code.length) {
        val char = code[index]
        bytes += when {
            char.code <= 0x7f -> 1
            char.code <= 0x7ff -> 2
            char.isHighSurrogate() && index + 1 < code.length && code[index + 1].isLowSurrogate() -> {
                index += 1
                4
            }
            else -> 3
        }
        if (bytes > maxBytes) return true
        index += 1
    }
    return false
}

private fun highlightRegex(code: String): List<HighlightToken> {
    val tokens = mutableListOf<HighlightToken>()
    var plainStart = 0
    var index = 0

    fun emitPlain(end: Int) {
        if (end > plainStart) tokens += HighlightToken.Plain(code.substring(plainStart, end))
    }

    fun emitStyled(endExclusive: Int, type: String) {
        emitPlain(index)
        tokens += HighlightToken.Styled(code.substring(index, endExclusive), type)
        index = endExclusive
        plainStart = index
    }

    while (index < code.length) {
        when (code[index]) {
            '\\' -> emitStyled((index + 2).coerceAtMost(code.length), "char.escape")
            '[', ']' -> emitStyled(index + 1, "string")
            '(', ')', '|' -> emitStyled(index + 1, "punctuation")
            '^', '$' -> emitStyled(index + 1, "keyword")
            '*', '+', '?' -> emitStyled(index + 1, "operator")
            '{' -> {
                val close = code.indexOf('}', startIndex = index + 1)
                emitStyled(if (close == -1) index + 1 else close + 1, "operator")
            }
            else -> index += 1
        }
    }
    emitPlain(code.length)
    return tokens
}
