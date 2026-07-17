package me.rerere.rikkahub.data.ai.tools.local

import java.util.concurrent.atomic.AtomicReference

internal const val DEFAULT_GNSS_OBSERVATION_WINDOW_MS = 8_000L
internal const val MIN_GNSS_OBSERVATION_WINDOW_MS = 5_000L
internal const val MAX_GNSS_OBSERVATION_WINDOW_MS = 10_000L

internal data class GnssObservationRequest(
    val observationWindowMs: Long = DEFAULT_GNSS_OBSERVATION_WINDOW_MS,
)

internal enum class GnssConstellation(val wireName: String) {
    BEIDOU("beidou"),
    GPS("gps"),
    GLONASS("glonass"),
    GALILEO("galileo"),
    QZSS("qzss"),
    SBAS("sbas"),
    IRNSS("irnss"),
    UNKNOWN("unknown"),
}

internal data class GnssSatellite(
    val constellation: GnssConstellation,
    val usedInFix: Boolean,
)

internal data class GnssSatelliteSnapshot(
    val satellites: List<GnssSatellite>,
)

internal class LatestGnssSnapshot {
    private val latest = AtomicReference<GnssSatelliteSnapshot?>(null)

    fun update(snapshot: GnssSatelliteSnapshot) {
        latest.set(snapshot)
    }

    fun get(): GnssSatelliteSnapshot? = latest.get()
}

internal data class ConstellationCounts(
    val visible: Int,
    val usedInFix: Int,
)

internal sealed interface GnssObservationResult {
    data class Success(
        val observationWindowMs: Long,
        val gnssStarted: Boolean,
        val firstFixObserved: Boolean,
        val satellitesVisible: Int,
        val satellitesUsedInFix: Int,
        val constellations: Map<String, ConstellationCounts>,
        val observedAtMs: Long,
        val warningCode: String? = null,
        val warning: String? = null,
    ) : GnssObservationResult

    data class Failure(
        val code: String,
        val message: String,
        val recovery: String,
    ) : GnssObservationResult
}

internal fun interface GnssObservationSource {
    suspend fun observe(request: GnssObservationRequest): GnssObservationResult
}

internal sealed interface GnssPlatformObservation {
    data class Completed(
        val gnssStarted: Boolean,
        val firstFixObserved: Boolean,
        val latestSnapshot: GnssSatelliteSnapshot?,
    ) : GnssPlatformObservation

    data class RegistrationFailed(val message: String) : GnssPlatformObservation
}

internal interface GnssObservationPlatform {
    fun permissionPrecision(): PermissionPrecision
    fun isLocationEnabled(): Boolean
    fun hasGpsProvider(): Boolean
    fun isGpsProviderEnabled(): Boolean
    fun currentTimeMillis(): Long
    suspend fun observe(observationWindowMs: Long): GnssPlatformObservation
}

internal class DefaultGnssObservationSource(
    private val platform: GnssObservationPlatform,
) : GnssObservationSource {
    override suspend fun observe(request: GnssObservationRequest): GnssObservationResult {
        when (platform.permissionPrecision()) {
            PermissionPrecision.NONE -> return GnssObservationResult.Failure(
                code = "LOCATION_PERMISSION_MISSING",
                message = "Foreground location permission is not granted.",
                recovery = "Grant precise location permission to observe GNSS satellites.",
            )
            PermissionPrecision.COARSE -> return GnssObservationResult.Failure(
                code = "PRECISE_LOCATION_NOT_GRANTED",
                message = "Precise location permission is required to observe GNSS satellites.",
                recovery = "Enable Precise location for RikkaHub in Android app permissions.",
            )
            PermissionPrecision.FINE -> Unit
        }
        if (!platform.isLocationEnabled()) {
            return GnssObservationResult.Failure(
                code = "LOCATION_SERVICES_DISABLED",
                message = "Android location services are disabled.",
                recovery = "Enable Location in Android system settings.",
            )
        }
        if (!platform.hasGpsProvider()) {
            return GnssObservationResult.Failure(
                code = "PROVIDER_UNAVAILABLE",
                message = "The GPS provider is unavailable on this device.",
                recovery = "Check whether this device supports satellite positioning.",
            )
        }
        if (!platform.isGpsProviderEnabled()) {
            return GnssObservationResult.Failure(
                code = "GPS_PROVIDER_DISABLED",
                message = "The GPS provider exists but is disabled.",
                recovery = "Enable satellite positioning in Android location settings.",
            )
        }
        return when (val observation = platform.observe(request.observationWindowMs)) {
            is GnssPlatformObservation.RegistrationFailed -> GnssObservationResult.Failure(
                code = "GNSS_REGISTRATION_FAILED",
                message = observation.message,
                recovery = "Retry the observation or review the device location settings.",
            )
            is GnssPlatformObservation.Completed -> {
                val snapshot = observation.latestSnapshot
                    ?: return GnssObservationResult.Failure(
                        code = "GNSS_STATUS_TIMEOUT",
                        message = "No GNSS status snapshot was observed during the requested window.",
                        recovery = "Move near a window or outdoors and try again.",
                    )
                val counts = GnssConstellation.entries.associate { constellation ->
                    val satellites = snapshot.satellites.filter { it.constellation == constellation }
                    constellation.wireName to ConstellationCounts(
                        visible = satellites.size,
                        usedInFix = satellites.count { it.usedInFix },
                    )
                }
                val visible = snapshot.satellites.size
                GnssObservationResult.Success(
                    observationWindowMs = request.observationWindowMs,
                    gnssStarted = observation.gnssStarted,
                    firstFixObserved = observation.firstFixObserved,
                    satellitesVisible = visible,
                    satellitesUsedInFix = snapshot.satellites.count { it.usedInFix },
                    constellations = counts,
                    observedAtMs = platform.currentTimeMillis(),
                    warningCode = if (visible == 0) "NO_SATELLITES_VISIBLE" else null,
                    warning = if (visible == 0) {
                        "GNSS reported no visible satellites during this observation window."
                    } else {
                        null
                    },
                )
            }
        }
    }
}
