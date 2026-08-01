package me.rerere.rikkahub.data.ai.tools.local

import java.util.IllformedLocaleException
import java.util.Locale

internal const val MIN_REVERSE_GEOCODE_TIMEOUT_MS = 1_000L
internal const val MAX_REVERSE_GEOCODE_TIMEOUT_MS = 15_000L
internal const val DEFAULT_REVERSE_GEOCODE_TIMEOUT_MS = 5_000L
internal const val DEFAULT_REVERSE_GEOCODE_LANGUAGE = "zh-CN"
internal const val ANDROID_REVERSE_GEOCODER_ID = "android"
internal const val AUTO_REVERSE_GEOCODER_ID = "auto"

private const val MAX_ADDRESS_FIELD_LENGTH = 512
private const val MAX_FORMATTED_ADDRESS_LENGTH = 2_048
private val PROVIDER_ID_PATTERN = Regex("[a-z0-9][a-z0-9_-]{0,63}")

internal enum class AddressFailureMode(val wireName: String) {
    BEST_EFFORT("best_effort"),
    REQUIRED("required"),
    ;

    companion object {
        fun fromWireName(value: String): AddressFailureMode? = entries.firstOrNull { it.wireName == value }
    }
}

internal enum class AddressDetailLevel(val wireName: String, internal val rank: Int) {
    ADMIN("admin", 0),
    CITY("city", 1),
    DISTRICT("district", 2),
    STREET("street", 3),
    POI("poi", 4),
    ;

    companion object {
        fun fromWireName(value: String): AddressDetailLevel? = entries.firstOrNull { it.wireName == value }
    }
}

internal enum class AddressMatchType(val wireName: String) {
    EXACT_ADDRESS("exact_address"),
    BUILDING("building"),
    POI("poi"),
    NEAREST_ROAD("nearest_road"),
    ADMIN_BOUNDARY("admin_boundary"),
    APPROXIMATE("approximate"),
    UNKNOWN("unknown"),
}

internal enum class CoordinateSystem {
    WGS84,
    GCJ02,
    BD09,
}

internal enum class CoordinateDisclosure(val wireName: String) {
    MEMORY_CACHE_ONLY("memory_cache_only"),
    PLATFORM_GEOCODER_UNKNOWN("platform_geocoder_unknown"),
    CONFIGURED_EXTERNAL("configured_external"),
}

internal data class ReverseGeocodeRequest(
    val latitude: Double,
    val longitude: Double,
    val providerId: String = AUTO_REVERSE_GEOCODER_ID,
    val languageTag: String = DEFAULT_REVERSE_GEOCODE_LANGUAGE,
    val detailLevel: AddressDetailLevel = AddressDetailLevel.STREET,
    val allowPlatformGeocoder: Boolean = true,
    val allowExternal: Boolean = false,
    val timeoutMs: Long = DEFAULT_REVERSE_GEOCODE_TIMEOUT_MS,
)

internal data class StructuredAddress(
    val formattedAddress: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val province: String? = null,
    val city: String? = null,
    val district: String? = null,
    val township: String? = null,
    val village: String? = null,
    val road: String? = null,
    val houseNumber: String? = null,
    val postalCode: String? = null,
    val poiName: String? = null,
    val provider: String,
    val inputCoordinateSystem: CoordinateSystem = CoordinateSystem.WGS84,
    val queryCoordinateSystem: CoordinateSystem,
    val matchType: AddressMatchType = AddressMatchType.UNKNOWN,
    val achievedDetail: AddressDetailLevel,
    val matchedDistanceM: Double? = null,
    val isExact: Boolean = false,
    val coordinateDisclosure: CoordinateDisclosure,
    val explicitExternalProvider: Boolean,
    val attribution: String? = null,
    val requestedLanguage: String? = null,
) {
    internal fun normalized(): StructuredAddress = copy(
        formattedAddress = formattedAddress.normalized(MAX_FORMATTED_ADDRESS_LENGTH),
        country = country.normalized(),
        countryCode = countryCode.normalized(),
        province = province.normalized(),
        city = city.normalized(),
        district = district.normalized(),
        township = township.normalized(),
        village = village.normalized(),
        road = road.normalized(),
        houseNumber = houseNumber.normalized(),
        postalCode = postalCode.normalized(),
        poiName = poiName.normalized(),
        attribution = attribution.normalized(),
        requestedLanguage = requestedLanguage.normalized(),
        matchedDistanceM = matchedDistanceM?.takeIf { it.isFinite() && it >= 0.0 },
    )

    internal fun hasUsableAddressField(): Boolean = listOf(
        formattedAddress,
        country,
        countryCode,
        province,
        city,
        district,
        township,
        village,
        road,
        houseNumber,
        postalCode,
        poiName,
    ).any { !it.isNullOrBlank() }
}

internal data class ReverseGeocodeError(
    val code: String,
    val message: String,
    val recovery: String? = null,
)

internal sealed interface ReverseGeocodeResolution {
    data class Success(
        val address: StructuredAddress,
        val cached: Boolean = false,
        val cacheAgeMs: Long? = null,
        val attemptedProviders: List<String> = emptyList(),
    ) : ReverseGeocodeResolution

