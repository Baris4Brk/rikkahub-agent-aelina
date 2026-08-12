package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A payload-free authority change receipt used to replay observer work after process death. */
@Entity(
    tableName = "memory_scope_changes",
    foreignKeys = [
        ForeignKey(
            entity = MemoryScopeStateEntity::class,
            parentColumns = ["scope_id"],
            childColumns = ["scope_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        // One transaction must coalesce repeated changes to the same entity before inserting.
        Index(
            value = ["scope_id", "memory_epoch", "entity_kind", "entity_id"],
            unique = true,
        ),
        Index(value = ["scope_id", "memory_epoch", "change_id"]),
    ],
)
data class MemoryScopeChangeEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo("change_id")
    val changeId: Long = 0,
    @ColumnInfo("scope_id")
    val scopeId: String,
    @ColumnInfo("memory_epoch")
    val memoryEpoch: Long,
    @ColumnInfo("entity_kind")
    val entityKind: String,
    @ColumnInfo("entity_id")
    val entityId: String,
    @ColumnInfo("entity_revision")
    val entityRevision: Long? = null,
    val operation: String,
    @ColumnInfo("reason_code")
    val reasonCode: String,
    @ColumnInfo("created_at_ms")
    val createdAtMs: Long,
)
