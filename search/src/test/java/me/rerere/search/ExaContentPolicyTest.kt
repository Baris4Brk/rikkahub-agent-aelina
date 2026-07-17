package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaContentPolicyTest {
    @Test
    fun `scrape URL accepts only credential-free HTTP URLs`() {
        assertEquals("https://example.com/a", validateExaUrl("https://example.com/a"))
        assertEquals("http://example.com", validateExaUrl("http://example.com"))

        listOf(
            "ftp://example.com/file",
            "https://user:secret@example.com/private",
            "https://example.com@evil.example/private",
            "https:///missing-host",
        ).forEach { value ->
            assertTrue(runCatching { validateExaUrl(value) }.isFailure)
        }
    }

    @Test
    fun `search content is capped per item and across the complete result`() {
        val result = SearchResult(
            answer = "answer",
            items = List(20) { index ->
                SearchResult.SearchResultItem(
                    title = "title-$index",
                    url = "https://example.com/$index",
                    text = "x".repeat(10_000),
                )
            },
        )

        val limited = limitExaSearchResult(result)

        assertTrue(limited.items.isNotEmpty())
        assertTrue(limited.items.all { it.text.length <= EXA_MAX_SEARCH_ITEM_CHARS })
        assertTrue(limited.items.first().text.endsWith(EXA_TRUNCATION_MARKER))
        assertTrue(limited.totalCharacters() <= EXA_MAX_TOTAL_OUTPUT_CHARS)
    }

    @Test
    fun `scraped pages are capped per page and across all pages`() {
        val result = ScrapedResult(
            urls = List(3) { index ->
                ScrapedResultUrl(
                    url = "https://example.com/$index",
                    content = "y".repeat(70_000),
                )
            },
        )

        val limited = limitExaScrapedResult(result)

        assertFalse(limited.urls.isEmpty())
        assertTrue(limited.urls.all { it.content.length <= EXA_MAX_SCRAPE_PAGE_CHARS })
        assertTrue(limited.urls.first().content.endsWith(EXA_TRUNCATION_MARKER))
        assertTrue(limited.totalCharacters() <= EXA_MAX_TOTAL_OUTPUT_CHARS)
    }

    private fun SearchResult.totalCharacters(): Int =
        (answer?.length ?: 0) + items.sumOf { it.title.length + it.url.length + it.text.length }

    private fun ScrapedResult.totalCharacters(): Int = urls.sumOf { item ->
        item.url.length + item.content.length +
            (item.metadata?.title?.length ?: 0) +
            (item.metadata?.description?.length ?: 0) +
            (item.metadata?.language?.length ?: 0)
    }
}