    data class Failure(
        val error: ReverseGeocodeError,
        val attemptedProviders: List<String> = emptyList(),
    ) : ReverseGeocodeResolution
}

internal sealed interface BackendAvailability {
    data object Available : BackendAvailability
    data class Disabled(val code: String = "PROVIDER_DISABLED") : BackendAvailability
    data object PlatformUnavailable : BackendAvailability
    data object MissingSecret : BackendAvailability
    data class UnsupportedCoordinateSystem(val coordinateSystem: CoordinateSystem) : BackendAvailability
}

internal interface ReverseGeocoderBackend {
    val id: String
    val disclosure: CoordinateDisclosure
    val queryCoordinateSystem: CoordinateSystem

    suspend fun availability(request: ReverseGeocodeRequest): BackendAvailability
    suspend fun reverse(request: ReverseGeocodeRequest): ReverseGeocodeResolution
}

internal fun interface ReverseGeocodeCoordinator {
    suspend fun reverse(request: ReverseGeocodeRequest): ReverseGeocodeResolution
}

internal sealed interface ReverseGeocodeRequestValidation {
    data class Valid(val request: ReverseGeocodeRequest) : ReverseGeocodeRequestValidation
    data class Invalid(val error: ReverseGeocodeError) : ReverseGeocodeRequestValidation
}

internal object ReverseGeocodeRequestValidator {
    fun validate(request: ReverseGeocodeRequest): ReverseGeocodeRequestValidation {
        val error = when {
            !request.latitude.isFinite() || request.latitude !in -90.0..90.0 ->
                invalid("latitude must be a finite number between -90 and 90")
            !request.longitude.isFinite() || request.longitude !in -180.0..180.0 ->
                invalid("longitude must be a finite number between -180 and 180")
            !isValidProviderId(request.providerId) ->
                invalid("provider must be a lowercase configured provider ID")
            normalizeLanguageTag(request.languageTag) == null ->
                invalid("language must be a valid BCP 47 language tag with 2 to 35 characters")
            request.timeoutMs !in MIN_REVERSE_GEOCODE_TIMEOUT_MS..MAX_REVERSE_GEOCODE_TIMEOUT_MS ->
                invalid("timeout_ms must be between 1000 and 15000")
            request.providerId == ANDROID_REVERSE_GEOCODER_ID && !request.allowPlatformGeocoder ->
                ReverseGeocodeError(
                    code = "PLATFORM_GEOCODER_NOT_ALLOWED",
                    message = "The Android platform geocoder is not allowed for this request.",
                    recovery = "Set allow_platform_geocoder to true or choose a configured provider.",
                )
            request.providerId !in setOf(AUTO_REVERSE_GEOCODER_ID, ANDROID_REVERSE_GEOCODER_ID) &&
                !request.allowExternal -> ReverseGeocodeError(
                    code = "EXTERNAL_GEOCODING_DISABLED",
                    message = "A configured external provider was selected without explicit permission.",
                    recovery = "Set allow_external to true only if coordinate disclosure is acceptable.",
                )
            else -> null
        }
        return if (error == null) {
            ReverseGeocodeRequestValidation.Valid(
                request.copy(languageTag = normalizeLanguageTag(request.languageTag)!!),
            )
        } else {
            ReverseGeocodeRequestValidation.Invalid(error)
        }
    }

    internal fun normalizeLanguageTag(raw: String): String? {
        if (raw.length !in 2..35 || raw != raw.trim()) return null
        return try {
            val locale = Locale.Builder().setLanguageTag(raw).build()
            locale.takeUnless { it == Locale.ROOT || it.language.isBlank() || it.language == "und" }
                ?.toLanguageTag()
        } catch (_: IllformedLocaleException) {
            null
        }
    }

    internal fun isValidProviderId(raw: String): Boolean = PROVIDER_ID_PATTERN.matches(raw)

    private fun invalid(message: String) = ReverseGeocodeError(
        code = "INVALID_ARGUMENT",
        message = message,
        recovery = "Correct the reverse-geocoding parameters and try again.",
    )
}

internal fun inferAchievedAddressDetail(
    country: String? = null,
    province: String? = null,
    city: String? = null,
    district: String? = null,
    road: String? = null,
    houseNumber: String? = null,
    poiName: String? = null,
): AddressDetailLevel = when {
    !poiName.isNullOrBlank() -> AddressDetailLevel.POI
    !road.isNullOrBlank() || !houseNumber.isNullOrBlank() -> AddressDetailLevel.STREET
    !district.isNullOrBlank() -> AddressDetailLevel.DISTRICT
    !city.isNullOrBlank() -> AddressDetailLevel.CITY
    !province.isNullOrBlank() || !country.isNullOrBlank() -> AddressDetailLevel.ADMIN
    else -> AddressDetailLevel.ADMIN
}

private fun String?.normalized(maxLength: Int = MAX_ADDRESS_FIELD_LENGTH): String? =
    this?.trim()?.takeIf { it.isNotEmpty() }?.take(maxLength)
