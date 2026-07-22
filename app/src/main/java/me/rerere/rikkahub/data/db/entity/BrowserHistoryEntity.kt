package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "browser_history",
    indices = [
        Index(value = ["normalized_url"]),
        Index(value = ["visited_at_ms"]),
    ],
)
data class BrowserHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "normalized_url") val normalizedUrl: String,
    val url: String,
    val title: String,
    @ColumnInfo(name = "visited_at_ms") val visitedAtMs: Long,
)
