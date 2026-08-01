package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseGeocodeCoordinatorTest {
    @Test
    fun `successful Android result is cached and later avoids backend disclosure`() = runBlocking {
        var now = 1_000L
        val backend = FakeBackend(result = success())
        val diagnostics = ReverseGeocodeDiagnosticsStore()
        val coordinator = ReverseGeocodeCoordinatorImpl(
            androidBackend = backend,
            cache = ReverseGeocodeCache(nowMs = { now }),
            diagnostics = diagnostics,
            monotonicNanos = { now * 1_000_000L },
        )

        val first = coordinator.reverse(request()) as ReverseGeocodeResolution.Success
        now += 500L
        val second = coordinator.reverse(request()) as ReverseGeocodeResolution.Success

        assertEquals(1, backend.calls)
        assertFalse(first.cached)
        assertTrue(second.cached)
        assertEquals(500L, second.cacheAgeMs)
        assertEquals(CoordinateDisclosure.MEMORY_CACHE_ONLY, second.address.coordinateDisclosure)
        assertTrue(second.attemptedProviders.isEmpty())
        assertEquals(1L, diagnostics.snapshot().cacheHitCount)
    }

    @Test
    fun `no-result is cached but platform errors are retried`() = runBlocking {
        val noResultBackend = FakeBackend(failure("NO_GEOCODER_RESULT"))
        val noResultCoordinator = coordinator(noResultBackend)
        noResultCoordinator.reverse(request())
        noResultCoordinator.reverse(request())
        assertEquals(1, noResultBackend.calls)

        val failedBackend = FakeBackend(failure("ANDROID_GEOCODER_FAILED"))
        val failedCoordinator = coordinator(failedBackend)
        failedCoordinator.reverse(request())
        failedCoordinator.reverse(request())
        assertEquals(2, failedBackend.calls)
    }

    @Test
    fun `external provider is not guessed or called in P0`() = runBlocking {
        val backend = FakeBackend(success())
        val result = coordinator(backend).reverse(
            request().copy(providerId = "amap-main", allowExternal = true),
        )

        assertFailureCode(result, "PROVIDER_NOT_CONFIGURED")
        assertEquals(0, backend.calls)
    }

    @Test
    fun `auto without any permitted backend returns stable failure`() = runBlocking {
        val backend = FakeBackend(success())
        val result = coordinator(backend).reverse(request().copy(allowPlatformGeocoder = false))

        assertFailureCode(result, "NO_GEOCODER_RESULT")
        assertEquals(0, backend.calls)
    }

    @Test(expected = CancellationException::class)
    fun `backend cancellation stops fallback and propagates`() = runBlocking<Unit> {
        val backend = object : ReverseGeocoderBackend {
            override val id = "android"
            override val disclosure = CoordinateDisclosure.PLATFORM_GEOCODER_UNKNOWN
            override val queryCoordinateSystem = CoordinateSystem.WGS84
            override suspend fun availability(request: ReverseGeocodeRequest) = BackendAvailability.Available
            override suspend fun reverse(request: ReverseGeocodeRequest): ReverseGeocodeResolution {
                throw CancellationException("stop")
            }
        }
        coordinator(backend).reverse(request())
    }

    @Test
    fun `diagnostics expose only stable categories`() = runBlocking {
        var tick = 0L
        val diagnostics = ReverseGeocodeDiagnosticsStore()
        val coordinator = ReverseGeocodeCoordinatorImpl(
            androidBackend = FakeBackend(failure("ANDROID_GEOCODER_FAILED")),
            cache = ReverseGeocodeCache(),
            diagnostics = diagnostics,
            monotonicNanos = {
                tick += 250_000_000L
                tick
            },
        )
        coordinator.reverse(request())

        val snapshot = diagnostics.snapshot()
        assertEquals("FAILED", snapshot.lastStatus)
        assertEquals("ANDROID_GEOCODER_FAILED", snapshot.lastErrorCode)
        assertEquals("android", snapshot.lastProviderId)
        assertEquals("LT_500_MS", snapshot.lastDurationBucket)
        assertFalse(snapshot.toString().contains("30.0"))
        assertFalse(snapshot.toString().contains("120.0"))
    }

    private fun coordinator(backend: ReverseGeocoderBackend) = ReverseGeocodeCoordinatorImpl(
        androidBackend = backend,
        cache = ReverseGeocodeCache(),
        diagnostics = ReverseGeocodeDiagnosticsStore(),
    )

    private fun request() = ReverseGeocodeRequest(30.0, 120.0)

    private fun success() = ReverseGeocodeResolution.Success(
        address = StructuredAddress(
            formattedAddress = "Hangzhou",
            provider = "android_geocoder",
            queryCoordinateSystem = CoordinateSystem.WGS84,
            achievedDetail = AddressDetailLevel.CITY,
            coordinateDisclosure = CoordinateDisclosure.PLATFORM_GEOCODER_UNKNOWN,
            explicitExternalProvider = false,
        ),
        attemptedProviders = listOf("android"),
    )

    private fun failure(code: String) = ReverseGeocodeResolution.Failure(
        ReverseGeocodeError(code, "Stable failure."),
        attemptedProviders = listOf("android"),
    )

    private fun assertFailureCode(result: ReverseGeocodeResolution, expected: String) {
        assertTrue(result is ReverseGeocodeResolution.Failure)
        assertEquals(expected, (result as ReverseGeocodeResolution.Failure).error.code)
    }

    private class FakeBackend(
        private val result: ReverseGeocodeResolution,
    ) : ReverseGeocoderBackend {
        override val id = "android"
        override val disclosure = CoordinateDisclosure.PLATFORM_GEOCODER_UNKNOWN
        override val queryCoordinateSystem = CoordinateSystem.WGS84
        var calls = 0

        override suspend fun availability(request: ReverseGeocodeRequest) = BackendAvailability.Available

        override suspend fun reverse(request: ReverseGeocodeRequest): ReverseGeocodeResolution {
            calls += 1
            return result
        }
    }
}
