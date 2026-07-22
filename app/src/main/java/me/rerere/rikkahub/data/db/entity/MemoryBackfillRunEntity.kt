package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "memory_backfill_runs", indices = [Index("assistant_id"), Index("status")])
data class MemoryBackfillRunEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("assistant_id") val assistantId: String,
    @ColumnInfo("scope_id") val scopeId: String,
    @ColumnInfo("selection_json") val selectionJson: String,
    @ColumnInfo("total_turns") val totalTurns: Int,
    @ColumnInfo("processed_turns", defaultValue = "0") val processedTurns: Int = 0,
    @ColumnInfo("failed_turns", defaultValue = "0") val failedTurns: Int = 0,
    @ColumnInfo(defaultValue = "'PENDING'") val status: String = "PENDING",
    @ColumnInfo("last_error") val lastError: String? = null,
    @ColumnInfo("created_at_ms") val createdAtMs: Long,
    @ColumnInfo("updated_at_ms") val updatedAtMs: Long,
)
