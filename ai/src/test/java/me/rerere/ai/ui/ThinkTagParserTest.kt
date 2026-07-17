package me.rerere.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThinkTagParserTest {
    @Test
    fun `closed think tag keeps following answer visible`() {
        val result = ThinkTagParser.parse("<think>plan</think>final answer")

        assertFalse(result.malformed)
        assertEquals(
            listOf(
                ThinkTagSegment.Reasoning("plan", malformed = false),
                ThinkTagSegment.Text("final answer"),
            ),
            result.segments,
        )
    }

    @Test
    fun `unclosed think tag is malformed reasoning and never guessed as an answer`() {
        val result = ThinkTagParser.parse("<think>analysis without a closing tag")

        assertEquals(true, result.malformed)
        assertEquals(
            listOf(
                ThinkTagSegment.Reasoning(
                    "analysis without a closing tag",
                    malformed = true,
                    closed = false,
                ),
            ),
            result.segments,
        )
    }

    @Test
    fun `text before and after a closed think tag keeps semantic order`() {
        val result = ThinkTagParser.parse("prefix<think>analysis</think>final")

        assertFalse(result.malformed)
        assertEquals(
            listOf(
                ThinkTagSegment.Text("prefix"),
                ThinkTagSegment.Reasoning("analysis", malformed = false),
                ThinkTagSegment.Text("final"),
            ),
            result.segments,
        )
    }

    @Test
    fun `open think block is pending while streaming and malformed only at finish`() {
        val streaming = ThinkTagParser.parse("<think>working", isFinal = false)
        val finished = ThinkTagParser.parse("<think>working", isFinal = true)

        assertFalse(streaming.malformed)
        assertEquals(true, streaming.hasOpenReasoning)
        assertEquals(
            listOf(ThinkTagSegment.Reasoning("working", malformed = false, closed = false)),
            streaming.segments,
        )
        assertEquals(true, finished.malformed)
        assertEquals(
            listOf(ThinkTagSegment.Reasoning("working", malformed = true, closed = false)),
            finished.segments,
        )
    }
}
