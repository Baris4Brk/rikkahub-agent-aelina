package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal class AndroidLocationSource(
    private val platform: LocationPlatformAdapter,
    private val gms: GmsLocationAdapter?,
) : LocationResolver {

    constructor(context: Context) : this(
        platform = AndroidLocationPlatformAdapter(context),
        gms = GooglePlayServicesLocationAdapter(context),
    )

    override suspend fun resolve(request: LocationRequest): LocationResolution {
        val precision = platform.permissionPrecision()
        if (precision == PermissionPrecision.NONE) {
            return LocationResolution.Failure(
                code = "LOCATION_PERMISSION_MISSING",
                message = "Foreground location permission is not granted.",
                recovery = "Grant approximate or precise location permission.",
            )
        }
        if (!platform.isLocationEnabled()) {
            return LocationResolution.Failure(
                code = "LOCATION_SERVICES_DISABLED",
                message = "Android location services are disabled.",
                recovery = "Enable Location in system settings.",
            )
        }

        val startedMs = platform.elapsedRealtimeMs()
        val startedNanos = platform.elapsedRealtimeNanos()
        val deadlineMs = startedMs + request.timeoutMs
        val cachedFixes = if (request.allowCached) {
            runCatching { platform.collectCachedFixes() }.getOrDefault(emptyList())
                .map { fix ->
                    CachedLocationCandidate(
                        fix = fix,
                        ageMs = locationAgeMs(startedNanos, fix.elapsedRealtimeNanos),
                        source = LocationSourceKind.LAST_KNOWN_LOCATION,
                    )
                }
                .sortedWith(compareBy<CachedLocationCandidate> { it.ageMs }.thenBy { it.fix.accuracyM })
        } else {
            emptyList()
        }
        val directCache = cachedFixes.firstOrNull { candidate ->
            request.directCacheMaxAgeMs > 0L && candidate.ageMs <= request.directCacheMaxAgeMs
        }
        if (directCache != null) {
            return cachedSuccess(
                candidate = directCache,
                precision = precision,
                accuracy = request.accuracy,
                cacheStatus = "direct_fresh",
                warning = null,
            )
        }

        val providers = providerOrder(request.accuracy)
        val gmsAvailable = runCatching { gms?.isAvailable() == true }.getOrDefault(false)
        var lastFailure = if (providers.isEmpty() && !gmsAvailable) {
            unavailableProviderFailure()
        } else {
            null
        }
        val returnedOldFixes = mutableListOf<CachedLocationCandidate>()

        for (provider in providers) {
            val remainingMs = deadlineMs - platform.elapsedRealtimeMs()
            if (remainingMs <= 0L) break
            val budgetMs = providerBudget(request.accuracy, provider, remainingMs)
            when (val result = platform.requestCurrentLocation(provider, budgetMs, startedNanos)) {
                is NativeLocationResult.Success -> {
                    if (result.generatedAfterRequest) {
                        return success(
                            result = result,
                            precision = precision,
                            accuracy = request.accuracy,
                            source = LocationSourceKind.ANDROID_LOCATION_MANAGER,
                        )
                    }
                    if (request.allowCached) {
                        val candidate = CachedLocationCandidate(
                            fix = result.fix,
                            ageMs = result.ageMs.coerceAtLeast(0L),
                            source = LocationSourceKind.ANDROID_LOCATION_MANAGER,
                        )
                        returnedOldFixes += candidate
                    }
                }
                NativeLocationResult.PermissionDenied -> return LocationResolution.Failure(
                    code = "OEM_REJECTED",
                    message = "The device rejected the location request.",
                    recovery = "Review the app location permission and OEM privacy settings.",
                )
                NativeLocationResult.LocationDisabled -> return LocationResolution.Failure(
                    code = "LOCATION_SERVICES_DISABLED",
                    message = "Android location services are disabled.",
                    recovery = "Enable Location in system settings.",
                )
                is NativeLocationResult.ProviderUnavailable -> {
                    lastFailure = LocationResolution.Failure(
                        code = "NO_PROVIDER_AVAILABLE",
                        message = "Location provider ${result.provider} is unavailable.",
                    )
                }
                is NativeLocationResult.Timeout -> {
                    lastFailure = LocationResolution.Failure(
                        code = "PROVIDER_TIMEOUT",
                        message = "Location provider ${result.provider} timed out.",
                    )
                }
                is NativeLocationResult.Failure -> {
                    lastFailure = LocationResolution.Failure(result.code, result.message)
                }
            }
        }

        val gmsRemainingMs = deadlineMs - platform.elapsedRealtimeMs()
        if (gmsRemainingMs > 0L && gmsAvailable && gms != null) {
            when (
                val result = gms.requestCurrentLocation(
                    accuracy = request.accuracy,
                    timeoutMs = gmsRemainingMs,
                    requestStartedElapsedNanos = startedNanos,
                )
            ) {
                is NativeLocationResult.Success -> {
                    if (result.generatedAfterRequest) {
                        return success(
                            result = result,
                            precision = precision,
                            accuracy = request.accuracy,
                            source = LocationSourceKind.GMS_FUSED,
                        )
                    }
                    if (request.allowCached) {
                        returnedOldFixes += CachedLocationCandidate(
                            fix = result.fix,
                            ageMs = result.ageMs.coerceAtLeast(0L),
                            source = LocationSourceKind.GMS_FUSED,
                        )
                    }
                }
                NativeLocationResult.PermissionDenied -> {
                    lastFailure = LocationResolution.Failure(
                        code = "OEM_REJECTED",
                        message = "Google fused location was rejected by the device.",
                    )
                }
                NativeLocationResult.LocationDisabled -> return LocationResolution.Failure(
                    code = "LOCATION_SERVICES_DISABLED",
                    message = "Android location services are disabled.",
                    recovery = "Enable Location in system settings.",
                )
                is NativeLocationResult.ProviderUnavailable -> {
                    lastFailure = LocationResolution.Failure(
                        code = "GMS_UNAVAILABLE",
                        message = "Google fused location is unavailable.",
                    )
                }
                is NativeLocationResult.Timeout -> {
                    lastFailure = LocationResolution.Failure(
                        code = "GMS_LOCATION_FAILED",
                        message = "Google fused location timed out.",
                    )
                }
                is NativeLocationResult.Failure -> {
                    lastFailure = LocationResolution.Failure(
                        code = "GMS_LOCATION_FAILED",
                        message = result.message,
                    )
                }
            }
        }

        if (request.allowCached) {
            val fallback = (cachedFixes + returnedOldFixes)
                .sortedWith(compareBy<CachedLocationCandidate> { it.ageMs }.thenBy { it.fix.accuracyM })
                .firstOrNull()
            if (fallback != null && fallback.ageMs <= request.fallbackCacheMaxAgeMs) {
                return cachedSuccess(
                    candidate = fallback,
                    precision = precision,
                    accuracy = request.accuracy,
                    cacheStatus = "fallback_stale",
                    warning = "Fresh location unavailable; returning cached location.",
                )
            }
            if (fallback != null) {
                return LocationResolution.Failure(
                    code = "CACHED_LOCATION_TOO_OLD",
                    message = "The newest cached location is ${fallback.ageMs} ms old.",
                    recovery = "Move near a window or outdoors and request a fresh location.",
                )
            }
            if (providers.isEmpty() && !gmsAvailable) {
                return lastFailure ?: unavailableProviderFailure()
            }
            return LocationResolution.Failure(
                code = "NO_CACHED_LOCATION",
                message = "Fresh location attempts failed and no cached location is available.",
                recovery = "Move near a window or outdoors and request a fresh location.",
            )
        }

        return lastFailure ?: LocationResolution.Failure(
            code = "NATIVE_LOCATION_FAILED",
            message = "Android location providers did not return a current location.",
            recovery = "Move near a window or outdoors and try again.",
        )
    }

    private fun unavailableProviderFailure(): LocationResolution.Failure {
        val gpsExists = runCatching { platform.hasProvider(LocationProviders.GPS) }.getOrDefault(false)
        val gpsEnabled = gpsExists && runCatching {
            platform.isProviderEnabled(LocationProviders.GPS)
        }.getOrDefault(false)
        return if (gpsExists && !gpsEnabled) {
            LocationResolution.Failure(
                code = "GPS_PROVIDER_DISABLED",
                message = "The GPS provider exists but is disabled.",
                recovery = "Enable GPS in Android location settings.",
            )
        } else {
            LocationResolution.Failure(
                code = "NO_PROVIDER_AVAILABLE",
                message = "No enabled Android location provider is available.",
                recovery = "Enable GPS or network location in system settings.",
            )
        }
    }

    private fun providerOrder(accuracy: RequestedAccuracy): List<String> {
        val candidates = when (accuracy) {
            RequestedAccuracy.HIGH -> listOf(
                LocationProviders.GPS,
                LocationProviders.FUSED,
                LocationProviders.NETWORK,
            )
            RequestedAccuracy.BALANCED -> listOf(
                LocationProviders.FUSED,
                LocationProviders.NETWORK,
                LocationProviders.GPS,
            )
            RequestedAccuracy.LOW -> listOf(
                LocationProviders.NETWORK,
                LocationProviders.FUSED,
                LocationProviders.GPS,
            )
        }
        return candidates.distinct().filter { provider ->
            (provider != LocationProviders.FUSED || platform.sdkInt() >= 31) &&
                platform.hasProvider(provider) &&
                platform.isProviderEnabled(provider)
        }
    }

    private fun providerBudget(
        accuracy: RequestedAccuracy,
        provider: String,
        remainingMs: Long,
    ): Long {
        val cap = when (accuracy) {
            RequestedAccuracy.HIGH -> when (provider) {
                LocationProviders.GPS -> 25_000L
                LocationProviders.FUSED -> 8_000L
                else -> remainingMs
            }
            RequestedAccuracy.BALANCED -> when (provider) {
                LocationProviders.FUSED -> 10_000L
                LocationProviders.NETWORK -> 8_000L
                else -> remainingMs
            }
            RequestedAccuracy.LOW -> when (provider) {
                LocationProviders.NETWORK -> 8_000L
                else -> remainingMs
            }
        }
        return minOf(cap, remainingMs).coerceAtLeast(1L)
    }

    private fun success(
        result: NativeLocationResult.Success,
        precision: PermissionPrecision,
        accuracy: RequestedAccuracy,
        source: LocationSourceKind,
    ): LocationResolution.Success {
        val precisionLimited = precision == PermissionPrecision.COARSE
        val sourceType = when {
            precisionLimited -> LocationSourceType.APPROXIMATE
            result.provider == LocationProviders.GPS -> LocationSourceType.GNSS
            result.provider == LocationProviders.FUSED -> LocationSourceType.FUSED
            result.provider == LocationProviders.NETWORK -> LocationSourceType.NETWORK
            result.provider == LocationProviders.PASSIVE -> LocationSourceType.PASSIVE
            else -> LocationSourceType.UNKNOWN
        }
        return LocationResolution.Success(
            fix = result.fix,
            source = source,
            sourceType = sourceType,
            generatedAfterRequest = result.generatedAfterRequest,
            ageMs = result.ageMs.coerceAtLeast(0L),
            cached = !result.generatedAfterRequest,
            fresh = result.generatedAfterRequest,
            permissionPrecision = precision,
            requestedAccuracy = accuracy,
            warningCode = if (precisionLimited) "PRECISE_LOCATION_NOT_GRANTED" else null,
            warning = if (precisionLimited) {
                "Enable Precise location for satellite-level accuracy."
            } else {
                null
            },
        )
    }

    private fun cachedSuccess(
        candidate: CachedLocationCandidate,
        precision: PermissionPrecision,
        accuracy: RequestedAccuracy,
        cacheStatus: String,
        warning: String?,
    ): LocationResolution.Success {
        val result = NativeLocationResult.Success(
            fix = candidate.fix,
            provider = candidate.fix.provider,
            generatedAfterRequest = false,
            ageMs = candidate.ageMs,
        )
        val base = success(
            result = result,
            precision = precision,
            accuracy = accuracy,
            source = candidate.source,
        )
        val combinedWarning = listOfNotNull(base.warning, warning)
            .distinct()
            .joinToString(" ")
            .ifBlank { null }
        return base.copy(
            cacheStatus = cacheStatus,
            warning = combinedWarning,
        )
    }
}

