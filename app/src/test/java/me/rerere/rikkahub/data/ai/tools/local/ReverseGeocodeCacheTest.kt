package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseGeocodeCacheTest {
    @Test
    fun `cache key isolates provider revision language detail and coordinate system`() = runBlocking {
        var now = 1_000L
        val cache = ReverseGeocodeCache(nowMs = { now })
        val base = request()
        cache.putSuccess(base, "android", 1L, CoordinateSystem.WGS84, address())

        assertTrue(cache.get(base, "android", 1L, CoordinateSystem.WGS84) is ReverseGeocodeCacheLookup.Success)
        assertTrue(cache.get(base, "android", 2L, CoordinateSystem.WGS84) is ReverseGeocodeCacheLookup.Miss)
        assertTrue(cache.get(base.copy(languageTag = "en-US"), "android", 1L, CoordinateSystem.WGS84) is ReverseGeocodeCacheLookup.Miss)
        assertTrue(cache.get(base.copy(detailLevel = AddressDetailLevel.POI), "android", 1L, CoordinateSystem.WGS84) is ReverseGeocodeCacheLookup.Miss)
        assertTrue(cache.get(base, "android", 1L, CoordinateSystem.GCJ02) is ReverseGeocodeCacheLookup.Miss)
    }

    @Test
    fun `cache applies bounded LRU and detail TTL`() = runBlocking {
        var now = 0L
        val cache = ReverseGeocodeCache(maxEntries = 2, nowMs = { now })
        val first = request(latitude = 30.00001)
        val second = request(latitude = 31.00001)
        val third = request(latitude = 32.00001)
        cache.putSuccess(first, "android", 1L, CoordinateSystem.WGS84, address("first"))
        cache.putSuccess(second, "android", 1L, CoordinateSystem.WGS84, address("second"))
        cache.putSuccess(third, "android", 1L, CoordinateSystem.WGS84, address("third"))

        assertTrue(cache.get(first, "android", 1L, CoordinateSystem.WGS84) is ReverseGeocodeCacheLookup.Miss)
        assertEquals(2, cache.stats().entryCount)

        now = 24L * 60L * 60L * 1_000L
        assertTrue(cache.get(second, "android", 1L, CoordinateSystem.WGS84) is ReverseGeocodeCacheLookup.Miss)
    }

    @Test
    fun `nearby coordinates share a detail bucket without claiming constant meter precision`() = runBlocking {
        val cache = ReverseGeocodeCache(nowMs = { 1_000L })
        val stored = request(latitude = 30.123451, longitude = 120.123451)
        val nearby = request(latitude = 30.123452, longitude = 120.123452)
        cache.putSuccess(stored, "android", 1L, CoordinateSystem.WGS84, address())

        assertTrue(cache.get(nearby, "android", 1L, CoordinateSystem.WGS84) is ReverseGeocodeCacheLookup.Success)
    }

    @Test
    fun `only no-result failures are cacheable through the explicit API`() = runBlocking {
        var now = 5_000L
        val cache = ReverseGeocodeCache(nowMs = { now })
        val request = request()
        cache.putNoResult(
            request,
            "android",
            1L,
            CoordinateSystem.WGS84,
            ReverseGeocodeError("NO_GEOCODER_RESULT", "No result."),
        )
        assertTrue(cache.get(request, "android", 1L, CoordinateSystem.WGS84) is ReverseGeocodeCacheLookup.Failure)

        now += 10L * 60L * 1_000L
        assertTrue(cache.get(request, "android", 1L, CoordinateSystem.WGS84) is ReverseGeocodeCacheLookup.Miss)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `authentication and transport failures cannot become coordinate cache entries`() = runBlocking<Unit> {
        ReverseGeocodeCache().putNoResult(
            request(),
            "android",
            1L,
            CoordinateSystem.WGS84,
            ReverseGeocodeError("PROVIDER_AUTH_FAILED", "Authentication failed."),
        )
    }

    @Test
    fun `provider cooldown is global to provider and expires`() = runBlocking {
        var now = 10L
        val cache = ReverseGeocodeCache(nowMs = { now })
        cache.setProviderCooldown("nominatim", 1_000L)
        assertTrue(cache.isProviderCoolingDown("nominatim"))
        assertFalse(cache.isProviderCoolingDown("amap"))
        now = 1_010L
        assertFalse(cache.isProviderCoolingDown("nominatim"))
    }

    private fun request(
        latitude: Double = 30.123451,
        longitude: Double = 120.123451,
    ) = ReverseGeocodeRequest(latitude, longitude)

    private fun address(value: String = "Hangzhou") = StructuredAddress(
        formattedAddress = value,
        provider = "android_geocoder",
        queryCoordinateSystem = CoordinateSystem.WGS84,
        achievedDetail = AddressDetailLevel.STREET,
        coordinateDisclosure = CoordinateDisclosure.PLATFORM_GEOCODER_UNKNOWN,
        explicitExternalProvider = false,
    )
}
