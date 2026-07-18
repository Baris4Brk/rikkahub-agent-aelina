package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class MemoryEntity(
    @PrimaryKey(true)
    val id: Int = 0,
    @ColumnInfo("assistant_id")
    val assistantId: String,
    @ColumnInfo("content")
    val content: String = "",
    @ColumnInfo("title")
    val title: String? = null,
    @ColumnInfo(name = "updated_at_ms", defaultValue = "0")
    val updatedAtMs: Long = 0L,
    @ColumnInfo(name = "importance", defaultValue = "0.5")
    val importance: Float = 0.5f,
)
