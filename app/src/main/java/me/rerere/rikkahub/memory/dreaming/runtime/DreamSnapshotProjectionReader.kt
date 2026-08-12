package me.rerere.rikkahub.memory.dreaming.runtime

import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId

data class DreamSnapshotProjectionReadRequest(
    val scopeId: DreamScopeId,
    /** The same frozen clock later passed to [DreamContextCompiler]. */
    val frozenNowEpochMs: Long,
)

/**
 * Storage seam for M6. Implementations must atomically read scope state, active Snapshot, immutable
 * Claim versions, current Claim heads and their live authority pins. Any parse/read ambiguity is an
 * [DreamSnapshotProjection.Unavailable] result, not an exception-backed partial projection.
 */
fun interface DreamSnapshotProjectionReader {
    suspend fun read(request: DreamSnapshotProjectionReadRequest): DreamSnapshotProjection
}