private data class CachedLocationCandidate(
    val fix: LocationFix,
    val ageMs: Long,
    val source: LocationSourceKind,
)

private fun locationAgeMs(nowElapsedNanos: Long, locationElapsedNanos: Long): Long =
    ((nowElapsedNanos - locationElapsedNanos).coerceAtLeast(0L) / 1_000_000L)

private class AndroidLocationPlatformAdapter(
    context: Context,
) : LocationPlatformAdapter {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    override fun permissionPrecision(): PermissionPrecision {
        val fineGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return locationPermissionPrecision(fineGranted, coarseGranted)
    }

    override fun isLocationEnabled(): Boolean = locationManager?.let { manager ->
        runCatching { LocationManagerCompat.isLocationEnabled(manager) }.getOrDefault(false)
    } == true

    override fun sdkInt(): Int = Build.VERSION.SDK_INT

    override fun hasProvider(provider: String): Boolean = locationManager?.let { manager ->
        runCatching { LocationManagerCompat.hasProvider(manager, provider) }.getOrDefault(false)
    } == true

    override fun isProviderEnabled(provider: String): Boolean = locationManager?.let { manager ->
        runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
    } == true

    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()

    override fun collectCachedFixes(): List<LocationFix> {
        val manager = locationManager ?: return emptyList()
        return cacheProvidersForSdk(Build.VERSION.SDK_INT).mapNotNull { provider ->
            if (!runCatching { LocationManagerCompat.hasProvider(manager, provider) }.getOrDefault(false)) {
                return@mapNotNull null
            }
            try {
                manager.getLastKnownLocation(provider)?.toLocationFix(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }
    }

    override suspend fun requestCurrentLocation(
        provider: String,
        timeoutMs: Long,
        requestStartedElapsedNanos: Long,
    ): NativeLocationResult {
        val manager = locationManager ?: return NativeLocationResult.Failure(
            provider = provider,
            code = "PROVIDER_FAILURE",
            message = "LocationManager is unavailable.",
        )
        val available = runCatching {
            LocationManagerCompat.hasProvider(manager, provider) && isProviderEnabled(provider)
        }.getOrElse { failure ->
            return NativeLocationResult.Failure(
                provider = provider,
                code = "PROVIDER_FAILURE",
                message = failure.message ?: "Provider status probe failed.",
            )
        }
        if (!available) {
            return NativeLocationResult.ProviderUnavailable(provider)
        }
        val timed = try {
            awaitLocationSignalResult(timeoutMs) { signal, complete ->
                LocationManagerCompat.getCurrentLocation(
                    manager,
                    provider,
                    signal,
                    ContextCompat.getMainExecutor(appContext),
                ) { location ->
                    complete(
                        if (location == null) {
                            NativeLocationResult.Failure(
                                provider = provider,
                                code = "PROVIDER_RETURNED_NULL",
                                message = "Provider returned no location.",
                            )
                        } else {
                            location.toNativeSuccess(
                                provider = provider,
                                requestStartedElapsedNanos = requestStartedElapsedNanos,
                                nowElapsedNanos = SystemClock.elapsedRealtimeNanos(),
                            )
                        }
                    )
                }
            }
        } catch (_: SecurityException) {
            return NativeLocationResult.PermissionDenied
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            return NativeLocationResult.Failure(
                provider = provider,
                code = "PROVIDER_FAILURE",
                message = failure.message ?: "Unknown provider failure.",
            )
        }
        return timed ?: NativeLocationResult.Timeout(provider)
    }
}

internal suspend fun awaitLocationSignalResult(
    timeoutMs: Long,
    register: (CancellationSignal, (NativeLocationResult) -> Unit) -> Unit,
): NativeLocationResult? = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        register(signal) { result ->
            if (continuation.isActive) continuation.resume(result)
        }
    }
}

