package me.rerere.highlight

import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlighterLimitsAndPerformanceTest {
    @Test
    fun `one mib limit is measured in utf8 bytes`() {
        assertFalse(exceedsHighlightBudget("a".repeat(HIGHLIGHT_MAX_CODE_BYTES)))
        assertTrue(exceedsHighlightBudget("a".repeat(HIGHLIGHT_MAX_CODE_BYTES + 1)))
        assertFalse(exceedsHighlightBudget("界".repeat(HIGHLIGHT_MAX_CODE_BYTES / 3)))
        assertTrue(exceedsHighlightBudget("界".repeat(HIGHLIGHT_MAX_CODE_BYTES / 3 + 1)))
    }

    @Test
    fun `over limit input degrades to lossless plain text`() {
        val code = "x".repeat(HIGHLIGHT_MAX_CODE_BYTES + 1)

        assertEquals(listOf(HighlightToken.Plain(code)), CodeHighlighter().highlight(code, "kotlin"))
    }

    @Test
    fun `latest request wins after five thousand streaming appends`() {
        val tracker = HighlightRequestTracker()
        val ids = (1..5_000).map { tracker.next() }

        ids.dropLast(1).forEach { assertFalse(tracker.isCurrent(it)) }
        assertTrue(tracker.isCurrent(ids.last()))
    }

    @Test
    fun `native engine highlights up to five thousand lines within jvm budget`() {
        val highlighter = CodeHighlighter()
        highlighter.highlight("val warmup = 1", "kotlin")

        listOf(
            100 to 2_000L,
            1_000 to 5_000L,
            5_000 to 20_000L,
        ).forEach { (lineCount, budgetMs) ->
            val code = buildString {
                repeat(lineCount) { line ->
                    append("val item")
                    append(line)
                    append(" = \"value-")
                    append(line)
                    append("\" // fixture\n")
                }
            }
            lateinit var result: List<HighlightToken>
            val elapsed = measureTimeMillis {
                result = highlighter.highlight(code, "kotlin")
            }

            assertEquals(code, result.joinToString(separator = "") { it.content })
            assertTrue(
                "$lineCount lines took ${elapsed}ms (budget ${budgetMs}ms)",
                elapsed <= budgetMs,
            )
        }
    }
}
