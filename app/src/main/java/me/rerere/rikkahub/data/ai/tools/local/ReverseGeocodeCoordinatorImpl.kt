package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.data.datastore.ReverseGeocoderProviderKind
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.security.SecondUserSecretVault

internal class ReverseGeocodeCoordinatorImpl(
    private val androidBackend: ReverseGeocoderBackend,
    private val cache: ReverseGeocodeCache,
    private val diagnostics: ReverseGeocodeDiagnosticsStore,
    private val settingsStore: SettingsStore? = null,
    private val vault: SecondUserSecretVault? = null,
    private val http: ReverseGeocodeHttpClient? = null,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : ReverseGeocodeCoordinator {
    override suspend fun reverse(request: ReverseGeocodeRequest): ReverseGeocodeResolution {
        when (val validation = ReverseGeocodeRequestValidator.validate(request)) {
            is ReverseGeocodeRequestValidation.Invalid -> return ReverseGeocodeResolution.Failure(validation.error)
            is ReverseGeocodeRequestValidation.Valid -> return reverseValidated(validation.request)
        }
    }

    private suspend fun reverseValidated(request: ReverseGeocodeRequest): ReverseGeocodeResolution {
        val settings = settingsStore?.settingsFlow?.value?.reverseGeocodingSettings?.normalized()
        if (settings != null && !settings.enabled) return failure(
            code = "PROVIDER_DISABLED",
            message = "Reverse geocoding is disabled in local settings.",
            recovery = "Enable reverse geocoding in local settings.",
        )
        val selectedId = if (request.providerId == AUTO_REVERSE_GEOCODER_ID) {
            settings?.defaultProviderId ?: AUTO_REVERSE_GEOCODER_ID
        } else request.providerId

        if (selectedId !in setOf(AUTO_REVERSE_GEOCODER_ID, ANDROID_REVERSE_GEOCODER_ID)) {
            if (settings == null) return failure(
                code = "PROVIDER_NOT_CONFIGURED",
                message = "The selected reverse-geocoding provider is not configured.",
                recovery = "Configure the provider locally or use auto/android.",
            )
            if (!request.allowExternal || settings?.externalEnabled != true) return failure(
                code = "EXTERNAL_GEOCODING_DISABLED",
                message = "External reverse geocoding is not permitted by both local policy and this call.",
                recovery = "Enable external reverse geocoding locally and set allow_external=true.",
            )
            val backend = configuredBackend(selectedId) ?: return configuredProviderFailure(selectedId, settings)
            return executeBackend(request, backend, providerRevision(selectedId), explicit = true)
        }

        if (selectedId == ANDROID_REVERSE_GEOCODER_ID) {
            if (!request.allowPlatformGeocoder) return failure(
                code = "PLATFORM_GEOCODER_NOT_ALLOWED",
                message = "The Android platform geocoder is not allowed for this request.",
                recovery = "Set allow_platform_geocoder=true or choose an enabled external provider.",
            )
            return executeBackend(request, androidBackend, ANDROID_BACKEND_REVISION, explicit = true)
        }

        var lastFailure: ReverseGeocodeResolution.Failure? = null
        if (request.allowPlatformGeocoder) {
            when (val platform = executeBackend(request, androidBackend, ANDROID_BACKEND_REVISION, explicit = false)) {
                is ReverseGeocodeResolution.Success -> return platform
                is ReverseGeocodeResolution.Failure -> lastFailure = platform
            }
        }
        if (request.allowExternal && settings?.externalEnabled == true) {
            val configuredProviders = settings.providers
                .filter { it.enabled && it.type != ReverseGeocoderProviderKind.NOMINATIM }
                .sortedBy { it.priority }
            var lastExternal: ReverseGeocodeResolution? = null
            for (config in configuredProviders) {
                val backend = configuredBackend(config.id) ?: continue
                lastExternal = executeBackend(request, backend, config.configRevision, explicit = false)
                if (lastExternal is ReverseGeocodeResolution.Success) break
            }
            val external = lastExternal
            if (external != null) {
                val previousAttempts = lastFailure?.attemptedProviders.orEmpty()
                return when (external) {
                    is ReverseGeocodeResolution.Success -> external.copy(
                        attemptedProviders = (previousAttempts + external.attemptedProviders).distinct(),
                    )
                    is ReverseGeocodeResolution.Failure -> external.copy(
                        attemptedProviders = (previousAttempts + external.attemptedProviders).distinct(),
                    )
                }
            }
        }
        return lastFailure ?: failure(
            code = "NO_GEOCODER_RESULT",
            message = "No permitted reverse-geocoding backend returned a usable address.",
            recovery = "Allow the Android platform geocoder or explicitly enable a configured provider.",
        )
    }

    private fun configuredBackend(providerId: String): ReverseGeocoderBackend? {
        val config = settingsStore?.settingsFlow?.value?.reverseGeocodingSettings?.normalized()
            ?.providers?.firstOrNull { it.id == providerId && it.enabled }
            ?: return null
        val localHttp = http ?: return null
        return when (config.type) {
            ReverseGeocoderProviderKind.AMAP -> {
                val localVault = vault ?: return null
                AmapReverseGeocoder(config, localVault, localHttp)
            }
            ReverseGeocoderProviderKind.BIGDATA_CLOUD -> BigDataCloudReverseGeocoder(config, localHttp)
            ReverseGeocoderProviderKind.NOMINATIM -> null
        }
    }

    private fun configuredProviderFailure(
        providerId: String,
        settings: me.rerere.rikkahub.data.datastore.ReverseGeocodingSettings?,
    ): ReverseGeocodeResolution.Failure {
        val config = settings?.providers?.firstOrNull { it.id == providerId }
        return when {
            config == null -> failure("PROVIDER_NOT_CONFIGURED", "The selected reverse-geocoding provider is not configured.", "Configure the provider locally.")
            !config.enabled -> failure("PROVIDER_DISABLED", "The selected reverse-geocoding provider is disabled.", "Enable the provider locally.")
            else -> failure("PROVIDER_NOT_CONFIGURED", "This provider type has no verified adapter in the current build.", "Use Android or a configured Amap provider.")
        }
    }

    private fun providerRevision(providerId: String): Long = settingsStore?.settingsFlow?.value
        ?.reverseGeocodingSettings?.providers?.firstOrNull { it.id == providerId }?.configRevision ?: 1L

    private suspend fun executeBackend(
        request: ReverseGeocodeRequest,
        backend: ReverseGeocoderBackend,
        revision: Long,
        explicit: Boolean,
    ): ReverseGeocodeResolution {
        when (val cached = cache.get(request, backend.id, revision, backend.queryCoordinateSystem)) {
            ReverseGeocodeCacheLookup.Miss -> Unit
            is ReverseGeocodeCacheLookup.Success -> {
                diagnostics.recordCacheHit()
                return ReverseGeocodeResolution.Success(
                    address = cached.address.copy(coordinateDisclosure = CoordinateDisclosure.MEMORY_CACHE_ONLY),
                    cached = true,
                    cacheAgeMs = cached.ageMs,
                    attemptedProviders = emptyList(),
                )
            }
            is ReverseGeocodeCacheLookup.Failure -> {
                diagnostics.recordCacheHit()
                return ReverseGeocodeResolution.Failure(cached.error, attemptedProviders = emptyList())
            }
        }
        if (cache.isProviderCoolingDown(backend.id)) return ReverseGeocodeResolution.Failure(
            ReverseGeocodeError("PROVIDER_RATE_LIMITED", "The configured provider is temporarily cooling down.", "Try again later."),
            attemptedProviders = listOf(backend.id),
        )
        when (val availability = backend.availability(request)) {
            BackendAvailability.Available -> Unit
            is BackendAvailability.Disabled -> return failure(availability.code, "The provider is disabled.", "Enable it locally.")
            BackendAvailability.PlatformUnavailable -> return failure("ANDROID_GEOCODER_UNAVAILABLE", "The Android platform geocoder is unavailable.", "Configure an external provider or try another device.")
            BackendAvailability.MissingSecret -> return failure("PROVIDER_SECRET_UNAVAILABLE", "The provider secret is unavailable.", "Bind a local Vault slot to this provider.")
            is BackendAvailability.UnsupportedCoordinateSystem -> return failure("PROVIDER_RESPONSE_INVALID", "The configured coordinate system is unsupported by this adapter.", "Correct the local provider configuration.")
        }
        val started = monotonicNanos()
        val result = try {
            withTimeout(request.timeoutMs) { backend.reverse(request) }
        } catch (_: TimeoutCancellationException) {
            ReverseGeocodeResolution.Failure(
                ReverseGeocodeError(
                    code = if (backend.id == androidBackend.id) "ANDROID_GEOCODER_TIMEOUT" else "PROVIDER_TIMEOUT",
                    message = "The reverse-geocoding backend did not finish within the requested timeout.",
                    recovery = "Try again or increase timeout_ms within the allowed range.",
                ),
                attemptedProviders = listOf(backend.id),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        val durationMs = (monotonicNanos() - started).coerceAtLeast(0L) / 1_000_000L
        diagnostics.recordResult(backend.id, result, durationMs)
        when (result) {
            is ReverseGeocodeResolution.Success -> cache.putSuccess(request, backend.id, revision, backend.queryCoordinateSystem, result.address)
            is ReverseGeocodeResolution.Failure -> {
                if (result.error.code == "NO_GEOCODER_RESULT") {
                    cache.putNoResult(request, backend.id, revision, backend.queryCoordinateSystem, result.error)
                } else if (result.error.code == "PROVIDER_RATE_LIMITED") {
                    cache.setProviderCooldown(backend.id, PROVIDER_COOLDOWN_MS)
                }
            }
        }
        return when {
            explicit -> result
            result is ReverseGeocodeResolution.Success -> result
            else -> result
        }
    }

    private fun failure(code: String, message: String, recovery: String) = ReverseGeocodeResolution.Failure(
        ReverseGeocodeError(code = code, message = message, recovery = recovery),
    )

    private companion object {
        const val ANDROID_BACKEND_REVISION = 1L
        const val PROVIDER_COOLDOWN_MS = 60_000L
    }
}
