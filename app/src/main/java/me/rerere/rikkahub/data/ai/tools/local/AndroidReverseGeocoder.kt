package me.rerere.rikkahub.data.ai.tools.local

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal class AndroidReverseGeocoder(
    private val client: AndroidGeocoderClient,
) : ReverseGeocoderBackend {
    override val id: String = ANDROID_REVERSE_GEOCODER_ID
    override val disclosure: CoordinateDisclosure = CoordinateDisclosure.PLATFORM_GEOCODER_UNKNOWN
    override val queryCoordinateSystem: CoordinateSystem = CoordinateSystem.WGS84

    override suspend fun availability(request: ReverseGeocodeRequest): BackendAvailability = when {
        !request.allowPlatformGeocoder -> BackendAvailability.Disabled("PLATFORM_GEOCODER_NOT_ALLOWED")
        !client.isPresent() -> BackendAvailability.PlatformUnavailable
        else -> BackendAvailability.Available
    }

    override suspend fun reverse(request: ReverseGeocodeRequest): ReverseGeocodeResolution {
        when (val available = availability(request)) {
            BackendAvailability.Available -> Unit
            BackendAvailability.PlatformUnavailable -> return failure(
                code = "ANDROID_GEOCODER_UNAVAILABLE",
                message = "The Android platform geocoder is unavailable on this device.",
                recovery = "Choose a configured provider or try on a device with a geocoder service.",
            )
            is BackendAvailability.Disabled -> return failure(
                code = available.code,
                message = "The Android platform geocoder is not allowed for this request.",
                recovery = "Allow the platform geocoder or choose a configured provider.",
            )
            BackendAvailability.MissingSecret,
            is BackendAvailability.UnsupportedCoordinateSystem,
            -> return failure(
                code = "ANDROID_GEOCODER_UNAVAILABLE",
                message = "The Android platform geocoder is unavailable for this request.",
            )
        }

        val locale = ReverseGeocodeRequestValidator.normalizeLanguageTag(request.languageTag)
            ?.let(Locale::forLanguageTag)
            ?: return failure(
                code = "INVALID_ARGUMENT",
                message = "language must be a valid BCP 47 language tag with 2 to 35 characters",
                recovery = "Correct the language parameter and try again.",
            )

        return try {
            val candidates = withTimeout(request.timeoutMs) {
                client.query(
                    latitude = request.latitude,
                    longitude = request.longitude,
                    maxResults = MAX_ANDROID_GEOCODER_RESULTS,
                    locale = locale,
                )
            }
            val address = selectCandidate(candidates, request.languageTag)
                ?: return failure(
                    code = "NO_GEOCODER_RESULT",
                    message = "The Android platform geocoder returned no usable address.",
                    recovery = "Try again or explicitly enable a configured provider.",
                )
            ReverseGeocodeResolution.Success(
                address = address,
                attemptedProviders = listOf(id),
            )
        } catch (_: TimeoutCancellationException) {
            failure(
                code = "ANDROID_GEOCODER_TIMEOUT",
                message = "The Android platform geocoder did not respond before the timeout.",
                recovery = "Try again or explicitly enable a configured provider.",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failure(
                code = "ANDROID_GEOCODER_FAILED",
                message = "The Android platform geocoder failed.",
                recovery = "Try again or explicitly enable a configured provider.",
            )
        }
    }

    private fun selectCandidate(
        candidates: List<AndroidGeocodeCandidate>,
        requestedLanguage: String,
    ): StructuredAddress? = candidates
        .mapIndexedNotNull { index, raw ->
            val candidate = raw.normalized()
            if (!candidate.hasUsableAddressField()) return@mapIndexedNotNull null
            val score = candidate.completenessScore() - index * CANDIDATE_RANK_PENALTY
            CandidateScore(index, score, candidate)
        }
        .maxWithOrNull(compareBy<CandidateScore> { it.score }.thenBy { -it.index })
        ?.candidate
        ?.let { candidate ->
            StructuredAddress(
                formattedAddress = candidate.formattedAddress,
                country = candidate.country,
                countryCode = candidate.countryCode,
                province = candidate.province,
                city = candidate.city,
                district = candidate.district,
                township = candidate.township,
                road = candidate.road,
                houseNumber = candidate.houseNumber,
                postalCode = candidate.postalCode,
                provider = "android_geocoder",
                queryCoordinateSystem = CoordinateSystem.WGS84,
                matchType = AddressMatchType.APPROXIMATE,
                achievedDetail = inferAchievedAddressDetail(
                    country = candidate.country,
                    province = candidate.province,
                    city = candidate.city,
                    district = candidate.district,
                    road = candidate.road,
                    houseNumber = candidate.houseNumber,
                ),
                isExact = false,
                coordinateDisclosure = CoordinateDisclosure.PLATFORM_GEOCODER_UNKNOWN,
                explicitExternalProvider = false,
                requestedLanguage = requestedLanguage,
            ).normalized()
        }

    private fun failure(
        code: String,
        message: String,
        recovery: String? = null,
    ) = ReverseGeocodeResolution.Failure(
        error = ReverseGeocodeError(code, message, recovery),
        attemptedProviders = listOf(id),
    )

    private data class CandidateScore(
        val index: Int,
        val score: Int,
        val candidate: AndroidGeocodeCandidate,
    )

    private companion object {
        const val MAX_ANDROID_GEOCODER_RESULTS = 3
        const val CANDIDATE_RANK_PENALTY = 2
    }
}

private fun AndroidGeocodeCandidate.normalized(): AndroidGeocodeCandidate = copy(
    formattedAddress = formattedAddress.clean(2_048),
    country = country.clean(),
    countryCode = countryCode.clean(),
    province = province.clean(),
    city = city.clean(),
    district = district.clean(),
    township = township.clean(),
    road = road.clean(),
    houseNumber = houseNumber.clean(),
    postalCode = postalCode.clean(),
)

private fun AndroidGeocodeCandidate.hasUsableAddressField(): Boolean = listOf(
    formattedAddress,
    country,
    countryCode,
    province,
    city,
    district,
    township,
    road,
    houseNumber,
    postalCode,
).any { !it.isNullOrBlank() }

private fun AndroidGeocodeCandidate.completenessScore(): Int =
    (if (formattedAddress != null) 4 else 0) +
        listOf(country, province, city, district, township).count { it != null } +
        (if (road != null) 3 else 0) +
        (if (houseNumber != null) 2 else 0) +
        (if (postalCode != null) 1 else 0)

private fun String?.clean(maxLength: Int = 512): String? =
    this?.trim()?.takeIf(String::isNotEmpty)?.take(maxLength)
