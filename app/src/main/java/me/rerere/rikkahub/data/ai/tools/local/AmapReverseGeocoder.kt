package me.rerere.rikkahub.data.ai.tools.local

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.datastore.ReverseGeocoderProviderConfig
import me.rerere.rikkahub.security.SecretBinding
import me.rerere.rikkahub.security.SecretBindingKind
import me.rerere.rikkahub.security.SecretLeaseResult
import me.rerere.rikkahub.security.SecondUserSecretVault
import me.rerere.rikkahub.utils.JsonInstant

internal class AmapReverseGeocoder(
    private val config: ReverseGeocoderProviderConfig,
    private val vault: SecondUserSecretVault,
    private val http: ReverseGeocodeHttpClient,
) : ReverseGeocoderBackend {
    override val id: String = config.id
    override val disclosure: CoordinateDisclosure = CoordinateDisclosure.CONFIGURED_EXTERNAL
    override val queryCoordinateSystem: CoordinateSystem = config.queryCoordinateSystem

    override suspend fun availability(request: ReverseGeocodeRequest): BackendAvailability {
        if (!config.enabled) return BackendAvailability.Disabled()
        if (queryCoordinateSystem == CoordinateSystem.BD09) {
            return BackendAvailability.UnsupportedCoordinateSystem(queryCoordinateSystem)
        }
        val subject = SecondUserAuthorityRegistry.current()?.subjectId ?: return BackendAvailability.MissingSecret
        return if (findBinding(subject) == null) BackendAvailability.MissingSecret else BackendAvailability.Available
    }

    override suspend fun reverse(request: ReverseGeocodeRequest): ReverseGeocodeResolution {
        val subject = SecondUserAuthorityRegistry.current()?.subjectId
            ?: return failure("PROVIDER_SECRET_UNAVAILABLE")
        val slotAndBinding = findBinding(subject) ?: return failure("PROVIDER_SECRET_UNAVAILABLE")
        val coordinate = when (queryCoordinateSystem) {
            CoordinateSystem.WGS84 -> CoordinatePair(request.latitude, request.longitude)
            CoordinateSystem.GCJ02 -> Wgs84Gcj02Converter.convert(request.latitude, request.longitude)
            CoordinateSystem.BD09 -> return failure("PROVIDER_RESPONSE_INVALID")
        }
        val leasedRequest = vault.withLease(
            slotId = slotAndBinding.first,
            subjectId = subject,
            binding = slotAndBinding.second,
        ) { lease ->
            lease.use { secret -> buildRequest(coordinate, request.detailLevel, secret.concatToString()) }
        }
        val outbound = when (leasedRequest) {
            is SecretLeaseResult.Success -> leasedRequest.value
            else -> return failure("PROVIDER_SECRET_UNAVAILABLE")
        } ?: return failure("PROVIDER_RESPONSE_INVALID")

        return when (val response = http.getJson(outbound)) {
            is ReverseGeocodeHttpResult.Failure -> failure(response.code)
            is ReverseGeocodeHttpResult.Json -> AmapResponseParser.parse(
                providerId = id,
                queryCoordinateSystem = queryCoordinateSystem,
                body = response.body,
                request = request,
            )
        }
    }

    private suspend fun findBinding(subjectId: String): Pair<String, SecretBinding>? =
        vault.listMetadata(subjectId).firstNotNullOfOrNull { slot ->
            slot.bindings.firstOrNull {
                it.kind == SecretBindingKind.REVERSE_GEOCODER && it.targetId == id
            }?.let { slot.slotId to it }
        }

    private fun buildRequest(
        coordinate: CoordinatePair,
        detail: AddressDetailLevel,
        key: String,
    ): Request? {
        val base = config.endpoint.toHttpUrlOrNull() ?: return null
        if (!base.isHttps || base.username.isNotEmpty() || base.password.isNotEmpty() || base.fragment != null) return null
        val extensions = if (detail == AddressDetailLevel.POI) "all" else "base"
        val location = String.format(Locale.ROOT, "%.6f,%.6f", coordinate.longitude, coordinate.latitude)
        val url = base.newBuilder()
            .removeAllQueryParameters("key")
            .removeAllQueryParameters("location")
            .removeAllQueryParameters("output")
            .removeAllQueryParameters("extensions")
            .addQueryParameter("key", key)
            .addQueryParameter("location", location)
            .addQueryParameter("output", "JSON")
            .addQueryParameter("extensions", extensions)
            .build()
        return Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "RikkaHub-Android/reverse-geocoder")
            .build()
    }

    private fun failure(code: String): ReverseGeocodeResolution.Failure = amapFailure(id, code)
}

