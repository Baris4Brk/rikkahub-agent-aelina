package me.rerere.rikkahub.data.ai.tools.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

internal data class GnssPreflightSnapshot(
    val permissionPrecision: PermissionPrecision,
    val locationEnabled: Boolean,
    val gpsProviderExists: Boolean,
    val gpsProviderEnabled: Boolean,
)

internal class AndroidGnssObservationSource(context: Context) : GnssObservationSource {
    private val platform = AndroidGnssObservationPlatform(context)
    private val delegate = DefaultGnssObservationSource(platform)

    override suspend fun observe(request: GnssObservationRequest): GnssObservationResult =
        delegate.observe(request)

    fun preflight(): GnssPreflightSnapshot = GnssPreflightSnapshot(
        permissionPrecision = platform.permissionPrecision(),
        locationEnabled = platform.isLocationEnabled(),
        gpsProviderExists = platform.hasGpsProvider(),
        gpsProviderEnabled = platform.isGpsProviderEnabled(),
    )
}

private class AndroidGnssObservationPlatform(
    context: Context,
) : GnssObservationPlatform {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    override fun permissionPrecision(): PermissionPrecision {
        val fine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return locationPermissionPrecision(fineGranted = fine, coarseGranted = coarse)
    }

    override fun isLocationEnabled(): Boolean = locationManager?.let { manager ->
        runCatching { LocationManagerCompat.isLocationEnabled(manager) }.getOrDefault(false)
    } == true

    override fun hasGpsProvider(): Boolean = locationManager?.let { manager ->
        runCatching {
            LocationManagerCompat.hasProvider(manager, LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
    } == true

    override fun isGpsProviderEnabled(): Boolean = locationManager?.let { manager ->
        runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    } == true

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override suspend fun observe(observationWindowMs: Long): GnssPlatformObservation {
        val manager = locationManager ?: return GnssPlatformObservation.RegistrationFailed(
            "LocationManager is unavailable.",
        )
        val gnssStarted = AtomicBoolean(false)
        val firstFixObserved = AtomicBoolean(false)
        val latestSnapshot = LatestGnssSnapshot()
        val callback = object : GnssStatusCompat.Callback() {
            override fun onStarted() {
                gnssStarted.set(true)
            }

            override fun onFirstFix(ttffMillis: Int) {
                firstFixObserved.set(true)
            }

            override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
                latestSnapshot.update(status.toSatelliteSnapshot())
            }
        }
        val cancellationSignal = CancellationSignal()
        try {
            return runGnssObservationWindow(
                observationWindowMs = observationWindowMs,
                register = {
                    LocationManagerCompat.registerGnssStatusCallback(
                        manager,
                        ContextCompat.getMainExecutor(appContext),
                        callback,
                    )
                },
                startGpsActivation = {
                    LocationManagerCompat.getCurrentLocation(
                        manager,
                        LocationManager.GPS_PROVIDER,
                        cancellationSignal,
                        ContextCompat.getMainExecutor(appContext),
                    ) { /* The request exists only to activate GNSS during the observation window. */ }
                },
                cancelGpsActivation = cancellationSignal::cancel,
                unregister = {
                    LocationManagerCompat.unregisterGnssStatusCallback(manager, callback)
                },
                completed = {
                    GnssPlatformObservation.Completed(
                        gnssStarted = gnssStarted.get(),
                        firstFixObserved = firstFixObserved.get(),
                        latestSnapshot = latestSnapshot.get(),
                    )
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SecurityException) {
            return GnssPlatformObservation.RegistrationFailed(
                failure.message ?: "Android denied GNSS status access.",
            )
        } catch (failure: RuntimeException) {
            return GnssPlatformObservation.RegistrationFailed(
                failure.message ?: "GNSS status observation failed.",
            )
        }
    }
}

internal suspend fun runGnssObservationWindow(
    observationWindowMs: Long,
    register: () -> Boolean,
    startGpsActivation: () -> Unit,
    cancelGpsActivation: () -> Unit,
    unregister: () -> Unit,
    completed: () -> GnssPlatformObservation.Completed,
): GnssPlatformObservation {
    var registered = false
    try {
        registered = register()
        if (!registered) {
            return GnssPlatformObservation.RegistrationFailed(
                "Android rejected GNSS status callback registration.",
            )
        }
        startGpsActivation()
        delay(observationWindowMs)
        return completed()
    } finally {
        runCatching(cancelGpsActivation)
        if (registered) {
            runCatching(unregister)
        }
    }
}

private fun GnssStatusCompat.toSatelliteSnapshot(): GnssSatelliteSnapshot = GnssSatelliteSnapshot(
    satellites = (0 until satelliteCount).map { index ->
        GnssSatellite(
            constellation = constellationName(getConstellationType(index)),
            usedInFix = usedInFix(index),
        )
    },
)

internal fun constellationName(type: Int): GnssConstellation = when (type) {
    GnssStatusCompat.CONSTELLATION_BEIDOU -> GnssConstellation.BEIDOU
    GnssStatusCompat.CONSTELLATION_GPS -> GnssConstellation.GPS
    GnssStatusCompat.CONSTELLATION_GLONASS -> GnssConstellation.GLONASS
    GnssStatusCompat.CONSTELLATION_GALILEO -> GnssConstellation.GALILEO
    GnssStatusCompat.CONSTELLATION_QZSS -> GnssConstellation.QZSS
    GnssStatusCompat.CONSTELLATION_SBAS -> GnssConstellation.SBAS
    GnssStatusCompat.CONSTELLATION_IRNSS -> GnssConstellation.IRNSS
    else -> GnssConstellation.UNKNOWN
}
