package me.rerere.rikkahub.browser

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object BrowserLibraryPolicy {
    private val sensitiveQueryNames = setOf(
        "code", "token", "access_token", "refresh_token", "id_token", "api_key",
        "apikey", "key", "signature", "sig", "x-amz-signature", "credential",
    )

    fun sanitizeForStorage(rawUrl: String): String = runCatching {
        val uri = URI(rawUrl.trim())
        val safeQuery = uri.rawQuery
            ?.split('&')
            ?.filter { part ->
                val encodedName = part.substringBefore('=')
                val name = URLDecoder.decode(encodedName, StandardCharsets.UTF_8)
                name.lowercase() !in sensitiveQueryNames
            }
            ?.joinToString("&")
            ?.takeIf(String::isNotBlank)
        URI(uri.scheme, uri.rawAuthority, uri.rawPath, safeQuery, null).toASCIIString()
    }.getOrElse { rawUrl.substringBefore('#') }

    fun normalize(rawUrl: String): String = runCatching {
        val safe = URI(sanitizeForStorage(rawUrl))
        val scheme = safe.scheme?.lowercase()
        val host = safe.host?.lowercase()
        val port = when {
            scheme == "https" && safe.port == 443 -> -1
            scheme == "http" && safe.port == 80 -> -1
            else -> safe.port
        }
        URI(scheme, safe.userInfo, host, port, safe.path.ifBlank { "/" }, safe.query, null)
            .toASCIIString()
            .removeSuffix("/")
    }.getOrElse { sanitizeForStorage(rawUrl).trim() }

    fun shouldRecordHistory(url: String, mainFrameSuccess: Boolean): Boolean {
        if (!mainFrameSuccess) return false
        val scheme = runCatching { URI(url).scheme?.lowercase() }.getOrNull()
        return scheme == "http" || scheme == "https"
    }
}
