package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidLocationSourceTest {

    @Test
    fun `high requests native gps when gms is unavailable`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 50_000L,
            nowNanos = 50_000_000_000L,
            currentResults = mutableMapOf(
                LocationProviders.GPS to NativeLocationResult.Success(
                    fix = fix(
                        provider = LocationProviders.GPS,
                        elapsedRealtimeNanos = 50_100_000_000L,
                    ),
                    provider = LocationProviders.GPS,
                    generatedAfterRequest = true,
                    ageMs = 0L,
                ),
            ),
        )
        val source = AndroidLocationSource(platform = platform, gms = null)

        val result = source.resolve(
            LocationRequest(
                accuracy = RequestedAccuracy.HIGH,
                timeoutMs = 45_000L,
                allowCached = false,
            )
        )

        assertEquals(listOf(LocationProviders.GPS), platform.requestedProviders)
        assertTrue(result is LocationResolution.Success)
        result as LocationResolution.Success
        assertEquals(LocationProviders.GPS, result.fix.provider)
        assertTrue(result.generatedAfterRequest)
        assertFalse(result.cached)
    }

    @Test
    fun `gms is used only after native providers fail`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 50_000L,
            nowNanos = 50_000_000_000L,
            currentResults = mutableMapOf(
                LocationProviders.GPS to NativeLocationResult.Timeout(LocationProviders.GPS),
                LocationProviders.NETWORK to NativeLocationResult.Timeout(LocationProviders.NETWORK),
            ),
        )
        val gms = RecordingGmsLocationAdapter(
            result = NativeLocationResult.Success(
                fix = fix(
                    provider = LocationProviders.FUSED,
                    elapsedRealtimeNanos = 50_300_000_000L,
                ),
                provider = LocationProviders.FUSED,
                generatedAfterRequest = true,
                ageMs = 0L,
            )
        )
        val source = AndroidLocationSource(platform = platform, gms = gms)

        val result = source.resolve(
            LocationRequest(
                accuracy = RequestedAccuracy.HIGH,
                timeoutMs = 45_000L,
                allowCached = false,
            )
        )

        assertEquals(
            listOf(LocationProviders.GPS, LocationProviders.NETWORK),
            platform.requestedProviders,
        )
        assertEquals(1, gms.requestCount)
        assertTrue(result is LocationResolution.Success)
        result as LocationResolution.Success
        assertEquals(LocationSourceKind.GMS_FUSED, result.source)
    }

    @Test
    fun `native success never calls available gms fallback`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 50_000L,
            nowNanos = 50_000_000_000L,
            currentResults = mutableMapOf(
                LocationProviders.GPS to NativeLocationResult.Success(
                    fix = fix(LocationProviders.GPS, 50_100_000_000L),
                    provider = LocationProviders.GPS,
                    generatedAfterRequest = true,
                    ageMs = 0L,
                ),
            ),
        )
        val gms = RecordingGmsLocationAdapter(NativeLocationResult.Timeout(LocationProviders.FUSED))

        val result = AndroidLocationSource(platform, gms).resolve(
            LocationRequest(accuracy = RequestedAccuracy.HIGH, allowCached = false)
        )

        assertTrue(result is LocationResolution.Success)
        assertEquals(0, gms.requestCount)
    }

    @Test
    fun `gms receives only the native deadline remainder`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 10_000L,
            nowNanos = 10_000_000_000L,
            currentResults = mutableMapOf(
                LocationProviders.GPS to NativeLocationResult.Timeout(LocationProviders.GPS),
            ),
            providers = setOf(LocationProviders.GPS),
            advanceByRequestedBudget = true,
        )
        val gms = RecordingGmsLocationAdapter(NativeLocationResult.Timeout(LocationProviders.FUSED))

        AndroidLocationSource(platform, gms).resolve(
            LocationRequest(
                accuracy = RequestedAccuracy.HIGH,
                timeoutMs = 45_000L,
                allowCached = false,
            )
        )

        assertEquals(listOf(20_000L), gms.requestedTimeouts)
    }

    @Test
    fun `balanced direct cache selects newest provider instead of preferring gps`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 100_000L,
            nowNanos = 100_000_000_000L,
            currentResults = mutableMapOf(),
            cachedFixes = listOf(
                fix(
                    provider = LocationProviders.GPS,
                    elapsedRealtimeNanos = 70_000_000_000L,
                ),
                fix(
                    provider = LocationProviders.NETWORK,
                    elapsedRealtimeNanos = 95_000_000_000L,
                ),
            ),
        )
        val source = AndroidLocationSource(platform = platform, gms = null)

        val result = source.resolve(LocationRequest(accuracy = RequestedAccuracy.BALANCED))

        assertTrue(result is LocationResolution.Success)
        result as LocationResolution.Success
        assertEquals(LocationProviders.NETWORK, result.fix.provider)
        assertEquals(5_000L, result.ageMs)
        assertTrue(result.cached)
        assertEquals("direct_fresh", result.cacheStatus)
        assertTrue(platform.requestedProviders.isEmpty())
    }

    @Test
    fun `high returns fallback cache only after current providers fail`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 200_000L,
            nowNanos = 200_000_000_000L,
            currentResults = mutableMapOf(
                LocationProviders.GPS to NativeLocationResult.Timeout(LocationProviders.GPS),
                LocationProviders.NETWORK to NativeLocationResult.Timeout(LocationProviders.NETWORK),
            ),
            cachedFixes = listOf(
                fix(
                    provider = LocationProviders.NETWORK,
                    elapsedRealtimeNanos = 15_000_000_000L,
                ),
            ),
        )
        val source = AndroidLocationSource(platform = platform, gms = null)

        val result = source.resolve(LocationRequest(accuracy = RequestedAccuracy.HIGH))

        assertEquals(
            listOf(LocationProviders.GPS, LocationProviders.NETWORK),
            platform.requestedProviders,
        )
        assertTrue(result is LocationResolution.Success)
        result as LocationResolution.Success
        assertEquals(185_000L, result.ageMs)
        assertEquals("fallback_stale", result.cacheStatus)
        assertEquals(LocationSourceKind.LAST_KNOWN_LOCATION, result.source)
        assertTrue(result.warning?.contains("cached", ignoreCase = true) == true)
    }

    @Test
    fun `high shares one deadline across gps fused and network budgets`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 10_000L,
            nowNanos = 10_000_000_000L,
            currentResults = mutableMapOf(),
            providers = setOf(LocationProviders.GPS, LocationProviders.FUSED, LocationProviders.NETWORK),
            advanceByRequestedBudget = true,
        )

        AndroidLocationSource(platform, gms = null).resolve(
            LocationRequest(
                accuracy = RequestedAccuracy.HIGH,
                timeoutMs = 45_000L,
                allowCached = false,
            )
        )

        assertEquals(
            listOf(LocationProviders.GPS, LocationProviders.FUSED, LocationProviders.NETWORK),
            platform.requestedProviders,
        )
        assertEquals(listOf(25_000L, 8_000L, 12_000L), platform.requestedBudgets)
    }

    @Test
    fun `coarse only high request succeeds with approximate warning`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 50_000L,
            nowNanos = 50_000_000_000L,
            currentResults = mutableMapOf(
                LocationProviders.GPS to NativeLocationResult.Success(
                    fix = fix(LocationProviders.GPS, 50_100_000_000L),
                    provider = LocationProviders.GPS,
                    generatedAfterRequest = true,
                    ageMs = 0L,
                ),
            ),
            permission = PermissionPrecision.COARSE,
        )

        val result = AndroidLocationSource(platform, gms = null).resolve(
            LocationRequest(accuracy = RequestedAccuracy.HIGH, allowCached = false)
        ) as LocationResolution.Success

        assertEquals(PermissionPrecision.COARSE, result.permissionPrecision)
        assertEquals(LocationSourceType.APPROXIMATE, result.sourceType)
        assertEquals("PRECISE_LOCATION_NOT_GRANTED", result.warningCode)
    }

    @Test
    fun `coarse direct cache preserves the precision limitation warning`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 100_000L,
            nowNanos = 100_000_000_000L,
            currentResults = mutableMapOf(),
            cachedFixes = listOf(fix(LocationProviders.NETWORK, 95_000_000_000L)),
            permission = PermissionPrecision.COARSE,
        )

        val result = AndroidLocationSource(platform, gms = null).resolve(
            LocationRequest(accuracy = RequestedAccuracy.BALANCED)
        ) as LocationResolution.Success

        assertEquals("direct_fresh", result.cacheStatus)
        assertEquals("PRECISE_LOCATION_NOT_GRANTED", result.warningCode)
        assertTrue(result.warning?.contains("Precise location") == true)
    }

    @Test
    fun `allow cached false never returns old provider or last known fixes`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 100_000L,
            nowNanos = 100_000_000_000L,
            currentResults = mutableMapOf(
                LocationProviders.GPS to NativeLocationResult.Success(
                    fix = fix(LocationProviders.GPS, 90_000_000_000L),
                    provider = LocationProviders.GPS,
                    generatedAfterRequest = false,
                    ageMs = 10_000L,
                ),
                LocationProviders.NETWORK to NativeLocationResult.Timeout(LocationProviders.NETWORK),
            ),
            cachedFixes = listOf(fix(LocationProviders.NETWORK, 99_000_000_000L)),
        )

        val result = AndroidLocationSource(platform, gms = null).resolve(
            LocationRequest(accuracy = RequestedAccuracy.HIGH, allowCached = false)
        )

        assertTrue(result is LocationResolution.Failure)
        assertFalse(result is LocationResolution.Success)
    }

    @Test
    fun `fallback cache remains available when no active provider is enabled`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 200_000L,
            nowNanos = 200_000_000_000L,
            currentResults = mutableMapOf(),
            cachedFixes = listOf(fix(LocationProviders.PASSIVE, 180_000_000_000L)),
            providers = setOf(LocationProviders.PASSIVE),
        )

        val result = AndroidLocationSource(platform, gms = null).resolve(
            LocationRequest(accuracy = RequestedAccuracy.HIGH)
        )

        assertTrue(result is LocationResolution.Success)
        result as LocationResolution.Success
        assertEquals(LocationProviders.PASSIVE, result.fix.provider)
        assertEquals(LocationSourceType.PASSIVE, result.sourceType)
        assertEquals("fallback_stale", result.cacheStatus)
    }

    @Test
    fun `cache older than fallback threshold is rejected explicitly`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 1_000_000L,
            nowNanos = 1_000_000_000_000L,
            currentResults = mutableMapOf(),
            cachedFixes = listOf(fix(LocationProviders.PASSIVE, 1_000_000_000L)),
            providers = setOf(LocationProviders.PASSIVE),
        )

        val result = AndroidLocationSource(platform, gms = null).resolve(
            LocationRequest(accuracy = RequestedAccuracy.HIGH)
        ) as LocationResolution.Failure

        assertEquals("CACHED_LOCATION_TOO_OLD", result.code)
    }

    @Test
    fun `cancelling resolution cancels the active platform request`() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val requestCancelled = CompletableDeferred<Unit>()
        val platform = RecordingLocationPlatform(
            nowMs = 100L,
            nowNanos = 100_000_000L,
            currentResults = mutableMapOf(),
            requestOverride = { _, _, _ ->
                requestStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    requestCancelled.complete(Unit)
                }
            },
        )
        val job = launch {
            AndroidLocationSource(platform, gms = null).resolve(
                LocationRequest(accuracy = RequestedAccuracy.HIGH, allowCached = false)
            )
        }

        requestStarted.await()
        job.cancelAndJoin()

        assertTrue(requestCancelled.isCompleted)
    }

    @Test
    fun `missing permission and disabled services have distinct failures`() = runBlocking {
        val missingPermission = AndroidLocationSource(
            RecordingLocationPlatform(
                nowMs = 0L,
                nowNanos = 0L,
                currentResults = mutableMapOf(),
                permission = PermissionPrecision.NONE,
            ),
            gms = null,
        ).resolve(LocationRequest()) as LocationResolution.Failure
        val disabledServices = AndroidLocationSource(
            RecordingLocationPlatform(
                nowMs = 0L,
                nowNanos = 0L,
                currentResults = mutableMapOf(),
                locationEnabled = false,
            ),
            gms = null,
        ).resolve(LocationRequest()) as LocationResolution.Failure

        assertEquals("LOCATION_PERMISSION_MISSING", missingPermission.code)
        assertEquals("LOCATION_SERVICES_DISABLED", disabledServices.code)
    }

    @Test
    fun `provider and cache failures have distinct error codes`() = runBlocking {
        val gpsDisabled = AndroidLocationSource(
            RecordingLocationPlatform(
                nowMs = 0L,
                nowNanos = 0L,
                currentResults = mutableMapOf(),
                providers = setOf(LocationProviders.GPS),
                enabledProviders = emptySet(),
            ),
            gms = null,
        ).resolve(LocationRequest(accuracy = RequestedAccuracy.HIGH)) as LocationResolution.Failure
        val noProvider = AndroidLocationSource(
            RecordingLocationPlatform(
                nowMs = 0L,
                nowNanos = 0L,
                currentResults = mutableMapOf(),
                providers = emptySet(),
            ),
            gms = null,
        ).resolve(LocationRequest(accuracy = RequestedAccuracy.HIGH)) as LocationResolution.Failure
        val noCache = AndroidLocationSource(
            RecordingLocationPlatform(
                nowMs = 0L,
                nowNanos = 0L,
                currentResults = mutableMapOf(
                    LocationProviders.GPS to NativeLocationResult.Timeout(LocationProviders.GPS),
                ),
                providers = setOf(LocationProviders.GPS),
            ),
            gms = null,
        ).resolve(LocationRequest(accuracy = RequestedAccuracy.HIGH)) as LocationResolution.Failure

        assertEquals("GPS_PROVIDER_DISABLED", gpsDisabled.code)
        assertEquals("NO_PROVIDER_AVAILABLE", noProvider.code)
        assertEquals("NO_CACHED_LOCATION", noCache.code)
    }

    @Test
    fun `provider null and exception failures remain observable when cache is disabled`() = runBlocking {
        suspend fun resolveFailure(code: String): LocationResolution.Failure = AndroidLocationSource(
            RecordingLocationPlatform(
                nowMs = 0L,
                nowNanos = 0L,
                currentResults = mutableMapOf(
                    LocationProviders.GPS to NativeLocationResult.Failure(
                        provider = LocationProviders.GPS,
                        code = code,
                        message = "provider failure",
                    ),
                ),
                providers = setOf(LocationProviders.GPS),
            ),
            gms = null,
        ).resolve(
            LocationRequest(accuracy = RequestedAccuracy.HIGH, allowCached = false)
        ) as LocationResolution.Failure

        assertEquals("PROVIDER_RETURNED_NULL", resolveFailure("PROVIDER_RETURNED_NULL").code)
        assertEquals("PROVIDER_FAILURE", resolveFailure("PROVIDER_FAILURE").code)
    }

    @Test
    fun `balanced prefers fused then network with fixed provider budgets`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 10_000L,
            nowNanos = 10_000_000_000L,
            currentResults = mutableMapOf(
                LocationProviders.FUSED to NativeLocationResult.Timeout(LocationProviders.FUSED),
                LocationProviders.NETWORK to NativeLocationResult.Success(
                    fix = fix(LocationProviders.NETWORK, 10_200_000_000L),
                    provider = LocationProviders.NETWORK,
                    generatedAfterRequest = true,
                    ageMs = 0L,
                ),
            ),
            providers = setOf(LocationProviders.GPS, LocationProviders.FUSED, LocationProviders.NETWORK),
        )

        val result = AndroidLocationSource(platform, gms = null).resolve(
            LocationRequest(accuracy = RequestedAccuracy.BALANCED, allowCached = false)
        ) as LocationResolution.Success

        assertEquals(listOf(LocationProviders.FUSED, LocationProviders.NETWORK), platform.requestedProviders)
        assertEquals(listOf(10_000L, 8_000L), platform.requestedBudgets)
        assertEquals(LocationSourceType.NETWORK, result.sourceType)
    }

    @Test
    fun `fused provider is ignored below Android twelve`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 10_000L,
            nowNanos = 10_000_000_000L,
            currentResults = mutableMapOf(),
            providers = setOf(LocationProviders.GPS, LocationProviders.FUSED, LocationProviders.NETWORK),
            sdk = 30,
        )

        AndroidLocationSource(platform, gms = null).resolve(
            LocationRequest(accuracy = RequestedAccuracy.BALANCED, allowCached = false)
        )

        assertEquals(
            listOf(LocationProviders.NETWORK, LocationProviders.GPS),
            platform.requestedProviders,
        )
    }

    @Test
    fun `high ignores pre request gps and continues to fresh network`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 100_000L,
            nowNanos = 100_000_000_000L,
            currentResults = mutableMapOf(
                LocationProviders.GPS to NativeLocationResult.Success(
                    fix = fix(LocationProviders.GPS, 90_000_000_000L),
                    provider = LocationProviders.GPS,
                    generatedAfterRequest = false,
                    ageMs = 10_000L,
                ),
                LocationProviders.NETWORK to NativeLocationResult.Success(
                    fix = fix(LocationProviders.NETWORK, 100_200_000_000L),
                    provider = LocationProviders.NETWORK,
                    generatedAfterRequest = true,
                    ageMs = 0L,
                ),
            ),
        )

        val result = AndroidLocationSource(platform, gms = null).resolve(
            LocationRequest(accuracy = RequestedAccuracy.HIGH)
        ) as LocationResolution.Success

        assertEquals(listOf(LocationProviders.GPS, LocationProviders.NETWORK), platform.requestedProviders)
        assertEquals(LocationProviders.NETWORK, result.fix.provider)
        assertTrue(result.generatedAfterRequest)
        assertFalse(result.cached)
    }

    @Test
    fun `balanced also continues after provider returns a recent pre request fix`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 100_000L,
            nowNanos = 100_000_000_000L,
            currentResults = mutableMapOf(
                LocationProviders.FUSED to NativeLocationResult.Success(
                    fix = fix(LocationProviders.FUSED, 95_000_000_000L),
                    provider = LocationProviders.FUSED,
                    generatedAfterRequest = false,
                    ageMs = 5_000L,
                ),
                LocationProviders.NETWORK to NativeLocationResult.Success(
                    fix = fix(LocationProviders.NETWORK, 100_200_000_000L),
                    provider = LocationProviders.NETWORK,
                    generatedAfterRequest = true,
                    ageMs = 0L,
                ),
            ),
            providers = setOf(LocationProviders.FUSED, LocationProviders.NETWORK),
        )

        val result = AndroidLocationSource(platform, gms = null).resolve(
            LocationRequest(accuracy = RequestedAccuracy.BALANCED)
        ) as LocationResolution.Success

        assertEquals(listOf(LocationProviders.FUSED, LocationProviders.NETWORK), platform.requestedProviders)
        assertEquals(LocationProviders.NETWORK, result.fix.provider)
        assertTrue(result.generatedAfterRequest)
    }

    @Test
    fun `cache age is monotonic and never negative`() = runBlocking {
        val platform = RecordingLocationPlatform(
            nowMs = 100L,
            nowNanos = 100_000_000L,
            currentResults = mutableMapOf(),
            cachedFixes = listOf(fix(LocationProviders.NETWORK, 200_000_000L)),
        )

        val result = AndroidLocationSource(platform, gms = null).resolve(
            LocationRequest(accuracy = RequestedAccuracy.BALANCED)
        ) as LocationResolution.Success

        assertEquals(0L, result.ageMs)
        assertEquals("direct_fresh", result.cacheStatus)
    }

    @Test
    fun `cache collection includes every supported native provider`() {
        assertEquals(
            listOf(LocationProviders.GPS, LocationProviders.NETWORK, LocationProviders.PASSIVE),
            cacheProvidersForSdk(30),
        )
        assertEquals(
            listOf(
                LocationProviders.GPS,
                LocationProviders.NETWORK,
                LocationProviders.PASSIVE,
                LocationProviders.FUSED,
            ),
            cacheProvidersForSdk(31),
        )
    }

    @Test
    fun `cancelling android wait cancels the underlying cancellation signal`() = runBlocking {
        val capturedSignal = CompletableDeferred<androidx.core.os.CancellationSignal>()
        val job = launch {
            awaitLocationSignalResult(timeoutMs = 60_000L) { signal, _ ->
                capturedSignal.complete(signal)
            }
        }

        val signal = capturedSignal.await()
        job.cancelAndJoin()

        assertTrue(signal.isCanceled)
    }

    private fun fix(
        provider: String,
        elapsedRealtimeNanos: Long,
    ) = LocationFix(
        latitude = 30.0,
        longitude = 120.0,
        accuracyM = 8.5f,
        provider = provider,
        timestampMs = 1_700_000_000_000L,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
    )

    private class RecordingLocationPlatform(
        private var nowMs: Long,
        private var nowNanos: Long,
        private val currentResults: MutableMap<String, NativeLocationResult>,
        private val cachedFixes: List<LocationFix> = emptyList(),
        private val permission: PermissionPrecision = PermissionPrecision.FINE,
        private val locationEnabled: Boolean = true,
        private val providers: Set<String> = setOf(LocationProviders.GPS, LocationProviders.NETWORK),
        private val enabledProviders: Set<String> = providers,
        private val sdk: Int = 37,
        private val advanceByRequestedBudget: Boolean = false,
        private val requestOverride: (suspend (String, Long, Long) -> NativeLocationResult)? = null,
    ) : LocationPlatformAdapter {
        val requestedProviders = mutableListOf<String>()
        val requestedBudgets = mutableListOf<Long>()

        override fun permissionPrecision(): PermissionPrecision = permission

        override fun isLocationEnabled(): Boolean = locationEnabled

        override fun sdkInt(): Int = sdk

        override fun hasProvider(provider: String): Boolean = provider in providers

        override fun isProviderEnabled(provider: String): Boolean = provider in enabledProviders

        override fun elapsedRealtimeMs(): Long = nowMs

        override fun elapsedRealtimeNanos(): Long = nowNanos

        override fun collectCachedFixes(): List<LocationFix> = cachedFixes

        override suspend fun requestCurrentLocation(
            provider: String,
            timeoutMs: Long,
            requestStartedElapsedNanos: Long,
        ): NativeLocationResult {
            requestedProviders += provider
            requestedBudgets += timeoutMs
            val elapsedMs = if (advanceByRequestedBudget) timeoutMs else 100L
            nowMs += elapsedMs
            nowNanos += elapsedMs * 1_000_000L
            requestOverride?.let { return it(provider, timeoutMs, requestStartedElapsedNanos) }
            return currentResults.remove(provider) ?: NativeLocationResult.Timeout(provider)
        }
    }

    private class RecordingGmsLocationAdapter(
        private val result: NativeLocationResult,
    ) : GmsLocationAdapter {
        var requestCount = 0
        val requestedTimeouts = mutableListOf<Long>()

        override fun isAvailable(): Boolean = true

        override suspend fun requestCurrentLocation(
            accuracy: RequestedAccuracy,
            timeoutMs: Long,
            requestStartedElapsedNanos: Long,
        ): NativeLocationResult {
            requestCount++
            requestedTimeouts += timeoutMs
            return result
        }
    }
}
