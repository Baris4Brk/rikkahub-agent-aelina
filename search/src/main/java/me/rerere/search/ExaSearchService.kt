package me.rerere.search

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchService.Companion.keyRoulette

object ExaSearchService : SearchService<SearchServiceOptions.ExaOptions> {
    override val name: String = "Exa"

    @Composable
    override fun Description() {
        val urlHandler = LocalUriHandler.current
        TextButton(
            onClick = {
                urlHandler.openUri("https://dashboard.exa.ai/api-keys")
            }
        ) {
            Text(stringResource(R.string.click_to_get_api_key))
        }
    }

    override fun parameters(options: SearchServiceOptions.ExaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
                put("type", buildJsonObject {
                    put("type", "string")
                    put("description", "Search type: fast (quick results), auto (default, balanced), deep (synthesized answer with citations)")
                    put("enum", buildJsonArray {
                        add("fast")
                        add("auto")
                        add("deep")
                    })
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.ExaOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "credential-free HTTP or HTTPS URL to scrape")
                })
            },
            required = listOf("url"),
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        captureFailure {
            val query = (params["query"] as? JsonPrimitive)?.content.orEmpty()
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())
            client().search(
                query = query,
                resultSize = commonOptions.resultSize,
                type = (params["type"] as? JsonPrimitive)?.content ?: "auto",
                apiKey = apiKey,
            )
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.ExaOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        captureFailure {
            val url = (params["url"] as? JsonPrimitive)?.content.orEmpty()
            val apiKey = keyRoulette.next(serviceOptions.apiKey, serviceOptions.id.toString())
            client().scrape(url = url, apiKey = apiKey)
        }
    }

    private fun client() = ExaClient(OkHttpExaTransport { SearchService.httpClient })

    private suspend fun <T> captureFailure(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
