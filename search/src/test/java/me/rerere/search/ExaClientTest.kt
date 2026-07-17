package me.rerere.search

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExaClientTest {
    @Test
    fun `search sends a bounded request and limits returned item content`() = runBlocking {
        val transport = RecordingExaTransport(
            ExaTransportResponse(
                statusCode = 200,
                body = """
                    {
                      "results": [{
                        "id": "one",
                        "title": "Example",
                        "url": "https://example.com",
                        "text": "${"x".repeat(9_000)}"
                      }],
                      "output": {"content": "answer"}
                    }
                """.trimIndent(),
            ),
        )

        val result = ExaClient(transport).search(
            query = "private query",
            resultSize = 3,
            type = "auto",
            apiKey = "secret-key",
        )

        assertEquals("https://api.exa.ai/search", transport.request?.url)
        assertEquals("secret-key", transport.request?.apiKey)
        assertEquals("private query", transport.request?.body?.get("query")?.jsonPrimitive?.content)
        assertEquals(3, transport.request?.body?.get("numResults")?.jsonPrimitive?.content?.toInt())
        assertTrue(result.items.single().text.endsWith(EXA_TRUNCATION_MARKER))
        assertTrue(result.items.single().text.length <= EXA_MAX_SEARCH_ITEM_CHARS)
    }

    @Test
    fun `scrape uses contents endpoint after validating the URL`() = runBlocking {
        val transport = RecordingExaTransport(
            ExaTransportResponse(
                statusCode = 200,
                body = """
                    {"results": [{
                      "id": "one",
                      "title": "Example",
                      "url": "https://example.com/page",
                      "text": "page body"
                    }]}
                """.trimIndent(),
            ),
        )

        val result = ExaClient(transport).scrape(
            url = "https://example.com/page",
            apiKey = "secret-key",
        )

        assertEquals("https://api.exa.ai/contents", transport.request?.url)
        assertEquals(
            "https://example.com/page",
            transport.request?.body?.get("ids")?.jsonArray?.single()?.jsonPrimitive?.content,
        )
        assertEquals("page body", result.urls.single().content)
    }

    @Test
    fun `invalid scrape URL fails before any network request`() = runBlocking {
        val transport = RecordingExaTransport(ExaTransportResponse(200, "{}"))

        val error = runCatching {
            ExaClient(transport).scrape(
                url = "https://user:secret@example.com/private",
                apiKey = "secret-key",
            )
        }.exceptionOrNull() as SearchServiceFailureException

        assertEquals(SearchFailureCode.InvalidRequest, error.failure.code)
        assertEquals(null, transport.request)
    }

    @Test
    fun `HTTP and decoding failures expose only structured metadata`() = runBlocking {
        val privateBody = "private upstream response"
        val httpError = runCatching {
            ExaClient(RecordingExaTransport(ExaTransportResponse(429, privateBody))).search(
                query = "private query",
                resultSize = 1,
                type = "auto",
                apiKey = "secret-key",
            )
        }.exceptionOrNull() as SearchServiceFailureException

        assertEquals(SearchFailureCode.HttpError, httpError.failure.code)
        assertEquals(429, httpError.failure.httpStatus)
        assertTrue(httpError.failure.retryable)
        assertFalse(httpError.message.orEmpty().contains(privateBody))
        assertFalse(httpError.message.orEmpty().contains("private query"))

        val decodeError = runCatching {
            ExaClient(RecordingExaTransport(ExaTransportResponse(200, "private malformed body"))).search(
                query = "private query",
                resultSize = 1,
                type = "auto",
                apiKey = "secret-key",
            )
        }.exceptionOrNull() as SearchServiceFailureException

        assertEquals(SearchFailureCode.InvalidResponse, decodeError.failure.code)
        assertFalse(decodeError.message.orEmpty().contains("private malformed body"))
    }

    private class RecordingExaTransport(
        private val response: ExaTransportResponse,
    ) : ExaTransport {
        var request: ExaTransportRequest? = null
            private set

        override suspend fun post(request: ExaTransportRequest): ExaTransportResponse {
            this.request = request
            return response
        }
    }
}
