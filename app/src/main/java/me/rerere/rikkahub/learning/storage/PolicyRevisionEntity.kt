package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Immutable audit revision of sanitized policy content; no conversation/provider transcript. */
@Entity(
    tableName = "policy_revisions",
    primaryKeys = ["policy_id", "revision"],
    foreignKeys = [
        ForeignKey(
            entity = LearningPolicyEntity::class,
            parentColumns = ["id"],
            childColumns = ["policy_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["policy_id", "created_at_ms"])],
)
data class PolicyRevisionEntity(
    @ColumnInfo(name = "policy_id")
    val policyId: String,
    val revision: Long,
    @ColumnInfo(name = "before_snapshot")
    val beforeSnapshot: String?,
    @ColumnInfo(name = "after_snapshot")
    val afterSnapshot: String,
    @ColumnInfo(name = "before_artifact_sha256")
    val beforeArtifactSha256: String?,
    @ColumnInfo(name = "after_artifact_sha256")
    val afterArtifactSha256: String,
    @ColumnInfo(name = "reason_code")
    val reasonCode: String,
    val actor: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
) {
    init {
        requireLearningStorageId(policyId, "policy revision ID")
        require(revision > 0L) { "Invalid policy revision" }
        requireNullableBoundedRedactedText(
            beforeSnapshot,
            "policy before snapshot",
            MAX_POLICY_SNAPSHOT_CHARS,
        )
        requireBoundedRedactedText(
            afterSnapshot,
            "policy after snapshot",
            MAX_POLICY_SNAPSHOT_CHARS,
        )
        beforeArtifactSha256?.let { requireSha256(it, "prior policy artifact") }
        requireSha256(afterArtifactSha256, "policy revision artifact")
        require((beforeSnapshot == null) == (beforeArtifactSha256 == null)) {
            "Incomplete prior policy snapshot"
        }
        require((revision == 1L) == (beforeSnapshot == null)) {
            "Only policy creation may omit its prior snapshot"
        }
        require(LearningPolicyRevisionReason.entries.any { it.name == reasonCode }) {
            "Invalid policy revision reason"
        }
        require(LearningPolicyRevisionActor.entries.any { it.name == actor }) {
            "Invalid policy revision actor"
        }
        require(createdAtMs >= 0L) { "Negative policy revision creation time" }
    }

    override fun toString(): String =
        "PolicyRevisionEntity(revision=$revision, reason=$reasonCode, snapshots=<redacted>, ids=<redacted>)"
}
