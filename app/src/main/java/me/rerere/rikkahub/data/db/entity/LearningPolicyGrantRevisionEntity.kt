package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Append-only, content-free snapshot journal for deterministic grant audit and restore replay. */
@Entity(
    tableName = "learning_policy_grant_revisions",
    primaryKeys = ["grant_id", "state_version"],
    foreignKeys = [
        ForeignKey(
            entity = LearningPolicyGrantEntity::class,
            parentColumns = ["grant_id"],
            childColumns = ["grant_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            name = "idx_learning_policy_grant_revisions_policy",
            value = ["source_stream_id", "policy_id", "policy_revision"],
        ),
        Index(
            name = "idx_learning_policy_grant_revisions_scope",
            value = [
                "source_stream_id",
                "scope_kind",
                "scope_id",
                "consuming_assistant_id",
                "state",
            ],
        ),
        Index(
            name = "idx_learning_policy_grant_revisions_changed",
            value = ["changed_at_ms", "grant_id", "state_version"],
        ),
    ],
)
data class LearningPolicyGrantRevisionEntity(
    @ColumnInfo(name = "grant_id") val grantId: String,
    @ColumnInfo(name = "source_stream_id") val sourceStreamId: String,
    @ColumnInfo(name = "policy_id") val policyId: String,
    @ColumnInfo(name = "policy_revision") val policyRevision: Long,
    @ColumnInfo(name = "artifact_sha256") val artifactSha256: String,
    @ColumnInfo(name = "scope_kind") val scopeKind: String,
    @ColumnInfo(name = "scope_id") val scopeId: String,
    @ColumnInfo(name = "consuming_assistant_id") val consumingAssistantId: String,
    @ColumnInfo(name = "actor") val actor: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "state_version") val stateVersion: Long,
    @ColumnInfo(name = "granted_at_ms") val grantedAtMs: Long,
    @ColumnInfo(name = "revoked_at_ms") val revokedAtMs: Long?,
    @ColumnInfo(name = "reason_code") val reasonCode: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    @ColumnInfo(name = "previous_state_version") val previousStateVersion: Long?,
    @ColumnInfo(name = "changed_at_ms") val changedAtMs: Long,
) {
    init {
        validateLearningPolicyGrantSnapshot(
            grantId = grantId,
            sourceStreamId = sourceStreamId,
            policyId = policyId,
            policyRevision = policyRevision,
            artifactSha256 = artifactSha256,
            scopeKind = scopeKind,
            scopeId = scopeId,
            consumingAssistantId = consumingAssistantId,
            actor = actor,
            state = state,
            stateVersion = stateVersion,
            grantedAtMs = grantedAtMs,
            revokedAtMs = revokedAtMs,
            reasonCode = reasonCode,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs,
        )
        require(
            (stateVersion == 1L && previousStateVersion == null) ||
                (stateVersion > 1L && previousStateVersion == stateVersion - 1L),
        ) { "Policy grant revisions must be contiguous" }
        require(changedAtMs == updatedAtMs) { "Grant revision change time must match its snapshot" }
    }

    override fun toString(): String =
        "LearningPolicyGrantRevisionEntity(scope=$scopeKind, state=$state, " +
            "version=$stateVersion, ids=<redacted>)"
}

internal fun LearningPolicyGrantEntity.toRevisionEntity(): LearningPolicyGrantRevisionEntity =
    LearningPolicyGrantRevisionEntity(
        grantId = grantId,
        sourceStreamId = sourceStreamId,
        policyId = policyId,
        policyRevision = policyRevision,
        artifactSha256 = artifactSha256,
        scopeKind = scopeKind,
        scopeId = scopeId,
        consumingAssistantId = consumingAssistantId,
        actor = actor,
        state = state,
        stateVersion = stateVersion,
        grantedAtMs = grantedAtMs,
        revokedAtMs = revokedAtMs,
        reasonCode = reasonCode,
        createdAtMs = createdAtMs,
        updatedAtMs = updatedAtMs,
        previousStateVersion = stateVersion.takeIf { it > 1L }?.minus(1L),
        changedAtMs = updatedAtMs,
    )
