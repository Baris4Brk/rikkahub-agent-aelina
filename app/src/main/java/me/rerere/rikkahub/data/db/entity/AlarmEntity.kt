package me.rerere.rikkahub.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey
    val id: String,                    // UUID
    val label: String,                 // Alarm label / title
    val note: String? = null,          // Optional note / message
    val scheduleType: String,          // "once" | "weekly"
    val time: String? = null,          // ISO-8601 for "once", e.g. "2026-07-10T08:00:00"
    val hour: Int? = null,             // Hour (0-23) for "weekly"
    val minute: Int? = null,           // Minute (0-59) for "weekly"
    val daysOfWeek: String? = null,    // Comma-separated, e.g. "1,3,5" (Mon=1, Sun=7)
    val timezone: String = java.time.ZoneId.systemDefault().id,
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val lastFiredAtMs: Long? = null,
    val nextFireAtMs: Long? = null,
)
