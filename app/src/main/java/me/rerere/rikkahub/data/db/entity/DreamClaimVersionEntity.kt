package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey

/** Immutable Claim history, except that privacy erasure may scrub [canonicalClaimJson]. */
@Entity(
    tableName = "dream_claim_versions",
    primaryKeys = ["claim_id", "claim_revision"],
    foreignKeys = [
        ForeignKey(
            entity = DreamClaimEntity::class,
            parentColumns = ["claim_id"],
            childColumns = ["claim_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DreamClaimVersionEntity(
    @ColumnInfo("claim_id")
    val claimId: String,
    @ColumnInfo("claim_revision")
    val claimRevision: Long,
    @ColumnInfo("canonical_claim_json")
    val canonicalClaimJson: String,
    @ColumnInfo("content_hash")
    val contentHash: String,
    @ColumnInfo("source_manifest_hash")
    val sourceManifestHash: String,
    @ColumnInfo("reason_code")
    val reasonCode: String,
    @ColumnInfo("created_by_run_id")
    val createdByRunId: String,
    @ColumnInfo("created_at_ms")
    val createdAtMs: Long,
)
