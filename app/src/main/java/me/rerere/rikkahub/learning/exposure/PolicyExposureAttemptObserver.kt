package me.rerere.rikkahub.learning.exposure

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.ai.ProviderAttemptEvent
import me.rerere.rikkahub.data.ai.ProviderAttemptObserver

/**
 * Process-local adapter for one already selected Policy-bearing request.
 *
 * The primary reservation and its COMPILED/INJECTED milestones must be durable before this object
 * is created. A watchdog retry receives a separate durable reservation. Storage loss after the
 * first dispatch latches attribution UNKNOWN but never dispatches a fallback or a second request.
 */
class PolicyExposureAttemptObserver private constructor(
    private val store: PolicyExposureStore,
    private val primaryReservation: PolicyExposureReservation,
    private val metadata: PolicyExposureMetadata,
    primaryReceipt: PolicyExposureReceipt,
    private val clock: () -> Long,
) : ProviderAttemptObserver {
    private val mutex = Mutex()
    private val receipts = mutableMapOf(1 to primaryReceipt)
    private val unavailableOrdinals = mutableSetOf<Int>()
    private var lastTimestamp = primaryReceipt.stateVersion.coerceAtLeast(0L).let { clock().coerceAtLeast(0L) }

    override suspend fun prepareForDispatch(attemptOrdinal: Int, isRetry: Boolean): Boolean =
        mutex.withLock {
            when {
                attemptOrdinal == 1 && !isRetry -> receipts[1] != null && 1 !in unavailableOrdinals
                attemptOrdinal <= 1 || !isRetry -> false
                attemptOrdinal in unavailableOrdinals -> false
                receipts[attemptOrdinal] != null -> true
                else -> reserveRetry(attemptOrdinal) != null
            }
        }

    override suspend fun observe(event: ProviderAttemptEvent) = mutex.withLock {
        val ordinal = event.attemptOrdinal
        if (ordinal in unavailableOrdinals) return@withLock
        var receipt = receipts[ordinal]
        // Every retry must have been reserved by prepareForDispatch before HostDispatched.
        receipt ?: run {
            unavailableOrdinals += ordinal
            return@withLock
        }
        val now = nextTimestamp()
        when (val result = store.observeProviderAttempt(
            reservationId = receipt.reservation.key.reservationId,
            expectedStateVersion = receipt.stateVersion,
            event = event,
            frozenNowEpochMs = now,
        )) {
            is PolicyExposureStoreResult.Available -> receipts[ordinal] = result.receipt
            is PolicyExposureStoreResult.Conflict -> {
                result.currentReceipt?.let { current -> receipts[ordinal] = current }
                unavailableOrdinals += ordinal
            }
            is PolicyExposureStoreResult.Unavailable -> unavailableOrdinals += ordinal
        }
    }

    suspend fun currentReceipt(attemptOrdinal: Int = 1): PolicyExposureReceipt? =
        mutex.withLock { receipts[attemptOrdinal] }

    private suspend fun reserveRetry(ordinal: Int): PolicyExposureReceipt? {
        if (ordinal <= 1) return null
        var reservation = primaryReservation
        repeat(ordinal - 1) { reservation = reservation.nextRetry() }
        val reserved = store.reserve(reservation, metadata, nextTimestamp())
            as? PolicyExposureStoreResult.Available ?: return null
        var receipt = reserved.receipt
        for (state in listOf(PolicyExposureState.COMPILED, PolicyExposureState.INJECTED)) {
            val next = store.observeMilestone(
                reservationId = reservation.key.reservationId,
                expectedStateVersion = receipt.stateVersion,
                state = state,
                frozenNowEpochMs = nextTimestamp(),
            ) as? PolicyExposureStoreResult.Available ?: return null
            receipt = next.receipt
        }
        receipts[ordinal] = receipt
        return receipt
    }

    private fun nextTimestamp(): Long = clock().coerceAtLeast(lastTimestamp).also {
        lastTimestamp = it
    }

    companion object {
        suspend fun create(
            store: PolicyExposureStore,
            reservation: PolicyExposureReservation,
            metadata: PolicyExposureMetadata,
            frozenNowEpochMs: Long,
            clock: () -> Long = System::currentTimeMillis,
            onReservedBeforeCompileOrInjection: suspend (PolicyExposureReceipt) -> Boolean = {
                true
            },
        ): PolicyExposureAttemptObserver? {
            return try {
                val reserved = store.reserve(reservation, metadata, frozenNowEpochMs)
                    as? PolicyExposureStoreResult.Available ?: return null
                var receipt = reserved.receipt
                if (!onReservedBeforeCompileOrInjection(receipt)) return null
                for (state in listOf(PolicyExposureState.COMPILED, PolicyExposureState.INJECTED)) {
                    val next = store.observeMilestone(
                        reservationId = reservation.key.reservationId,
                        expectedStateVersion = receipt.stateVersion,
                        state = state,
                        frozenNowEpochMs = frozenNowEpochMs,
                    ) as? PolicyExposureStoreResult.Available ?: return null
                    receipt = next.receipt
                }
                PolicyExposureAttemptObserver(store, reservation, metadata, receipt, clock)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        }
    }
}
