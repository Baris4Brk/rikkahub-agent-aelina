package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.search.SearchFailureCode
import me.rerere.search.SearchServiceFailure
import me.rerere.search.SearchServiceFailureException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SearchToolsFailureTest {
    @Test
    fun `known search failures become a redacted structured tool result`() {
        val privateCause = IllegalStateException("private upstream response")
        val result = Result.failure<String>(
            SearchServiceFailureException(
                failure = SearchServiceFailure(
                    code = SearchFailureCode.HttpError,
                    retryable = true,
                    httpStatus = 429,
                ),
                cause = privateCause,
            ),
        )

        val payload = result.toSearchToolPayload { error("success mapper must not run") }
        val error = payload["error"]!!.jsonObject

        assertEquals("false", payload["success"]!!.jsonPrimitive.content)
        assertEquals("HttpError", error["code"]!!.jsonPrimitive.content)
        assertEquals("true", error["retryable"]!!.jsonPrimitive.content)
        assertEquals("429", error["httpStatus"]!!.jsonPrimitive.content)
        assertFalse(payload.toString().contains("private upstream response"))
    }
}
