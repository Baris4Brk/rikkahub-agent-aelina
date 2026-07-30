package me.rerere.rikkahub.toolcatalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class ToolExperienceState {
    ACTIVE,
    DISABLED,
    STALE_SCHEMA,
    STALE_AUTHORITY,
    SOFT_DELETED,
}

enum class ToolExperienceConfidence {
    /** The host completed an operation and its result did not contain a failure envelope. */
    OBSERVED,

    /** A standard success envelope or an independent runtime confirmation was present. */
    VERIFIED,
}

enum class ToolExperienceActor {
    SYSTEM,
    SECOND_USER,
    USER,
}

/**
 * A concise procedural memory, scoped to one exact second-user authority subject.
 *
 * Arguments and tool output are intentionally absent. The related immutable evidence lives in
 * [ToolExperienceEvidenceEntity], while user/agent prose edits are append-only revisions.
 */
@Entity(
    tableName = "tool_experiences",
    indices = [
        Index(value = ["authority_subject_id", "state", "updated_at_ms"]),
        Index(value = ["authority_subject_id", "primary_tool_name", "schema_fingerprint"], unique = true),
        Index(value = ["primary_tool_name", "state"]),
        Index(value = ["deleted_at_ms"]),
    ],
)
data class ToolExperienceEntity(
    @PrimaryKey
    @ColumnInfo(name = "experience_id")
    val experienceId: String,
    @ColumnInfo(name = "authority_subject_id")
    val authoritySubjectId: String,
    @ColumnInfo(name = "primary_tool_name")
    val primaryToolName: String,
    /** JSON array of up to three stable tool names; no arguments or values. */
    @ColumnInfo(name = "tool_names_json")
    val toolNamesJson: String,
    @ColumnInfo(name = "category_path")
    val categoryPath: String,
    @ColumnInfo(name = "schema_fingerprint")
    val schemaFingerprint: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "body")
    val body: String,
    @ColumnInfo(name = "tags_json")
    val tagsJson: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "confidence")
    val confidence: String,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "last_observed_at_ms")
    val lastObservedAtMs: Long,
    @ColumnInfo(name = "last_verified_at_ms")
    val lastVerifiedAtMs: Long?,
    @ColumnInfo(name = "deleted_at_ms")
    val deletedAtMs: Long? = null,
)

@Entity(
    tableName = "tool_experience_evidence",
    foreignKeys = [
        ForeignKey(
            entity = ToolExperienceEntity::class,
            parentColumns = ["experience_id"],
            childColumns = ["experience_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["experience_id", "created_at_ms"]),
        Index(value = ["execution_id"], unique = true),
    ],
)
data class ToolExperienceEvidenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "evidence_id")
    val evidenceId: String,
    @ColumnInfo(name = "experience_id")
    val experienceId: String,
    /** Opaque authoritative execution id; never a command, path, or output. */
    @ColumnInfo(name = "execution_id")
    val executionId: String,
    @ColumnInfo(name = "tool_name")
    val toolName: String,
    @ColumnInfo(name = "schema_fingerprint")
    val schemaFingerprint: String,
    /** HOST_COMPLETED, STANDARD_SUCCESS, or RUNTIME_CONFIRMED. */
    @ColumnInfo(name = "outcome_kind")
    val outcomeKind: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
)

@Entity(
    tableName = "tool_experience_revisions",
    foreignKeys = [
        ForeignKey(
            entity = ToolExperienceEntity::class,
            parentColumns = ["experience_id"],
            childColumns = ["experience_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["experience_id", "revision"], unique = true),
        Index(value = ["created_at_ms"]),
    ],
)
data class ToolExperienceRevisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "revision_id")
    val revisionId: String,
    @ColumnInfo(name = "experience_id")
    val experienceId: String,
    @ColumnInfo(name = "revision")
    val revision: Long,
    @ColumnInfo(name = "actor")
    val actor: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "body")
    val body: String,
    @ColumnInfo(name = "tags_json")
    val tagsJson: String,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
)
