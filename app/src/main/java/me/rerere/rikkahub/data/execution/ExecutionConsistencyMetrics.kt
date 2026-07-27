package me.rerere.rikkahub.data.execution

import java.util.concurrent.atomic.AtomicLong

data class ExecutionConsistencyMetricsSnapshot(
    val casConflicts: Long,
    val staleProbeDiscards: Long,
)

/** Process-local counters only; diagnostics never persist record payloads or probe output. */
class ExecutionConsistencyMetrics {
    private val casConflicts = AtomicLong()
    private val staleProbeDiscards = AtomicLong()

    fun recordCasConflict() {
        casConflicts.incrementAndGet()
    }

    fun recordStaleProbeDiscard() {
        staleProbeDiscards.incrementAndGet()
    }

    fun snapshot() = ExecutionConsistencyMetricsSnapshot(
        casConflicts = casConflicts.get(),
        staleProbeDiscards = staleProbeDiscards.get(),
    )
}
