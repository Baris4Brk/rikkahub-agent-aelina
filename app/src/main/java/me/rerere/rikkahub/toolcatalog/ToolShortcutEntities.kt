package me.rerere.rikkahub.toolcatalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent state of a model-confirmed fast-lane entry.  A shortcut is metadata only: it never
 * stores a schema body, arguments, command text, paths, URLs, output, or credentials.
 */
enum class ToolShortcutState {
    ACTIVE,
    DISABLED,
    STALE_SCHEMA,
    STALE_AUTHORITY,
}

@Entity(
    tableName = "tool_shortcuts",
    indices = [
        Index(value = ["authority_subject_id", "state", "updated_at_ms"]),
        Index(value = ["authority_subject_id", "tool_name", "schema_fingerprint"], unique = true),
        Index(value = ["tool_name", "state"]),
    ],
)
data class ToolShortcutEntity(
    @PrimaryKey
    @ColumnInfo(name = "shortcut_id")
    val shortcutId: String,
    @ColumnInfo(name = "authority_subject_id")
    val authoritySubjectId: String,
    @ColumnInfo(name = "tool_name")
    val toolName: String,
    /** STATIC_CAPABILITY or INTERNAL. External MCP/plugin definitions are deliberately absent. */
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "category_path")
    val categoryPath: String,
    @ColumnInfo(name = "risk")
    val risk: String,
    @ColumnInfo(name = "schema_fingerprint")
    val schemaFingerprint: String,
    @ColumnInfo(name = "state")
    val state: String,
    @ColumnInfo(name = "state_version")
    val stateVersion: Long,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
    @ColumnInfo(name = "last_used_at_ms")
    val lastUsedAtMs: Long?,
    @ColumnInfo(name = "use_count")
    val useCount: Long,
    @ColumnInfo(name = "model_confirmed_at_ms")
    val modelConfirmedAtMs: Long,
)