internal object AmapResponseParser {
    fun parse(
        providerId: String,
        queryCoordinateSystem: CoordinateSystem,
        body: String,
        request: ReverseGeocodeRequest,
    ): ReverseGeocodeResolution {
        val root = runCatching { JsonInstant.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return amapFailure(providerId, "PROVIDER_RESPONSE_INVALID")
        if (root.text("status") != "1") {
            val code = when (root.text("infocode")) {
                "10001", "10002", "10005", "10006", "10007", "10008", "10009" -> "PROVIDER_AUTH_FAILED"
                "10003", "10004", "10010", "10019", "10020", "10021" -> "PROVIDER_RATE_LIMITED"
                else -> "PROVIDER_NETWORK_FAILED"
            }
            return amapFailure(providerId, code)
        }
        val regeocode = root["regeocode"] as? JsonObject ?: return amapFailure(providerId, "NO_GEOCODER_RESULT")
        val component = regeocode["addressComponent"] as? JsonObject ?: JsonObject(emptyMap())
        val streetNumber = component["streetNumber"] as? JsonObject ?: JsonObject(emptyMap())
        val poi = (regeocode["pois"] as? JsonArray)?.firstOrNull() as? JsonObject
        val poiName = if (request.detailLevel == AddressDetailLevel.POI) poi?.text("name") else null
        val houseNumber = streetNumber.text("number")
        val road = streetNumber.text("street")
        val matchedDistance = when {
            poiName != null -> poi?.text("distance")?.toDoubleOrNull()
            !houseNumber.isNullOrBlank() || !road.isNullOrBlank() -> streetNumber.text("distance")?.toDoubleOrNull()
            else -> null
        }
        val address = StructuredAddress(
            formattedAddress = regeocode.text("formatted_address"),
            country = component.text("country"),
            province = component.text("province"),
            city = component.text("city"),
            district = component.text("district"),
            township = component.text("township"),
            road = road,
            houseNumber = houseNumber,
            poiName = poiName,
            provider = providerId,
            inputCoordinateSystem = CoordinateSystem.WGS84,
            queryCoordinateSystem = queryCoordinateSystem,
            matchType = when {
                poiName != null -> AddressMatchType.POI
                !houseNumber.isNullOrBlank() -> AddressMatchType.BUILDING
                else -> AddressMatchType.APPROXIMATE
            },
            achievedDetail = inferAchievedAddressDetail(
                country = component.text("country"),
                province = component.text("province"),
                city = component.text("city"),
                district = component.text("district"),
                road = road,
                houseNumber = houseNumber,
                poiName = poiName,
            ),
            matchedDistanceM = matchedDistance,
            isExact = false,
            coordinateDisclosure = CoordinateDisclosure.CONFIGURED_EXTERNAL,
            explicitExternalProvider = true,
            attribution = "高德地图",
            requestedLanguage = request.languageTag,
        ).normalized()
        return if (address.hasUsableAddressField()) {
            ReverseGeocodeResolution.Success(address, attemptedProviders = listOf(providerId))
        } else amapFailure(providerId, "NO_GEOCODER_RESULT")
    }
}

private fun amapFailure(providerId: String, code: String): ReverseGeocodeResolution.Failure = ReverseGeocodeResolution.Failure(
        error = ReverseGeocodeError(
            code = code,
            message = when (code) {
                "PROVIDER_SECRET_UNAVAILABLE" -> "The configured provider secret is unavailable."
                "PROVIDER_AUTH_FAILED" -> "The configured provider rejected its local credential."
                "PROVIDER_RATE_LIMITED" -> "The configured provider is temporarily rate limited."
                "NO_GEOCODER_RESULT" -> "No permitted reverse-geocoding backend returned a usable address."
                "PROVIDER_RESPONSE_INVALID" -> "The configured provider returned an unusable response."
                else -> "The configured provider request failed."
            },
            recovery = "Check the local provider configuration and try again.",
        ),
        attemptedProviders = listOf(providerId),
    )

private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)
    ?.takeIf { it.isString }
    ?.contentOrNull
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
