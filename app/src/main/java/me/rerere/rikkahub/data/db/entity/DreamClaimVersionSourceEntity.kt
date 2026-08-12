package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Exact authority revision pin for one immutable Claim version. */
@Entity(
    tableName = "dream_claim_version_sources",
    primaryKeys = [
        "claim_id",
        "claim_revision",
        "memory_id",
        "memory_revision",
        "support_type",
    ],
    foreignKeys = [
        ForeignKey(
            entity = DreamClaimVersionEntity::class,
            parentColumns = ["claim_id", "claim_revision"],
            childColumns = ["claim_id", "claim_revision"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["memory_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MemoryRevisionEntity::class,
            parentColumns = ["memory_id", "revision"],
            childColumns = ["memory_id", "memory_revision"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["memory_id", "memory_revision"]),
        Index(value = ["claim_id", "claim_revision"]),
        Index(value = ["memory_evidence_id"]),
    ],
)
data class DreamClaimVersionSourceEntity(
    @ColumnInfo("claim_id")
    val claimId: String,
    @ColumnInfo("claim_revision")
    val claimRevision: Long,
    @ColumnInfo("memory_id")
    val memoryId: Int,
    @ColumnInfo("memory_revision")
    val memoryRevision: Int,
    @ColumnInfo("memory_semantic_hash")
    val memorySemanticHash: String,
    @ColumnInfo("memory_evidence_id")
    val memoryEvidenceId: String? = null,
    @ColumnInfo("support_type")
    val supportType: String,
    @ColumnInfo("created_at_ms")
    val createdAtMs: Long,
)
