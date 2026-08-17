package me.rerere.rikkahub.workflow.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 12 — workflow row. The full definition is stored as canonical JSON in
 * [definitionJson]; the projected columns ([name], [enabled], etc.) exist only so the
 * Settings UI can sort / filter without parsing every row. The JSON is the source of
 * truth — projected columns must be re-derived on every write.
 *
 * Triggered triggers (broadcast receivers, geofence client, etc.) read [enabled] off this
 * row — the auth/disable cycle does NOT round-trip through the JSON because that would
 * spam pointless rewrites.
 */
@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    /** Canonical JSON. Source of truth. ≤ ~16KB in practice. */
    val definitionJson: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastRunAtMs: Long? = null,
    val lastRunStatus: String? = null,        // SUCCESS / FAILED / SKIPPED_*
    val lastRunError: String? = null,         // ≤500 chars truncated at write site
    @ColumnInfo(defaultValue = "0")
    val runsTodayCount: Int = 0,
    /** ISO local-date "yyyy-MM-dd" for daily-cap rollover. Empty string = never run. */
    @ColumnInfo(defaultValue = "''")
    val runsTodayDate: String = "",
    /** Optimistic-lock version for definition/enabled/provenance mutations. */
    @ColumnInfo(defaultValue = "1")
    val stateVersion: Long = 1L,
    /** USER or LEARNED. Never infer this authority boundary from the id prefix. */
    @ColumnInfo(defaultValue = "'USER'")
    val origin: String = "USER",
    val sourceCandidateId: String? = null,
    val sourceArtifactHash: String? = null,
    val grantDigest: String? = null,
    val authoringAssistantId: String? = null,
    /** Canonical sorted JSON projections checked against definitionJson on every load. */
    @ColumnInfo(defaultValue = "'[]'")
    val capabilitySnapshotJson: String = "[]",
    @ColumnInfo(defaultValue = "'[]'")
    val toolSchemaFingerprintsJson: String = "[]",
    /** Stable fail-closed reason set when a learned workflow becomes stale. */
    val staleReason: String? = null,
)

/**
 * One row per workflow fire. Capped at 100 rows per workflow via
 * [WorkflowRunDao.trim] called from the engine post-fire.
 */
@Entity(
    tableName = "workflow_runs",
    indices = [Index(value = ["workflowId", "firedAtMs"])],
)
data class WorkflowRunEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val workflowId: String,
    val firedAtMs: Long,
    val status: String,                       // SUCCESS / FAILED / SKIPPED_*
    val durationMs: Long,
    val errorMessage: String? = null,
)
