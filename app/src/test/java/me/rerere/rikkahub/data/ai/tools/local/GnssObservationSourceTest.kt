package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.core.location.GnssStatusCompat

class GnssObservationSourceTest {
    @Test
    fun `latest snapshot replaces the previous callback instead of accumulating satellites`() {
        val latest = LatestGnssSnapshot()
        latest.update(
            GnssSatelliteSnapshot(
                listOf(
                    GnssSatellite(GnssConstellation.GPS, usedInFix = true),
                    GnssSatellite(GnssConstellation.BEIDOU, usedInFix = false),
                ),
            ),
        )
        val replacement = GnssSatelliteSnapshot(
            listOf(GnssSatellite(GnssConstellation.GALILEO, usedInFix = true)),
        )

        latest.update(replacement)

        assertEquals(replacement, latest.get())
    }

    @Test
    fun `completed observation cancels GPS activation and unregisters exactly once`() = runBlocking {
        var activationStartCount = 0
        var activationCancelCount = 0
        var unregisterCount = 0

        val result = runGnssObservationWindow(
            observationWindowMs = 0L,
            register = { true },
            startGpsActivation = { activationStartCount++ },
            cancelGpsActivation = { activationCancelCount++ },
            unregister = { unregisterCount++ },
            completed = { GnssPlatformObservation.Completed(true, false, null) },
        )

        assertTrue(result is GnssPlatformObservation.Completed)
        assertEquals(1, activationStartCount)
        assertEquals(1, activationCancelCount)
        assertEquals(1, unregisterCount)
    }

    @Test
    fun `rejected registration does not activate or unregister GNSS`() = runBlocking {
        var activationStartCount = 0
        var activationCancelCount = 0
        var unregisterCount = 0

        val result = runGnssObservationWindow(
            observationWindowMs = 0L,
            register = { false },
            startGpsActivation = { activationStartCount++ },
            cancelGpsActivation = { activationCancelCount++ },
            unregister = { unregisterCount++ },
            completed = { GnssPlatformObservation.Completed(false, false, null) },
        )

        assertTrue(result is GnssPlatformObservation.RegistrationFailed)
        assertEquals(0, activationStartCount)
        assertEquals(1, activationCancelCount)
        assertEquals(0, unregisterCount)
    }

    @Test
    fun `activation exception still cancels signal and unregisters exactly once`() = runBlocking {
        var activationCancelCount = 0
        var unregisterCount = 0
        var failure: Throwable? = null

        try {
            runGnssObservationWindow(
                observationWindowMs = 0L,
                register = { true },
                startGpsActivation = { error("activation failed") },
                cancelGpsActivation = { activationCancelCount++ },
                unregister = { unregisterCount++ },
                completed = { GnssPlatformObservation.Completed(false, false, null) },
            )
        } catch (error: Throwable) {
            failure = error
        }

        assertEquals("activation failed", failure?.message)
        assertEquals(1, activationCancelCount)
        assertEquals(1, unregisterCount)
    }

    @Test
    fun `cancelling observation cleans GPS activation and callback exactly once`() = runBlocking {
        val registered = CompletableDeferred<Unit>()
        var activationCancelCount = 0
        var unregisterCount = 0
        val job = launch {
            runGnssObservationWindow(
                observationWindowMs = 60_000L,
                register = {
                    registered.complete(Unit)
                    true
                },
                startGpsActivation = {},
                cancelGpsActivation = { activationCancelCount++ },
                unregister = { unregisterCount++ },
                completed = {
                    GnssPlatformObservation.Completed(false, false, null)
                },
            )
        }

        registered.await()
        job.cancelAndJoin()

        assertEquals(1, activationCancelCount)
        assertEquals(1, unregisterCount)
    }

    @Test
    fun `coarse permission is rejected before GNSS observation`() = runBlocking {
        val platform = FakeGnssObservationPlatform(
            permission = PermissionPrecision.COARSE,
            observation = GnssPlatformObservation.Completed(
                gnssStarted = true,
                firstFixObserved = false,
                latestSnapshot = GnssSatelliteSnapshot(emptyList()),
            ),
        )

        val result = DefaultGnssObservationSource(platform).observe(GnssObservationRequest())

        assertEquals("PRECISE_LOCATION_NOT_GRANTED", (result as GnssObservationResult.Failure).code)
        assertEquals(0, platform.observeCount)
    }

    @Test
    fun `preflight failures use distinct recovery codes`() = runBlocking {
        val cases = listOf(
            FakeGnssObservationPlatform(
                permission = PermissionPrecision.NONE,
                observation = completedEmpty(),
            ) to "LOCATION_PERMISSION_MISSING",
            FakeGnssObservationPlatform(
                locationEnabled = false,
                observation = completedEmpty(),
            ) to "LOCATION_SERVICES_DISABLED",
            FakeGnssObservationPlatform(
                hasGpsProvider = false,
                observation = completedEmpty(),
            ) to "PROVIDER_UNAVAILABLE",
            FakeGnssObservationPlatform(
                gpsProviderEnabled = false,
                observation = completedEmpty(),
            ) to "GPS_PROVIDER_DISABLED",
        )

        cases.forEach { (platform, expectedCode) ->
            val result = DefaultGnssObservationSource(platform).observe(GnssObservationRequest())
            assertEquals(expectedCode, (result as GnssObservationResult.Failure).code)
            assertEquals(0, platform.observeCount)
        }
    }

