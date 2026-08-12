package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-scope authority for Memory observer progress and single-run ownership.
 *
 * [activeRunId] and [activeRunLeaseUntilMs] are the lease authority. The corresponding fields on
 * [DreamRunEntity] are an audit mirror and must never be used on their own to prove ownership.
 */
@Entity(
    tableName = "memory_scope_state",
    indices = [
        Index(value = ["active_run_id"], unique = true),
        Index(value = ["active_run_lease_until_ms"]),
        // There is deliberately no reverse FK to dream_snapshots: v45 cannot add it without
        // rebuilding this parent and both Observer child tables. Guarded DAO CAS owns the link.
        Index(value = ["active_snapshot_id"], unique = true),
    ],
)
data class MemoryScopeStateEntity(
    @PrimaryKey
    @ColumnInfo("scope_id")
    val scopeId: String,
    @ColumnInfo(name = "memory_epoch", defaultValue = "0")
    val memoryEpoch: Long = 0,
    @ColumnInfo(name = "observer_checkpoint_epoch", defaultValue = "0")
    val observerCheckpointEpoch: Long = 0,
    @ColumnInfo("active_run_id")
    val activeRunId: String? = null,
    @ColumnInfo("active_run_lease_until_ms")
    val activeRunLeaseUntilMs: Long? = null,
    @ColumnInfo("updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo("last_reason_code")
    val lastReasonCode: String? = null,
    @ColumnInfo(name = "dream_state_revision", defaultValue = "0")
    val dreamStateRevision: Long = 0,
    @ColumnInfo(name = "last_applied_memory_epoch", defaultValue = "0")
    val lastAppliedMemoryEpoch: Long = 0,
    @ColumnInfo("active_snapshot_id")
    val activeSnapshotId: String? = null,
    @ColumnInfo("last_full_rebuild_at_ms")
    val lastFullRebuildAtMs: Long? = null,
)
