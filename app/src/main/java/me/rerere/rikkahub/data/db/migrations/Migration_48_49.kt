package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.workflow.model.WorkflowCapabilitySnapshot
import me.rerere.rikkahub.workflow.model.WorkflowJson
import me.rerere.rikkahub.workflow.model.WorkflowOrigin

/**
 * Durable P4 workflow authority/provenance columns.  The old v48 table deliberately has no
 * learned-workflow authority: every migrated row is a USER row and receives no synthetic grant.
 */
internal val WORKFLOW_V49_COLUMNS = listOf(
    "stateVersion" to "INTEGER NOT NULL DEFAULT 1",
    "origin" to "TEXT NOT NULL DEFAULT 'USER'",
    "sourceCandidateId" to "TEXT",
    "sourceArtifactHash" to "TEXT",
    "grantDigest" to "TEXT",
    "authoringAssistantId" to "TEXT",
    "capabilitySnapshotJson" to "TEXT NOT NULL DEFAULT '[]'",
    "toolSchemaFingerprintsJson" to "TEXT NOT NULL DEFAULT '[]'",
    "staleReason" to "TEXT",
)

/** Pure row transform shared by Room migration and the raw cold-restore adapter. */
internal data class WorkflowV49Backfill(
    val definitionJson: String,
    val authoringAssistantId: String?,
    val capabilitySnapshotJson: String,
)

internal fun workflowV49Backfill(
    definitionJson: String,
    projectedEnabled: Boolean? = null,
): WorkflowV49Backfill? {
    val stored = WorkflowJson.parseStoredWithCompatibility(definitionJson) ?: return null
    val legacyActions = stored.definition.actions.map { it.copy(toolSchemaFingerprint = null) }
    val capabilities = WorkflowCapabilitySnapshot.capture(legacyActions)
    val migrated = stored.definition.copy(
        enabled = projectedEnabled ?: stored.definition.enabled,
        actions = legacyActions,
        capabilitySnapshot = capabilities,
        origin = WorkflowOrigin.USER,
        sourceCandidateId = null,
        sourceArtifactHash = null,
        grantDigest = null,
    )
    return WorkflowV49Backfill(
        definitionJson = WorkflowJson.encode(migrated),
        authoringAssistantId = migrated.authoringAssistantId,
        capabilitySnapshotJson = JsonArray(
            capabilities.toSortedSet().map(::JsonPrimitive),
        ).toString(),
    )
}

internal fun backfillWorkflowV49Rows(db: SupportSQLiteDatabase) {
    val rows = db.query("SELECT `id`, `definitionJson`, `enabled` FROM `workflows`").use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow("id")
        val definitionIndex = cursor.getColumnIndexOrThrow("definitionJson")
        val enabledIndex = cursor.getColumnIndexOrThrow("enabled")
        buildList {
            while (cursor.moveToNext()) {
                workflowV49Backfill(
                    definitionJson = cursor.getString(definitionIndex),
                    projectedEnabled = cursor.getInt(enabledIndex) != 0,
                )?.let { backfill ->
                    add(cursor.getString(idIndex) to backfill)
                }
            }
        }
    }
    val statement = db.compileStatement(
        "UPDATE `workflows` SET `definitionJson` = ?, `origin` = 'USER', " +
            "`sourceCandidateId` = NULL, `sourceArtifactHash` = NULL, `grantDigest` = NULL, " +
            "`authoringAssistantId` = ?, `capabilitySnapshotJson` = ?, " +
            "`toolSchemaFingerprintsJson` = '[]', `staleReason` = NULL WHERE `id` = ?",
    )
    rows.forEach { (id, backfill) ->
        statement.clearBindings()
        statement.bindString(1, backfill.definitionJson)
        backfill.authoringAssistantId?.let { statement.bindString(2, it) }
            ?: statement.bindNull(2)
        statement.bindString(3, backfill.capabilitySnapshotJson)
        statement.bindString(4, id)
        check(statement.executeUpdateDelete() == 1) { "Workflow v49 backfill lost row" }
    }
}

val MIGRATION_48_49 = object : Migration(48, 49) {
    override fun migrate(db: SupportSQLiteDatabase) {
        WORKFLOW_V49_COLUMNS.forEach { (name, declaration) ->
            db.execSQL("ALTER TABLE `workflows` ADD COLUMN `$name` $declaration")
        }
        backfillWorkflowV49Rows(db)
    }
}
