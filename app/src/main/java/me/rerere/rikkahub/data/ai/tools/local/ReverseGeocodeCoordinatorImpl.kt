package me.rerere.rikkahub.data.ai.tools.local

internal class ReverseGeocodeCoordinatorImpl(
    private val androidBackend: ReverseGeocoderBackend,
    private val cache: ReverseGeocodeCache,
    private val diagnostics: ReverseGeocodeDiagnosticsStore,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : ReverseGeocodeCoordinator {
    override suspend fun reverse(request: ReverseGeocodeRequest): ReverseGeocodeResolution {
        when (val validation = ReverseGeocodeRequestValidator.validate(request)) {
            is ReverseGeocodeRequestValidation.Invalid -> return ReverseGeocodeResolution.Failure(validation.error)
            is ReverseGeocodeRequestValidation.Valid -> return reverseValidated(validation.request)
        }
    }

    private suspend fun reverseValidated(request: ReverseGeocodeRequest): ReverseGeocodeResolution {
        if (request.providerId !in setOf(AUTO_REVERSE_GEOCODER_ID, ANDROID_REVERSE_GEOCODER_ID)) {
            return failure(
                code = "PROVIDER_NOT_CONFIGURED",
                message = "The selected reverse-geocoding provider is not configured.",
                recovery = "Configure the provider locally or use auto/android.",
            )
        }
        if (!request.allowPlatformGeocoder) {
            return failure(
                code = "NO_GEOCODER_RESULT",
                message = "No permitted reverse-geocoding backend is available.",
                recovery = "Allow the Android platform geocoder or configure an external provider.",
            )
        }

        when (
            val cached = cache.get(
                request = request,
                providerId = androidBackend.id,
                providerRevision = ANDROID_BACKEND_REVISION,
                coordinateSystem = androidBackend.queryCoordinateSystem,
            )
        ) {
            ReverseGeocodeCacheLookup.Miss -> Unit
            is ReverseGeocodeCacheLookup.Success -> {
                diagnostics.recordCacheHit()
                return ReverseGeocodeResolution.Success(
                    address = cached.address.copy(
                        coordinateDisclosure = CoordinateDisclosure.MEMORY_CACHE_ONLY,
                    ),
                    cached = true,
                    cacheAgeMs = cached.ageMs,
                    attemptedProviders = emptyList(),
                )
            }
            is ReverseGeocodeCacheLookup.Failure -> {
                diagnostics.recordCacheHit()
                return ReverseGeocodeResolution.Failure(
                    error = cached.error,
                    attemptedProviders = emptyList(),
                )
            }
        }

        val started = monotonicNanos()
        val result = androidBackend.reverse(request)
        val durationMs = ((monotonicNanos() - started).coerceAtLeast(0L) / 1_000_000L)
        diagnostics.recordResult(androidBackend.id, result, durationMs)
        when (result) {
            is ReverseGeocodeResolution.Success -> cache.putSuccess(
                request = request,
                providerId = androidBackend.id,
                providerRevision = ANDROID_BACKEND_REVISION,
                coordinateSystem = androidBackend.queryCoordinateSystem,
                address = result.address,
            )
            is ReverseGeocodeResolution.Failure -> if (result.error.code == "NO_GEOCODER_RESULT") {
                cache.putNoResult(
                    request = request,
                    providerId = androidBackend.id,
                    providerRevision = ANDROID_BACKEND_REVISION,
                    coordinateSystem = androidBackend.queryCoordinateSystem,
                    error = result.error,
                )
            }
        }
        return result
    }

    private fun failure(code: String, message: String, recovery: String) = ReverseGeocodeResolution.Failure(
        ReverseGeocodeError(code = code, message = message, recovery = recovery),
    )

    private companion object {
        const val ANDROID_BACKEND_REVISION = 1L
    }
}
