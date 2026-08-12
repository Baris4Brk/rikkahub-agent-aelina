package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Durable proof that a source was deleted or scrubbed.
 *
 * The source digest participates in the key so a later version of the same message can be
 * distinguished from deleted content. An empty digest is the fail-closed wildcard for legacy
 * message tombstones and all conversation tombstones.
 */
@Entity(
    tableName = "memory_source_tombstones",
    primaryKeys = [
        "scope_id",
        "conversation_id",
        "source_kind",
        "source_id",
        "source_digest",
    ],
)
data class MemorySourceTombstoneEntity(
    @ColumnInfo("scope_id") val scopeId: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("source_kind") val sourceKind: String,
    @ColumnInfo("source_id") val sourceId: String,
    @ColumnInfo(name = "source_digest", defaultValue = "''")
    val sourceDigest: String = "",
    @ColumnInfo("reason_code") val reasonCode: String,
    @ColumnInfo("tombstoned_at_ms") val tombstonedAtMs: Long,
)
