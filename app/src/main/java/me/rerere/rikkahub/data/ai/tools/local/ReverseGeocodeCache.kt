package me.rerere.rikkahub.data.ai.tools.local

import kotlin.math.pow
import kotlin.math.roundToLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface ReverseGeocodeCacheLookup {
    data object Miss : ReverseGeocodeCacheLookup
    data class Success(val address: StructuredAddress, val ageMs: Long) : ReverseGeocodeCacheLookup
    data class Failure(val error: ReverseGeocodeError, val ageMs: Long) : ReverseGeocodeCacheLookup
}

internal data class ReverseGeocodeCacheStats(
    val entryCount: Int,
    val hitCount: Long,
    val missCount: Long,
    val providerCooldownCount: Int,
)

internal class ReverseGeocodeCache(
    private val maxEntries: Int = 64,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<CacheKey, CacheEntry>(maxEntries, 0.75f, true)
    private val providerCooldowns = mutableMapOf<String, Long>()
    private var hitCount = 0L
    private var missCount = 0L

    init {
        require(maxEntries > 0)
    }

    suspend fun get(
        request: ReverseGeocodeRequest,
        providerId: String,
        providerRevision: Long,
        coordinateSystem: CoordinateSystem,
    ): ReverseGeocodeCacheLookup = mutex.withLock {
        val now = nowMs()
        val key = request.cacheKey(providerId, providerRevision, coordinateSystem)
        val entry = entries[key]
        if (entry == null || entry.expiresAtMs <= now) {
            if (entry != null) entries.remove(key)
            missCount += 1
            return@withLock ReverseGeocodeCacheLookup.Miss
        }
        hitCount += 1
        val age = (now - entry.createdAtMs).coerceAtLeast(0L)
        when (entry) {
            is CacheEntry.Success -> ReverseGeocodeCacheLookup.Success(entry.address, age)
            is CacheEntry.Failure -> ReverseGeocodeCacheLookup.Failure(entry.error, age)
        }
    }

    suspend fun putSuccess(
        request: ReverseGeocodeRequest,
        providerId: String,
        providerRevision: Long,
        coordinateSystem: CoordinateSystem,
        address: StructuredAddress,
    ) = mutex.withLock {
        val now = nowMs()
        entries[request.cacheKey(providerId, providerRevision, coordinateSystem)] = CacheEntry.Success(
            address = address,
            createdAtMs = now,
            expiresAtMs = safeExpiry(now, successTtlMs(request.detailLevel)),
        )
        trimToSize()
    }

    suspend fun putNoResult(
        request: ReverseGeocodeRequest,
        providerId: String,
        providerRevision: Long,
        coordinateSystem: CoordinateSystem,
        error: ReverseGeocodeError,
    ) = mutex.withLock {
        require(error.code == "NO_GEOCODER_RESULT") {
            "Only a provider's coordinate-specific no-result response may be cached."
        }
        val now = nowMs()
        entries[request.cacheKey(providerId, providerRevision, coordinateSystem)] = CacheEntry.Failure(
            error = error,
            createdAtMs = now,
            expiresAtMs = safeExpiry(now, NO_RESULT_TTL_MS),
        )
        trimToSize()
    }

    suspend fun setProviderCooldown(providerId: String, durationMs: Long) = mutex.withLock {
        if (durationMs > 0L) {
            providerCooldowns[providerId] = safeExpiry(nowMs(), durationMs)
        }
    }

    suspend fun isProviderCoolingDown(providerId: String): Boolean = mutex.withLock {
        val now = nowMs()
        val expiry = providerCooldowns[providerId] ?: return@withLock false
        if (expiry <= now) {
            providerCooldowns.remove(providerId)
            false
        } else {
            true
        }
    }

    suspend fun stats(): ReverseGeocodeCacheStats = mutex.withLock {
        val now = nowMs()
        entries.entries.removeAll { it.value.expiresAtMs <= now }
        providerCooldowns.entries.removeAll { it.value <= now }
        ReverseGeocodeCacheStats(
            entryCount = entries.size,
            hitCount = hitCount,
            missCount = missCount,
            providerCooldownCount = providerCooldowns.size,
        )
    }

    private fun trimToSize() {
        while (entries.size > maxEntries) {
            entries.remove(entries.entries.first().key)
        }
    }

    private sealed interface CacheEntry {
        val createdAtMs: Long
        val expiresAtMs: Long

        data class Success(
            val address: StructuredAddress,
            override val createdAtMs: Long,
            override val expiresAtMs: Long,
        ) : CacheEntry

        data class Failure(
            val error: ReverseGeocodeError,
            override val createdAtMs: Long,
            override val expiresAtMs: Long,
        ) : CacheEntry
    }

    private data class CacheKey(
        val providerId: String,
        val providerRevision: Long,
        val languageTag: String,
        val detailLevel: AddressDetailLevel,
        val coordinateSystem: CoordinateSystem,
        val latitudeBucket: Long,
        val longitudeBucket: Long,
    )

    private fun ReverseGeocodeRequest.cacheKey(
        providerId: String,
        providerRevision: Long,
        coordinateSystem: CoordinateSystem,
    ): CacheKey {
        val decimalPlaces = when (detailLevel) {
            AddressDetailLevel.ADMIN, AddressDetailLevel.CITY -> 3
            AddressDetailLevel.DISTRICT -> 4
            AddressDetailLevel.STREET, AddressDetailLevel.POI -> 5
        }
        val factor = 10.0.pow(decimalPlaces)
        return CacheKey(
            providerId = providerId,
            providerRevision = providerRevision,
            languageTag = languageTag,
            detailLevel = detailLevel,
            coordinateSystem = coordinateSystem,
            latitudeBucket = (latitude * factor).roundToLong(),
            longitudeBucket = (longitude * factor).roundToLong(),
        )
    }

    private fun successTtlMs(detail: AddressDetailLevel): Long = when (detail) {
        AddressDetailLevel.ADMIN, AddressDetailLevel.CITY -> 7L * 24L * 60L * 60L * 1_000L
        AddressDetailLevel.DISTRICT -> 72L * 60L * 60L * 1_000L
        AddressDetailLevel.STREET -> 24L * 60L * 60L * 1_000L
        AddressDetailLevel.POI -> 6L * 60L * 60L * 1_000L
    }

    private fun safeExpiry(now: Long, duration: Long): Long =
        if (Long.MAX_VALUE - now < duration) Long.MAX_VALUE else now + duration

    private companion object {
        const val NO_RESULT_TTL_MS = 10L * 60L * 1_000L
    }
}
