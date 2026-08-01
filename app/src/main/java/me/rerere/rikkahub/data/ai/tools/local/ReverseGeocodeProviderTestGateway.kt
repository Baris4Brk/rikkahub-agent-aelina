package me.rerere.rikkahub.data.ai.tools.local

data class ReverseGeocodeProviderTestResult(
    val ok: Boolean,
    val code: String,
    val providerId: String,
    val achievedDetail: String? = null,
)

class ReverseGeocodeProviderTestGateway internal constructor(
    private val coordinator: ReverseGeocodeCoordinator,
) {
    suspend fun test(
        providerId: String,
        latitude: Double = DEFAULT_TEST_LATITUDE,
        longitude: Double = DEFAULT_TEST_LONGITUDE,
    ): ReverseGeocodeProviderTestResult = when (val result = coordinator.reverse(
        ReverseGeocodeRequest(
            latitude = latitude,
            longitude = longitude,
            providerId = providerId,
            allowPlatformGeocoder = false,
            allowExternal = true,
            timeoutMs = DEFAULT_REVERSE_GEOCODE_TIMEOUT_MS,
        ),
    )) {
        is ReverseGeocodeResolution.Success -> ReverseGeocodeProviderTestResult(
            ok = true,
            code = "PROVIDER_TEST_SUCCEEDED",
            providerId = result.address.provider,
            achievedDetail = result.address.achievedDetail.wireName,
        )
        is ReverseGeocodeResolution.Failure -> ReverseGeocodeProviderTestResult(
            ok = false,
            code = result.error.code,
            providerId = providerId,
        )
    }

    private companion object {
        // Fixed public coordinate used when the user did not explicitly supply one.
        const val DEFAULT_TEST_LATITUDE = 39.9042
        const val DEFAULT_TEST_LONGITUDE = 116.4074
    }
}
