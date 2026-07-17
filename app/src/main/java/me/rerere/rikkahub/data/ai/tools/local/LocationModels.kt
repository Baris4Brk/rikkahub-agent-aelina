package me.rerere.rikkahub.data.ai.tools.local

internal object LocationProviders {
    const val GPS = "gps"
    const val NETWORK = "network"
    const val PASSIVE = "passive"
    const val FUSED = "fused"
}

internal enum class RequestedAccuracy(
    val wireName: String,
    val defaultTimeoutMs: Long,
    val defaultDirectCacheMaxAgeMs: Long,
    val defaultFallbackCacheMaxAgeMs: Long,
) {
    HIGH("high", 45_000L, 0L, 300_000L),
    BALANCED("balanced", 30_000L, 10_000L, 600_000L),
    LOW("low", 15_000L, 30_000L, 900_000L),
    ;

    companion object {
        fun fromWireName(value: String): RequestedAccuracy? = entries.firstOrNull { it.wireName == value }
    }
}

internal enum class PermissionPrecision(val wireName: String) {
    NONE("none"),
    COARSE("coarse"),
    FINE("fine"),
}

internal data class LocationRequest(
    val accuracy: RequestedAccuracy = RequestedAccuracy.BALANCED,
    val timeoutMs: Long = accuracy.defaultTimeoutMs,
    val allowCached: Boolean = true,
    val directCacheMaxAgeMs: Long = accuracy.defaultDirectCacheMaxAgeMs,
    val fallbackCacheMaxAgeMs: Long = accuracy.defaultFallbackCacheMaxAgeMs,
)

internal data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val altitudeM: Double? = null,
    val speedMps: Float? = null,
    val bearingDegrees: Float? = null,
    val provider: String,
    val timestampMs: Long,
    val elapsedRealtimeNanos: Long,
)

internal sealed interface NativeLocationResult {
    data class Success(
        val fix: LocationFix,
        val provider: String,
        val generatedAfterRequest: Boolean,
        val ageMs: Long,
    ) : NativeLocationResult

    data class Timeout(val provider: String) : NativeLocationResult
    data object PermissionDenied : NativeLocationResult
    data object LocationDisabled : NativeLocationResult
    data class ProviderUnavailable(val provider: String) : NativeLocationResult
    data class Failure(
        val provider: String?,
        val code: String,
        val message: String,
    ) : NativeLocationResult
}

internal enum class LocationSourceKind(val wireName: String) {
    ANDROID_LOCATION_MANAGER("android_location_manager"),
    GMS_FUSED("gms_fused"),
    LAST_KNOWN_LOCATION("last_known_location"),
}

internal enum class LocationSourceType(val wireName: String) {
    GNSS("gnss"),
    FUSED("fused"),
    NETWORK("network"),
    PASSIVE("passive"),
    APPROXIMATE("approximate"),
    UNKNOWN("unknown"),
}

internal sealed interface LocationResolution {
    data class Success(
        val fix: LocationFix,
        val source: LocationSourceKind,
        val sourceType: LocationSourceType,
        val generatedAfterRequest: Boolean,
        val ageMs: Long,
        val cached: Boolean,
        val fresh: Boolean,
        val permissionPrecision: PermissionPrecision,
        val requestedAccuracy: RequestedAccuracy,
        val cacheStatus: String? = null,
        val warningCode: String? = null,
        val warning: String? = null,
    ) : LocationResolution

    data class Failure(
        val code: String,
        val message: String,
        val recovery: String? = null,
    ) : LocationResolution
}

internal fun interface LocationResolver {
    suspend fun resolve(request: LocationRequest): LocationResolution
}

internal interface LocationPlatformAdapter {
    fun permissionPrecision(): PermissionPrecision
    fun isLocationEnabled(): Boolean
    fun sdkInt(): Int
    fun hasProvider(provider: String): Boolean
    fun isProviderEnabled(provider: String): Boolean
    fun elapsedRealtimeMs(): Long
    fun elapsedRealtimeNanos(): Long
    fun collectCachedFixes(): List<LocationFix>

    suspend fun requestCurrentLocation(
        provider: String,
        timeoutMs: Long,
        requestStartedElapsedNanos: Long,
    ): NativeLocationResult
}

internal interface GmsLocationAdapter {
    fun isAvailable(): Boolean

    suspend fun requestCurrentLocation(
        accuracy: RequestedAccuracy,
        timeoutMs: Long,
        requestStartedElapsedNanos: Long,
    ): NativeLocationResult
}
