package me.rerere.rikkahub.data.ai.tools.local

data class ReverseGeocodeRuntimeDiagnostics(
    val androidGeocoderPresent: Boolean,
    val cacheEntryCount: Int,
    val cacheHitCount: Long,
    val lastStatus: String?,
    val lastErrorCode: String?,
    val lastProviderId: String?,
    val lastDurationBucket: String?,
)

class ReverseGeocodeRuntimeDiagnosticsSource internal constructor(
    private val androidClient: AndroidGeocoderClient,
    private val cache: ReverseGeocodeCache,
    private val diagnostics: ReverseGeocodeDiagnosticsStore,
) {
    suspend fun snapshot(): ReverseGeocodeRuntimeDiagnostics {
        val cacheStats = cache.stats()
        val state = diagnostics.snapshot()
        return ReverseGeocodeRuntimeDiagnostics(
            androidGeocoderPresent = androidClient.isPresent(),
            cacheEntryCount = cacheStats.entryCount,
            cacheHitCount = cacheStats.hitCount,
            lastStatus = state.lastStatus,
            lastErrorCode = state.lastErrorCode,
            lastProviderId = state.lastProviderId,
            lastDurationBucket = state.lastDurationBucket,
        )
    }
}
