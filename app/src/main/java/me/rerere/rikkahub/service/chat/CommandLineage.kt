package me.rerere.rikkahub.service.chat

import me.rerere.rikkahub.data.db.entity.PendingChatCommandEntity
import kotlin.uuid.Uuid

/** Authority identity persisted beside a command, never inside its model-facing payload. */
data class CommandLineageContext(
    val assistantIdSnapshot: Uuid,
    val lineageId: Uuid,
    val parentCommandId: Uuid?,
    val branchAnchorMessageId: Uuid,
    val branchAnchorMessageRevision: Long? = null,
) {
    override fun toString(): String =
        "CommandLineageContext(parent=${parentCommandId != null}, ids=<redacted>)"

    companion object {
        /** Legacy or partially populated rows are deliberately not promoted into a lineage. */
        fun fromAuthorityRowOrNull(row: PendingChatCommandEntity): CommandLineageContext? {
            val assistant = row.assistantIdSnapshot?.parseUuidOrNull() ?: return null
            val lineage = row.lineageId?.parseUuidOrNull() ?: return null
            val parent = row.parentCommandId?.let { it.parseUuidOrNull() ?: return null }
            val branchAnchor = row.branchAnchorMessageId?.parseUuidOrNull() ?: return null
            if (row.schemaVersion >= 2 && row.branchAnchorMessageRevision == null) return null
            return CommandLineageContext(
                assistantIdSnapshot = assistant,
                lineageId = lineage,
                parentCommandId = parent,
                branchAnchorMessageId = branchAnchor,
                branchAnchorMessageRevision = row.branchAnchorMessageRevision,
            )
        }
    }
}

private fun String.parseUuidOrNull(): Uuid? = runCatching { Uuid.parse(this) }
    .getOrNull()
    ?.takeUnless { it.toString() == NIL_UUID }

internal const val NIL_UUID: String = "00000000-0000-0000-0000-000000000000"