    @Test
    fun `registration failure is returned as a distinct tool error`() = runBlocking {
        val result = DefaultGnssObservationSource(
            FakeGnssObservationPlatform(
                observation = GnssPlatformObservation.RegistrationFailed("registration rejected"),
            ),
        ).observe(GnssObservationRequest())

        assertEquals("GNSS_REGISTRATION_FAILED", (result as GnssObservationResult.Failure).code)
    }

    @Test
    fun `zero-satellite snapshot is successful while missing snapshot times out`() = runBlocking {
        val zero = DefaultGnssObservationSource(
            FakeGnssObservationPlatform(observation = completedEmpty()),
        ).observe(GnssObservationRequest()) as GnssObservationResult.Success
        val missing = DefaultGnssObservationSource(
            FakeGnssObservationPlatform(
                observation = GnssPlatformObservation.Completed(true, false, null),
            ),
        ).observe(GnssObservationRequest()) as GnssObservationResult.Failure

        assertEquals(0, zero.satellitesVisible)
        assertEquals("NO_SATELLITES_VISIBLE", zero.warningCode)
        assertEquals("GNSS_STATUS_TIMEOUT", missing.code)
    }

    @Test
    fun `Android constellation constants map to stable wire constellations`() {
        assertEquals(GnssConstellation.BEIDOU, constellationName(GnssStatusCompat.CONSTELLATION_BEIDOU))
        assertEquals(GnssConstellation.GPS, constellationName(GnssStatusCompat.CONSTELLATION_GPS))
        assertEquals(GnssConstellation.GLONASS, constellationName(GnssStatusCompat.CONSTELLATION_GLONASS))
        assertEquals(GnssConstellation.GALILEO, constellationName(GnssStatusCompat.CONSTELLATION_GALILEO))
        assertEquals(GnssConstellation.QZSS, constellationName(GnssStatusCompat.CONSTELLATION_QZSS))
        assertEquals(GnssConstellation.SBAS, constellationName(GnssStatusCompat.CONSTELLATION_SBAS))
        assertEquals(GnssConstellation.IRNSS, constellationName(GnssStatusCompat.CONSTELLATION_IRNSS))
        assertEquals(GnssConstellation.UNKNOWN, constellationName(Int.MAX_VALUE))
    }

    @Test
    fun `observation reports constellation counts from the latest complete snapshot`() = runBlocking {
        val platform = FakeGnssObservationPlatform(
            observation = GnssPlatformObservation.Completed(
                gnssStarted = true,
                firstFixObserved = true,
                latestSnapshot = GnssSatelliteSnapshot(
                    satellites = listOf(
                        GnssSatellite(GnssConstellation.BEIDOU, usedInFix = true),
                        GnssSatellite(GnssConstellation.BEIDOU, usedInFix = false),
                        GnssSatellite(GnssConstellation.GPS, usedInFix = true),
                    ),
                ),
            ),
        )

        val result = DefaultGnssObservationSource(platform).observe(
            GnssObservationRequest(observationWindowMs = 8_000L),
        ) as GnssObservationResult.Success

        assertTrue(result.gnssStarted)
        assertTrue(result.firstFixObserved)
        assertEquals(3, result.satellitesVisible)
        assertEquals(2, result.satellitesUsedInFix)
        assertEquals(ConstellationCounts(visible = 2, usedInFix = 1), result.constellations["beidou"])
        assertEquals(ConstellationCounts(visible = 1, usedInFix = 1), result.constellations["gps"])
    }

    private class FakeGnssObservationPlatform(
        private val permission: PermissionPrecision = PermissionPrecision.FINE,
        private val locationEnabled: Boolean = true,
        private val hasGpsProvider: Boolean = true,
        private val gpsProviderEnabled: Boolean = true,
        private val observation: GnssPlatformObservation,
    ) : GnssObservationPlatform {
        var observeCount = 0

        override fun permissionPrecision(): PermissionPrecision = permission
        override fun isLocationEnabled(): Boolean = locationEnabled
        override fun hasGpsProvider(): Boolean = hasGpsProvider
        override fun isGpsProviderEnabled(): Boolean = gpsProviderEnabled
        override fun currentTimeMillis(): Long = 1_700_000_000_000L
        override suspend fun observe(observationWindowMs: Long): GnssPlatformObservation {
            observeCount++
            return observation
        }
    }

    private companion object {
        fun completedEmpty() = GnssPlatformObservation.Completed(
            gnssStarted = true,
            firstFixObserved = false,
            latestSnapshot = GnssSatelliteSnapshot(emptyList()),
        )
    }
}