internal fun cacheProvidersForSdk(sdkInt: Int): List<String> = buildList {
    add(LocationProviders.GPS)
    add(LocationProviders.NETWORK)
    add(LocationProviders.PASSIVE)
    if (sdkInt >= Build.VERSION_CODES.S) add(LocationProviders.FUSED)
}

private class GooglePlayServicesLocationAdapter(
    context: Context,
) : GmsLocationAdapter {
    private val appContext = context.applicationContext
    private val client by lazy { LocationServices.getFusedLocationProviderClient(appContext) }

    override fun isAvailable(): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) ==
            ConnectionResult.SUCCESS
    }.getOrDefault(false)

    override suspend fun requestCurrentLocation(
        accuracy: RequestedAccuracy,
        timeoutMs: Long,
        requestStartedElapsedNanos: Long,
    ): NativeLocationResult {
        if (!isAvailable()) return NativeLocationResult.ProviderUnavailable(LocationProviders.FUSED)
        val priority = when (accuracy) {
            RequestedAccuracy.HIGH -> Priority.PRIORITY_HIGH_ACCURACY
            RequestedAccuracy.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            RequestedAccuracy.LOW -> Priority.PRIORITY_LOW_POWER
        }
        val cancellation = CancellationTokenSource()
        return try {
            val location = withTimeout(timeoutMs) {
                client.getCurrentLocation(priority, cancellation.token).await()
            } ?: return NativeLocationResult.Failure(
                provider = LocationProviders.FUSED,
                code = "GMS_LOCATION_FAILED",
                message = "Google fused location returned no location.",
            )
            location.toNativeSuccess(
                provider = LocationProviders.FUSED,
                requestStartedElapsedNanos = requestStartedElapsedNanos,
                nowElapsedNanos = SystemClock.elapsedRealtimeNanos(),
            )
        } catch (_: TimeoutCancellationException) {
            NativeLocationResult.Timeout(LocationProviders.FUSED)
        } catch (_: SecurityException) {
            NativeLocationResult.PermissionDenied
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            NativeLocationResult.Failure(
                provider = LocationProviders.FUSED,
                code = "GMS_LOCATION_FAILED",
                message = failure.message ?: "Google fused location failed.",
            )
        } finally {
            cancellation.cancel()
        }
    }
}

private fun Location.toNativeSuccess(
    provider: String,
    requestStartedElapsedNanos: Long,
    nowElapsedNanos: Long,
): NativeLocationResult.Success = NativeLocationResult.Success(
    fix = toLocationFix(provider),
    provider = provider,
    generatedAfterRequest = elapsedRealtimeNanos >= requestStartedElapsedNanos,
    ageMs = ((nowElapsedNanos - elapsedRealtimeNanos).coerceAtLeast(0L) / 1_000_000L),
)

private fun Location.toLocationFix(provider: String): LocationFix = LocationFix(
    latitude = latitude,
    longitude = longitude,
    accuracyM = accuracy,
    altitudeM = altitude.takeIf { hasAltitude() },
    speedMps = speed.takeIf { hasSpeed() },
    bearingDegrees = bearing.takeIf { hasBearing() },
    provider = provider,
    timestampMs = time,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
)

internal fun locationPermissionPrecision(
    fineGranted: Boolean,
    coarseGranted: Boolean,
): PermissionPrecision = when {
    fineGranted -> PermissionPrecision.FINE
    coarseGranted -> PermissionPrecision.COARSE
    else -> PermissionPrecision.NONE
}
