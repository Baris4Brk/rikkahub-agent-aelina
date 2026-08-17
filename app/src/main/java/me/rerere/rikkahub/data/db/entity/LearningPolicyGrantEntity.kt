package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Content-free AppDatabase authority head for one explicitly reviewed Learning policy.
 *
 * Policy text, evidence, prompts, and model output belong to LearningDatabase and must never be
 * copied here. [policyRevision] is the Learning policy's immutable `content_revision`, never its
 * independently advancing lifecycle state version. [sourceStreamId] keeps a restored grant inert
 * when it no longer names the installed main-database authority stream.
 */
@Entity(
    tableName = "learning_policy_grants",
    primaryKeys = ["grant_id"],
    indices = [
        Index(
            name = "idx_learning_policy_grants_stream_scope_policy",
            value = [
                "source_stream_id",
                "scope_kind",
                "scope_id",
                "consuming_assistant_id",
                "policy_id",
            ],
            unique = true,
        ),
        Index(
            name = "idx_learning_policy_grants_scope_state",
            value = [
                "source_stream_id",
                "scope_kind",
                "scope_id",
                "consuming_assistant_id",
                "state",
                "updated_at_ms",
            ],
        ),
        Index(
            name = "idx_learning_policy_grants_updated",
            value = ["updated_at_ms", "grant_id"],
        ),
    ],
)
data class LearningPolicyGrantEntity(
    @ColumnInfo(name = "grant_id") val grantId: String,
    @ColumnInfo(name = "source_stream_id") val sourceStreamId: String,
    @ColumnInfo(name = "policy_id") val policyId: String,
    @ColumnInfo(name = "policy_revision") val policyRevision: Long,
    @ColumnInfo(name = "artifact_sha256") val artifactSha256: String,
    @ColumnInfo(name = "scope_kind") val scopeKind: String,
    @ColumnInfo(name = "scope_id") val scopeId: String,
    /** Exact Assistant consuming this advice; mandatory even for AUTHORITY_SUBJECT scope. */
    @ColumnInfo(name = "consuming_assistant_id") val consumingAssistantId: String,
    @ColumnInfo(name = "actor") val actor: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "state_version") val stateVersion: Long,
    @ColumnInfo(name = "granted_at_ms") val grantedAtMs: Long,
    @ColumnInfo(name = "revoked_at_ms") val revokedAtMs: Long?,
    @ColumnInfo(name = "reason_code") val reasonCode: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
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
    }

    override fun toString(): String =
        "LearningPolicyGrantEntity(scope=$scopeKind, state=$state, " +
            "version=$stateVersion, ids=<redacted>)"
}

internal fun validateLearningPolicyGrantSnapshot(
    grantId: String,
    sourceStreamId: String,
    policyId: String,
    policyRevision: Long,
    artifactSha256: String,
    scopeKind: String,
    scopeId: String,
    consumingAssistantId: String,
    actor: String,
    state: String,
    stateVersion: Long,
    grantedAtMs: Long,
    revokedAtMs: Long?,
    reasonCode: String,
    createdAtMs: Long,
    updatedAtMs: Long,
) {
    require(
        listOf(grantId, sourceStreamId, policyId, scopeId, consumingAssistantId)
            .all { it.isPolicyGrantId() },
    ) {
        "Invalid policy grant reference"
    }
    require(policyRevision > 0L) { "Invalid policy revision" }
    require(artifactSha256.isLowercaseSha256()) { "Invalid policy artifact digest" }
    require(scopeKind == "ASSISTANT" || scopeKind == "AUTHORITY_SUBJECT") {
        "Policy grants cannot use a global or unknown scope"
    }
    require(runCatching { kotlin.uuid.Uuid.parse(consumingAssistantId).toString() == consumingAssistantId }
        .getOrDefault(false)) { "Invalid consuming Assistant ID" }
    if (scopeKind == "ASSISTANT") {
        require(scopeId == consumingAssistantId) {
            "Assistant-scoped grant must be consumed by that exact Assistant"
        }
    }
    require(actor == "USER_REVIEW" || actor == "AUTHORITY_REVOCATION") {
        "Policy grant actor is not authoritative"
    }
    require(state == "GRANTED" || state == "REVOKED") { "Invalid policy grant state" }
    require(stateVersion > 0L) { "Invalid policy grant state version" }
    require(stateVersion != 1L || state == "GRANTED") { "A policy grant cannot begin revoked" }
    require(grantedAtMs >= 0L && createdAtMs >= 0L && updatedAtMs >= createdAtMs) {
        "Invalid policy grant time"
    }
    require(
        (state == "GRANTED" && revokedAtMs == null) ||
            (state == "REVOKED" && revokedAtMs != null &&
                revokedAtMs >= grantedAtMs && updatedAtMs >= revokedAtMs),
    ) { "Policy grant state/time mismatch" }
    require(
        reasonCode.length in 1..64 &&
            reasonCode.all { it in 'A'..'Z' || it in '0'..'9' || it == '_' },
    ) {
        "Policy grant reason must be a content-free code"
    }
    if (actor == "AUTHORITY_REVOCATION") {
        require(
            scopeKind == "AUTHORITY_SUBJECT" && state == "REVOKED" &&
                reasonCode == "SECOND_USER_AUTHORITY_REVOKED",
        ) { "Authority revocation receipt is not exact" }
    } else {
        require(reasonCode != "SECOND_USER_AUTHORITY_REVOKED") {
            "User review cannot forge an authority revocation"
        }
    }
}

internal fun String.isPolicyGrantId(): Boolean =
    length in 1..256 && all { char ->
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
            char == '-' || char == '_' || char == '.' ||
            char == ':' || char == '@'
    }

internal fun String.isLowercaseSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
