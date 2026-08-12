package me.rerere.rikkahub.learning.resources

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

enum class LearningForegroundWorkKind {
    CONVERSATION_EXECUTION,
    PET_DIALOGUE,
    MANUAL_COMPRESSION,
}

enum class LearningForegroundHealth {
    HEALTHY,
    DEGRADED,
}

data class LearningForegroundSnapshot(
    val activeCount: Int = 0,
    val activeKinds: Set<LearningForegroundWorkKind> = emptySet(),
    /** Monotonic only on enter; an enter+exit cannot be hidden by StateFlow conflation. */
    val foregroundStartEpoch: Long = 0,
    val health: LearningForegroundHealth = LearningForegroundHealth.HEALTHY,
)

enum class LearningForegroundPreemption {
    FOREGROUND_STARTED,
    REGISTRY_DEGRADED,
}

/**
 * Process-local resource signal only. It is never a command/outcome/durability ledger.
 * Foreground work fails open; a damaged registry permanently makes background admission fail
 * closed for the lifetime of this process.
 */
class LearningForegroundRegistry {
    private val lock = Any()
    private val counts = mutableMapOf<LearningForegroundWorkKind, Int>()
    private val mutableSnapshot = MutableStateFlow(LearningForegroundSnapshot())

    val snapshot: StateFlow<LearningForegroundSnapshot> = mutableSnapshot.asStateFlow()

    fun enter(
        kind: LearningForegroundWorkKind,
        ownerJob: Job? = null,
    ): LearningForegroundLease {
        val entered = try {
            synchronized(lock) {
                val previous = mutableSnapshot.value
                val nextEpoch = Math.addExact(previous.foregroundStartEpoch, 1L)
                counts[kind] = Math.addExact(counts[kind] ?: 0, 1)
                publishLocked(
                    foregroundStartEpoch = nextEpoch,
                    health = previous.health,
                )
            }
            true
        } catch (_: Exception) {
            markDegraded()
            false
        }
        if (!entered) return LearningForegroundLease.NO_OP

        val lease = LearningForegroundLease { exit(kind) }
        ownerJob?.let { job ->
            try {
                lease.attachOwnerCompletion(job.invokeOnCompletion { lease.close() })
            } catch (_: Exception) {
                lease.close()
                markDegraded()
                return LearningForegroundLease.NO_OP
            }
        }
        return lease
    }

    suspend fun awaitForegroundAfter(admittedForegroundStartEpoch: Long): LearningForegroundPreemption {
        require(admittedForegroundStartEpoch >= 0L) { "Negative foreground epoch" }
        val next = snapshot.first { current ->
            current.health == LearningForegroundHealth.DEGRADED ||
                current.foregroundStartEpoch > admittedForegroundStartEpoch
        }
        return if (next.health == LearningForegroundHealth.DEGRADED) {
            LearningForegroundPreemption.REGISTRY_DEGRADED
        } else {
            LearningForegroundPreemption.FOREGROUND_STARTED
        }
    }

    private fun exit(kind: LearningForegroundWorkKind) {
        try {
            synchronized(lock) {
                val current = counts[kind]
                    ?: throw IllegalStateException("Unbalanced foreground lease")
                if (current <= 1) counts.remove(kind) else counts[kind] = current - 1
                publishLocked(
                    foregroundStartEpoch = mutableSnapshot.value.foregroundStartEpoch,
                    health = mutableSnapshot.value.health,
                )
            }
        } catch (_: Exception) {
            markDegraded()
        }
    }

    private fun publishLocked(
        foregroundStartEpoch: Long,
        health: LearningForegroundHealth,
    ) {
        var effectiveHealth = health
        val activeCount = try {
            counts.values.fold(0) { total, value -> Math.addExact(total, value) }
        } catch (_: ArithmeticException) {
            effectiveHealth = LearningForegroundHealth.DEGRADED
            Int.MAX_VALUE
        }
        mutableSnapshot.value = LearningForegroundSnapshot(
            activeCount = activeCount,
            activeKinds = counts.keys.toSet(),
            foregroundStartEpoch = foregroundStartEpoch,
            health = effectiveHealth,
        )
    }

    private fun markDegraded() {
        try {
            synchronized(lock) {
                val previous = mutableSnapshot.value
                mutableSnapshot.value = LearningForegroundSnapshot(
                    // A degraded registry is already a hard background stop. Preserve the last
                    // known non-negative count without risking another overflowing aggregation.
                    activeCount = previous.activeCount.coerceAtLeast(0),
                    activeKinds = counts.keys.toSet(),
                    foregroundStartEpoch = previous.foregroundStartEpoch,
                    health = LearningForegroundHealth.DEGRADED,
                )
            }
        } catch (_: Exception) {
            // Foreground work must fail open even if health publication itself is unavailable.
        }
    }
}

class LearningForegroundLease internal constructor(
    private val release: () -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private var ownerCompletion: DisposableHandle? = null

    internal fun attachOwnerCompletion(handle: DisposableHandle) {
        synchronized(lock) {
            if (closed.get()) {
                handle.dispose()
            } else {
                ownerCompletion = handle
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            ownerCompletion?.dispose()
            ownerCompletion = null
        }
        try {
            release()
        } catch (_: Exception) {
            // Lease release is deliberately no-throw for foreground callers.
        }
    }

    internal companion object {
        val NO_OP = LearningForegroundLease { }
    }
}
