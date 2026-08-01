package me.rerere.rikkahub.data.ai.tools.local

internal data class ReverseGeocodeDiagnosticsSnapshot(
    val cacheHitCount: Long,
    val successCount: Long,
    val failureCount: Long,
    val lastStatus: String?,
    val lastErrorCode: String?,
    val lastProviderId: String?,
    val lastDurationBucket: String?,
)

internal class ReverseGeocodeDiagnosticsStore {
    private val lock = Any()
    private var cacheHitCount = 0L
    private var successCount = 0L
    private var failureCount = 0L
    private var lastStatus: String? = null
    private var lastErrorCode: String? = null
    private var lastProviderId: String? = null
    private var lastDurationBucket: String? = null

    fun recordCacheHit() = synchronized(lock) {
        cacheHitCount += 1
        lastStatus = "CACHE_HIT"
        lastErrorCode = null
        lastProviderId = null
        lastDurationBucket = null
    }

    fun recordResult(providerId: String, result: ReverseGeocodeResolution, durationMs: Long) = synchronized(lock) {
        lastProviderId = providerId
        lastDurationBucket = durationMs.toDurationBucket()
        when (result) {
            is ReverseGeocodeResolution.Success -> {
                successCount += 1
                lastStatus = "SUCCESS"
                lastErrorCode = null
            }
            is ReverseGeocodeResolution.Failure -> {
                failureCount += 1
                lastStatus = "FAILED"
                lastErrorCode = result.error.code
            }
        }
    }

    fun snapshot(): ReverseGeocodeDiagnosticsSnapshot = synchronized(lock) {
        ReverseGeocodeDiagnosticsSnapshot(
            cacheHitCount = cacheHitCount,
            successCount = successCount,
            failureCount = failureCount,
            lastStatus = lastStatus,
            lastErrorCode = lastErrorCode,
            lastProviderId = lastProviderId,
            lastDurationBucket = lastDurationBucket,
        )
    }
}

private fun Long.toDurationBucket(): String = when {
    this < 100L -> "LT_100_MS"
    this < 500L -> "LT_500_MS"
    this < 1_000L -> "LT_1_S"
    this < 5_000L -> "LT_5_S"
    else -> "GTE_5_S"
}
