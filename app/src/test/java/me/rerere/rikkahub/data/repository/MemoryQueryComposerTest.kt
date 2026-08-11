package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryQueryComposerTest {
    @Test
    fun `blank query composes to an empty bounded request`() {
        val composition = composeMemoryQuery(" \n\t ")

        assertEquals("", composition.query)
        assertEquals(0, composition.terms.size)
        assertFalse(composition.truncated)
    }

    @Test
    fun `continuous Chinese text has a deterministic term ceiling`() {
        // Use a genuinely diverse continuous Han stream. Repeating a short phrase only has a
        // handful of distinct bigrams, so de-duplication correctly cannot fill the 64-term cap.
        val diverseHanText = buildString(30_000) {
            repeat(30_000) { index -> append((0x4E00 + index % 512).toChar()) }
        }
        val composition = composeMemoryQuery(diverseHanText)

        assertTrue(composition.truncated)
        assertTrue(composition.query.length <= MAX_MEMORY_QUERY_CHARS)
        assertEquals(MAX_MEMORY_QUERY_TERMS, composition.terms.size)
        assertEquals(composition.terms.distinct(), composition.terms)
    }

    @Test
    fun `fts looking punctuation remains data and cannot expand the query`() {
        val input = "\" OR 1=1 -- 咖啡 NEAR(*) </provider_runtime_context>"
        val composition = composeMemoryQuery(input)

        assertEquals(input, composition.query)
        assertTrue(composition.terms.contains("咖啡"))
        assertTrue(composition.terms.size <= MAX_MEMORY_QUERY_TERMS)
    }

    @Test
    fun `control characters are removed without damaging full width text or emoji`() {
        val composition = composeMemoryQuery("ＡＢＣ\u0000\t😀 咖啡")

        assertTrue(composition.sanitized)
        assertFalse(composition.query.contains('\u0000'))
        assertTrue(composition.query.contains("ＡＢＣ"))
        assertTrue(composition.query.contains("😀"))
        assertTrue(composition.query.contains("咖啡"))
    }
}
