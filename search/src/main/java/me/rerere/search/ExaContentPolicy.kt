package me.rerere.search

import java.net.URI

internal const val EXA_MAX_SEARCH_ITEM_CHARS = 8_000
internal const val EXA_MAX_SCRAPE_PAGE_CHARS = 64_000
internal const val EXA_MAX_TOTAL_OUTPUT_CHARS = 128_000
internal const val EXA_TRUNCATION_MARKER = "\n[content truncated]"

internal fun validateExaUrl(rawUrl: String): String {
    val value = rawUrl.trim()
    val uri = runCatching { URI(value) }.getOrNull()
        ?: throw IllegalArgumentException("invalid_url")
    val scheme = uri.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") { "invalid_url_scheme" }
    require(!uri.host.isNullOrBlank()) { "invalid_url_host" }
    require(uri.userInfo == null && uri.rawAuthority?.contains('@') != true) {
        "url_credentials_not_allowed"
    }
    return value
}

internal fun limitExaSearchResult(result: SearchResult): SearchResult {
    var remaining = EXA_MAX_TOTAL_OUTPUT_CHARS
    val answer = result.answer?.let { value ->
        truncateExaContent(value, remaining)?.also { remaining -= it.length }
    }
    val items = buildList {
        for (item in result.items) {
            val fixedCharacters = item.title.length + item.url.length
            if (fixedCharacters > remaining) break
            val textLimit = minOf(EXA_MAX_SEARCH_ITEM_CHARS, remaining - fixedCharacters)
            val text = truncateExaContent(item.text, textLimit) ?: break
            add(item.copy(text = text))
            remaining -= fixedCharacters + text.length
        }
    }
    return result.copy(answer = answer, items = items)
}

internal fun limitExaScrapedResult(result: ScrapedResult): ScrapedResult {
    var remaining = EXA_MAX_TOTAL_OUTPUT_CHARS
    val urls = buildList {
        for (item in result.urls) {
            val fixedCharacters = item.url.length +
                (item.metadata?.title?.length ?: 0) +
                (item.metadata?.description?.length ?: 0) +
                (item.metadata?.language?.length ?: 0)
            if (fixedCharacters > remaining) break
            val contentLimit = minOf(EXA_MAX_SCRAPE_PAGE_CHARS, remaining - fixedCharacters)
            val content = truncateExaContent(item.content, contentLimit) ?: break
            add(item.copy(content = content))
            remaining -= fixedCharacters + content.length
        }
    }
    return result.copy(urls = urls)
}

private fun truncateExaContent(value: String, limit: Int): String? {
    if (value.length <= limit) return value
    if (limit < EXA_TRUNCATION_MARKER.length) return null
    return value.take(limit - EXA_TRUNCATION_MARKER.length) + EXA_TRUNCATION_MARKER
}
