package me.rerere.rikkahub.learning.storage.curator

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import me.rerere.rikkahub.learning.curator.CuratorApplyPlan
import me.rerere.rikkahub.learning.curator.CuratorLineageRelation
import me.rerere.rikkahub.learning.curator.isCuratorSha256
import me.rerere.rikkahub.learning.curator.isSafeCuratorId
import me.rerere.rikkahub.learning.curator.isSafeCuratorPlanId
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity

/** Exact Curator plan lineage. Rollback deactivates the row; it never deletes audit lineage. */
@Entity(
    tableName = "curator_delta_lineage",
    primaryKeys = ["candidate_id", "parent_policy_id", "child_policy_id", "relation_type"],
    foreignKeys = [
        ForeignKey(
            entity = CuratorDeltaCandidateEntity::class,
            parentColumns = ["id"],
            childColumns = ["candidate_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LearningPolicyEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_policy_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LearningPolicyEntity::class,
            parentColumns = ["id"],
            childColumns = ["child_policy_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["candidate_id", "active", "updated_at_ms"]),
        Index(value = ["parent_policy_id"]),
        Index(value = ["child_policy_id"]),
    ],
)
data class CuratorDeltaLineageEntity(
    @ColumnInfo(name = "candidate_id") val candidateId: String,
    @ColumnInfo(name = "apply_plan_id") val applyPlanId: String,
    @ColumnInfo(name = "parent_policy_id") val parentPolicyId: String,
    @ColumnInfo(name = "parent_revision") val parentRevision: Long,
    @ColumnInfo(name = "parent_artifact_sha256") val parentArtifactSha256: String,
    @ColumnInfo(name = "child_policy_id") val childPolicyId: String,
    @ColumnInfo(name = "child_revision") val childRevision: Long,
    @ColumnInfo(name = "child_artifact_sha256") val childArtifactSha256: String,
    @ColumnInfo(name = "relation_type") val relationType: String,
    val active: Boolean,
    @ColumnInfo(name = "state_version") val stateVersion: Long,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
) {
    init {
        require(candidateId.isSafeCuratorId())
        require(applyPlanId.isSafeCuratorPlanId())
        require(parentPolicyId.isSafeCuratorId() && childPolicyId.isSafeCuratorId())
        require(parentPolicyId != childPolicyId)
        require(parentRevision > 0L && childRevision > 0L)
        require(parentArtifactSha256.isCuratorSha256() && childArtifactSha256.isCuratorSha256())
        require(CuratorLineageRelation.entries.any { it.name == relationType })
        require(stateVersion > 0L)
        require(active == (stateVersion == 1L))
        require(createdAtMs >= 0L && updatedAtMs >= createdAtMs)
    }

    override fun toString(): String =
        "CuratorDeltaLineageEntity(relation=$relationType, active=$active, " +
            "stateVersion=$stateVersion, ids=<redacted>)"
}

fun CuratorApplyPlan.toLineageEntities(createdAtMs: Long): List<CuratorDeltaLineageEntity> {
    val beforeById = mutations.mapNotNull { it.before }.associateBy { it.policyId }
    val afterById = mutations.mapNotNull { it.after }.associateBy { it.policyId }
    return lineage.map { edge ->
        val parent = requireNotNull(beforeById[edge.parentPolicyId])
        val child = requireNotNull(afterById[edge.childPolicyId])
        CuratorDeltaLineageEntity(
            candidateId = candidateId,
            applyPlanId = planId,
            parentPolicyId = parent.policyId,
            parentRevision = parent.revision,
            parentArtifactSha256 = parent.artifactSha256,
            childPolicyId = child.policyId,
            childRevision = child.revision,
            childArtifactSha256 = child.artifactSha256,
            relationType = edge.relation.name,
            active = true,
            stateVersion = 1L,
            createdAtMs = createdAtMs,
            updatedAtMs = createdAtMs,
        )
    }
}
