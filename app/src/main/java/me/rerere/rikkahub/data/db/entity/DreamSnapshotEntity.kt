package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Bounded immutable compiled view; privacy erasure may scrub its payload and tombstone it. */
@Entity(
    tableName = "dream_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = MemoryScopeStateEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["scope_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["scope_id", "snapshot_revision"], unique = true),
        Index(value = ["scope_id", "status", "created_at_ms"]),
    ],
)
data class DreamSnapshotEntity(
    @PrimaryKey
    @ColumnInfo("snapshot_id")
    val snapshotId: String,
    @ColumnInfo("scope_id")
    val scopeId: String,
    @ColumnInfo("snapshot_revision")
    val snapshotRevision: Long,
    @ColumnInfo("source_memory_epoch")
    val sourceMemoryEpoch: Long,
    @ColumnInfo("committed_dream_revision")
    val committedDreamRevision: Long,
    val status: String,
    @ColumnInfo("canonical_payload_json")
    val canonicalPayloadJson: String,
    @ColumnInfo("payload_sha256")
    val payloadSha256: String,
    @ColumnInfo("compiler_revision")
    val compilerRevision: String,
    @ColumnInfo("estimated_tokens")
    val estimatedTokens: Int,
    @ColumnInfo("claim_count")
    val claimCount: Int,
    @ColumnInfo("created_by_run_id")
    val createdByRunId: String,
    @ColumnInfo("created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo("supersedes_snapshot_id")
    val supersedesSnapshotId: String? = null,
    @ColumnInfo("reason_code")
    val reasonCode: String,
)
