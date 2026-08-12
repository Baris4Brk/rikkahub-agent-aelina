package me.rerere.rikkahub.learning.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Typed and bounded policy-only lineage edge; it is not a general memory graph. */
@Entity(
    tableName = "policy_lineage",
    primaryKeys = ["child_policy_id", "parent_policy_id", "relation_type"],
    foreignKeys = [
        ForeignKey(
            entity = LearningPolicyEntity::class,
            parentColumns = ["id"],
            childColumns = ["child_policy_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LearningPolicyEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_policy_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["child_policy_id"]),
        Index(value = ["parent_policy_id"]),
    ],
)
data class PolicyLineageEntity(
    @ColumnInfo(name = "child_policy_id")
    val childPolicyId: String,
    @ColumnInfo(name = "parent_policy_id")
    val parentPolicyId: String,
    @ColumnInfo(name = "relation_type")
    val relationType: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
) {
    init {
        requireLearningStorageId(childPolicyId, "child policy ID")
        requireLearningStorageId(parentPolicyId, "parent policy ID")
        require(childPolicyId != parentPolicyId) { "Policy lineage cannot self-reference" }
        require(LearningPolicyLineageRelation.entries.any { it.name == relationType }) {
            "Invalid policy lineage relation"
        }
        require(createdAtMs >= 0L) { "Negative policy lineage creation time" }
    }

    override fun toString(): String =
        "PolicyLineageEntity(relation=$relationType, ids=<redacted>)"
}
