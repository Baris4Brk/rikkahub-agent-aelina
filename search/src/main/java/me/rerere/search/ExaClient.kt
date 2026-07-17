package me.rerere.search

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.search.SearchService.Companion.json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "ExaTransport"
private const val EXA_SEARCH_URL = "https://api.exa.ai/search"
private const val EXA_CONTENTS_URL = "https://api.exa.ai/contents"
private const val EXA_MAX_RAW_RESPONSE_CHARS = 2_000_000

internal data class ExaTransportRequest(
    val url: String,
    val apiKey: String,
    val body: JsonObject,
)

internal data class ExaTransportResponse(
    val statusCode: Int,
    val body: String,
)

internal fun interface ExaTransport {
    suspend fun post(request: ExaTransportRequest): ExaTransportResponse
}

internal class OkHttpExaTransport(
    private val clientProvider: () -> OkHttpClient,
) : ExaTransport {
    override suspend fun post(request: ExaTransportRequest): ExaTransportResponse {
        val httpRequest = Request.Builder()
            .url(request.url)
            .post(request.body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${request.apiKey}")
            .build()
        val call = clientProvider().newCall(httpRequest)
        try {
            return call.await().use { response ->
                val body = response.body.charStream().use { reader ->
                    val buffer = CharArray(8_192)
                    val result = StringBuilder()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = reader.read(buffer)
                        if (read < 0) break
                        if (result.length + read > EXA_MAX_RAW_RESPONSE_CHARS) {
                            throw SearchServiceFailureException(
                                SearchServiceFailure(
                                    code = SearchFailureCode.ResponseTooLarge,
                                    retryable = false,
                                    httpStatus = response.code,
                                ),
                            )
                        }
                        result.append(buffer, 0, read)
                    }
                    result.toString()
                }
                Log.d(TAG, "Exa response status=${response.code}, length=${body.length}")
                ExaTransportResponse(statusCode = response.code, body = body)
            }
        } catch (cancelled: CancellationException) {
            call.cancel()
            throw cancelled
        }
    }
}

internal class ExaClient(
    private val transport: ExaTransport,
) {
    suspend fun search(
        query: String,
        resultSize: Int,
        type: String,
        apiKey: String,
    ): SearchResult {
        if (query.isBlank() || type !in setOf("fast", "auto", "deep")) {
            throw failure(SearchFailureCode.InvalidRequest, retryable = false)
        }
        val request = ExaTransportRequest(
            url = EXA_SEARCH_URL,
            apiKey = apiKey,
            body = buildJsonObject {
                put("query", query)
                put("numResults", resultSize.coerceIn(1, 100))
                put("type", type)
                put("contents", buildJsonObject { put("text", true) })
            },
        )
        val data = decode(execute(request))
        return limitExaSearchResult(
            SearchResult(
                answer = data.output?.content,
                items = data.results.map { item ->
                    SearchResult.SearchResultItem(
                        title = item.title.orEmpty(),
                        url = item.url,
                        text = item.text.orEmpty(),
                    )
                },
            ),
        )
    }

    suspend fun scrape(url: String, apiKey: String): ScrapedResult {
        val validatedUrl = try {
            validateExaUrl(url)
        } catch (error: IllegalArgumentException) {
            throw failure(SearchFailureCode.InvalidRequest, retryable = false, cause = error)
        }
        val request = ExaTransportRequest(
            url = EXA_CONTENTS_URL,
            apiKey = apiKey,
            body = buildJsonObject {
                put("ids", buildJsonArray { add(validatedUrl) })
                put("text", true)
            },
        )
        val data = decode(execute(request))
        return limitExaScrapedResult(
            ScrapedResult(
                urls = data.results.map { item ->
                    ScrapedResultUrl(
                        url = item.url,
                        content = item.text.orEmpty(),
                        metadata = ScrapedResultMetadata(title = item.title),
                    )
                },
            ),
        )
    }

    private suspend fun execute(request: ExaTransportRequest): ExaTransportResponse {
        val response = try {
            transport.post(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SearchServiceFailureException) {
            throw failure
        } catch (error: Throwable) {
            throw failure(SearchFailureCode.NetworkError, retryable = true, cause = error)
        }
        if (response.statusCode !in 200..299) {
            throw SearchServiceFailureException(
                SearchServiceFailure(
                    code = SearchFailureCode.HttpError,
                    retryable = response.statusCode in RETRYABLE_HTTP_STATUS,
                    httpStatus = response.statusCode,
                ),
            )
        }
        return response
    }

    private fun decode(response: ExaTransportResponse): ExaResponseData = try {
        json.decodeFromString<ExaResponseData>(response.body)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        throw failure(SearchFailureCode.InvalidResponse, retryable = false, cause = error)
    }

    private fun failure(
        code: SearchFailureCode,
        retryable: Boolean,
        cause: Throwable? = null,
    ) = SearchServiceFailureException(
        SearchServiceFailure(code = code, retryable = retryable),
        cause,
    )

    private companion object {
        val RETRYABLE_HTTP_STATUS = setOf(408, 425, 429) + (500..599)
    }
}

@Serializable
internal data class ExaResponseData(
    val results: List<ExaResponseResult> = emptyList(),
    val output: ExaResponseOutput? = null,
)

@Serializable
internal data class ExaResponseOutput(
    val content: String? = null,
)

@Serializable
internal data class ExaResponseResult(
    val id: String? = null,
    val title: String? = null,
    val url: String,
    val text: String? = null,
)
