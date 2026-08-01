package me.rerere.rikkahub.data.datastore

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.ai.tools.local.CoordinateSystem

@Serializable
enum class ReverseGeocoderProviderKind {
    @SerialName("amap")
    AMAP,

    @SerialName("nominatim")
    NOMINATIM,

    @SerialName("bigdata_cloud")
    BIGDATA_CLOUD,
}

@Serializable
data class ReverseGeocoderProviderConfig(
    val id: String,
    val type: ReverseGeocoderProviderKind,
    val displayName: String,
    val endpoint: String,
    val enabled: Boolean = false,
    val priority: Int = 100,
    val queryCoordinateSystem: CoordinateSystem = CoordinateSystem.WGS84,
    val configRevision: Long = 1,
    val termsAcceptedAtMs: Long? = null,
)

@Serializable
data class ReverseGeocodingSettings(
    val enabled: Boolean = true,
    val externalEnabled: Boolean = false,
    val defaultProviderId: String = "auto",
    val providers: List<ReverseGeocoderProviderConfig> = emptyList(),
) {
    fun normalized(): ReverseGeocodingSettings {
        val normalizedProviders = providers
            .mapNotNull(ReverseGeocoderProviderConfig::normalizedOrNull)
            .distinctBy { it.id }
            .sortedWith(compareBy<ReverseGeocoderProviderConfig> { it.priority }.thenBy { it.id })
        val validDefault = defaultProviderId.takeIf { candidate ->
            candidate == "auto" || candidate == "android" || normalizedProviders.any { it.id == candidate }
        } ?: "auto"
        return copy(defaultProviderId = validDefault, providers = normalizedProviders)
    }
}

internal fun ReverseGeocoderProviderConfig.normalizedOrNull(): ReverseGeocoderProviderConfig? {
    val normalizedId = id.trim().lowercase()
    val normalizedEndpoint = endpoint.trim()
    if (!REVERSE_GEOCODER_PROVIDER_ID.matches(normalizedId) || normalizedId in setOf("auto", "android")) return null
    if (!isSafeReverseGeocoderEndpoint(normalizedEndpoint)) return null
    val normalizedName = displayName.trim().take(160).ifBlank { normalizedId }
    return copy(
        id = normalizedId,
        displayName = normalizedName,
        endpoint = normalizedEndpoint,
        priority = priority.coerceIn(0, 10_000),
        configRevision = configRevision.coerceAtLeast(1),
        termsAcceptedAtMs = termsAcceptedAtMs?.takeIf { it > 0 },
    )
}

internal fun isSafeReverseGeocoderEndpoint(value: String): Boolean {
    if (value.isBlank() || value.length > 2_048) return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    val queryNames = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).map { field ->
        runCatching {
            URLDecoder.decode(field.substringBefore('='), StandardCharsets.UTF_8.name()).lowercase()
        }.getOrDefault("[invalid]")
    }
    return uri.scheme.equals("https", ignoreCase = true) &&
        !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null &&
        uri.rawFragment == null &&
        "[invalid]" !in queryNames &&
        queryNames.none { it in SENSITIVE_ENDPOINT_QUERY_NAMES }
}

private val REVERSE_GEOCODER_PROVIDER_ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
private val SENSITIVE_ENDPOINT_QUERY_NAMES = setOf(
    "key", "api_key", "apikey", "token", "access_token", "secret", "password", "sig", "signature",
)
