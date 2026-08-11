package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_link_revisions",
    indices = [
        Index(value = ["link_id", "revision"], unique = true),
        Index(value = ["link_id", "created_at_ms"]),
        Index("relation_candidate_id"),
    ],
)
data class MemoryLinkRevisionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("link_id") val linkId: String,
    val revision: Int,
    val operation: String,
    @ColumnInfo("before_snapshot_json") val beforeSnapshotJson: String? = null,
    @ColumnInfo("after_snapshot_json") val afterSnapshotJson: String? = null,
    val actor: String,
    @ColumnInfo("relation_candidate_id") val relationCandidateId: String? = null,
    @ColumnInfo("reason_code") val reasonCode: String? = null,
    @ColumnInfo("created_at_ms") val createdAtMs: Long,
)
