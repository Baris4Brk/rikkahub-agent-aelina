package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import me.rerere.rikkahub.data.datastore.ReverseGeocoderProviderConfig
import me.rerere.rikkahub.utils.JsonInstant

/**
 * BigDataCloud free reverse-geocoding backend.
 *
 * No API key required. Free tier allows ~10k lookups per day, which is plenty
 * for an on-device assistant. The free client endpoint returns administrative
 * hierarchy (country / province / city / district / township) but no street-level
 * detail, so this backend intentionally reports ADMIN_BOUNDARY matches.
 *
 * Configured endpoint template:
 *   https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=..&longitude=..&localityLanguage=..
 */
internal class BigDataCloudReverseGeocoder(
    private val config: ReverseGeocoderProviderConfig,
    private val http: ReverseGeocodeHttpClient,
) : ReverseGeocoderBackend {
    override val id: String = config.id
    override val disclosure: CoordinateDisclosure = CoordinateDisclosure.CONFIGURED_EXTERNAL
    override val queryCoordinateSystem: CoordinateSystem = config.queryCoordinateSystem

    override suspend fun availability(request: ReverseGeocodeRequest): BackendAvailability {
        if (!config.enabled) return BackendAvailability.Disabled()
        return BackendAvailability.Available
    }

    override suspend fun reverse(request: ReverseGeocodeRequest): ReverseGeocodeResolution {
        val outbound = buildRequest(request) ?: return bigDataCloudFailure(id, "PROVIDER_RESPONSE_INVALID")
        return when (val response = http.getJson(outbound)) {
            is ReverseGeocodeHttpResult.Failure -> bigDataCloudFailure(id, response.code)
            is ReverseGeocodeHttpResult.Json -> BigDataCloudResponseParser.parse(
                providerId = id,
                queryCoordinateSystem = queryCoordinateSystem,
                body = response.body,
                request = request,
            )
        }
    }

    private fun buildRequest(request: ReverseGeocodeRequest): Request? {
        val base = config.endpoint.toHttpUrlOrNull() ?: return null
        if (!base.isHttps || base.username.isNotEmpty() || base.password.isNotEmpty() || base.fragment != null) return null
        val language = request.languageTag.substringBefore('-').ifBlank { "en" }
        val url = base.newBuilder()
            .removeAllQueryParameters("latitude")
            .removeAllQueryParameters("longitude")
            .removeAllQueryParameters("localityLanguage")
            .addQueryParameter("latitude", request.latitude.toString())
            .addQueryParameter("longitude", request.longitude.toString())
            .addQueryParameter("localityLanguage", language)
            .build()
        return Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "RikkaHub-Android/reverse-geocoder")
            .build()
    }
}

internal object BigDataCloudResponseParser {
    fun parse(
        providerId: String,
        queryCoordinateSystem: CoordinateSystem,
        body: String,
        request: ReverseGeocodeRequest,
    ): ReverseGeocodeResolution {
        val root = runCatching { JsonInstant.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return bigDataCloudFailure(providerId, "PROVIDER_RESPONSE_INVALID")
        val country = root.text("countryName")
        val countryCode = root.text("countryCode")
        val province = root.text("principalSubdivision")
        val city = root.text("city")
        val district = root.text("locality")
        val township = (root["localityInfo"] as? JsonObject)
            ?.let { info -> (info["administrative"] as? JsonArray)?.mapNotNull { it as? JsonObject } }
            ?.firstOrNull { it.int("adminLevel") == 8 }
            ?.text("name")
        val formatted = listOfNotNull(country, province, city, district, township).joinToString(" ")
        val address = StructuredAddress(
            formattedAddress = formatted,
            country = country,
            countryCode = countryCode,
            province = province,
            city = city,
            district = district,
            township = township,
            provider = providerId,
            inputCoordinateSystem = CoordinateSystem.WGS84,
            queryCoordinateSystem = queryCoordinateSystem,
            matchType = AddressMatchType.ADMIN_BOUNDARY,
            achievedDetail = inferAchievedAddressDetail(
                country = country,
                province = province,
                city = city,
                district = district,
            ),
            isExact = false,
            coordinateDisclosure = CoordinateDisclosure.CONFIGURED_EXTERNAL,
            explicitExternalProvider = true,
            attribution = "BigDataCloud",
            requestedLanguage = request.languageTag,
        ).normalized()
        return if (address.hasUsableAddressField()) {
            ReverseGeocodeResolution.Success(address, attemptedProviders = listOf(providerId))
        } else bigDataCloudFailure(providerId, "NO_GEOCODER_RESULT")
    }
}

private fun bigDataCloudFailure(providerId: String, code: String): ReverseGeocodeResolution.Failure =
    ReverseGeocodeResolution.Failure(
        error = ReverseGeocodeError(
            code = code,
            message = when (code) {
                "PROVIDER_RATE_LIMITED" -> "The configured provider is temporarily rate limited."
                "NO_GEOCODER_RESULT" -> "The configured provider returned no usable address."
                else -> "The configured provider request failed."
            },
        ),
        attemptedProviders = listOf(providerId),
    )

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
