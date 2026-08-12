package me.rerere.rikkahub.learning.handoff

import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.learning.model.DisabledLearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import me.rerere.rikkahub.service.chat.CommandAuthorityEvent
import me.rerere.rikkahub.service.chat.CommandAuthorityEventKind
import me.rerere.rikkahub.service.chat.CommandAuthorityEventPort
import me.rerere.rikkahub.service.chat.CommandTransactionRunner
import me.rerere.rikkahub.service.chat.DurableCommandState

/** Main-database transaction adapter; it never opens or calls the derived Learning database. */
class RoomCommandTransactionRunner(
    private val database: AppDatabase,
) : CommandTransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction { block() }
}

/** Projects a command authority mutation into the narrow outbox while the owner transaction holds. */
class LearningCommandAuthorityEventPort(
    private val appender: LearningOutboxAppender,
    private val featureFlags: LearningFeatureFlagSource = DisabledLearningFeatureFlagSource,
) : CommandAuthorityEventPort {
    override suspend fun appendInCurrentTransaction(event: CommandAuthorityEvent): Boolean {
        val isCombinedV2 = event.conversationSourceRevision != null &&
            event.lineage.branchAnchorMessageRevision != null &&
            (event.kind == CommandAuthorityEventKind.ADMITTED || event.completion != null)
        // FAILED_FINAL_SAVE is durably typed on the command row but has no committed graph/source
        // revision. It cannot form a Learning authority event and reconciliation also skips it.
        if (event.completion != null && event.conversationSourceRevision == null) return false
        // A WAITING event is safe only when the combined graph/approval/command transaction
        // supplied every v2 authority field. Legacy WAITING remains fail closed.
        if (event.kind == CommandAuthorityEventKind.WAITING_APPROVAL && !isCombinedV2) return false
        val flags = featureFlags.current()
        if (!flags.isValid || !flags.effective.handoff) return false
        val result = appender.appendInCurrentAuthorityTransaction { streamId ->
            val eventType = event.kind.toLearningEventType()
            LearningOutboxDraft(
                streamId = streamId,
                eventCode = LearningEventCode(
                    eventType.name,
                    schemaVersion = if (isCombinedV2) 2 else 1,
                ),
                source = LearningSourceRef(
                    sourceKind = LearningSourceKind.COMMAND,
                    sourceId = event.commandId.toString(),
                    sourceRevision = event.stateVersion,
                    missingRevisionReason = null,
                    databaseStreamId = streamId,
                    scope = event.authoritySubjectId?.let { LearningScope.AuthoritySubject(it) }
                        ?: LearningScope.Assistant(event.lineage.assistantIdSnapshot),
                    occurredAtMs = event.occurredAtMs,
                ),
                correlation = LearningCorrelation(
                    conversationId = event.conversationId.toString(),
                    commandId = event.commandId.toString(),
                    lineageId = event.lineage.lineageId.toString(),
                    parentCommandId = event.lineage.parentCommandId?.toString(),
                    branchAnchorMessageId = event.lineage.branchAnchorMessageId.toString(),
                    branchAnchorMessageRevision = event.lineage.branchAnchorMessageRevision,
                    conversationSourceRevision = event.conversationSourceRevision,
                    completionKindCode = event.completion?.kind?.name,
                    messageId = event.completion?.resultMessage?.messageId,
                    messageRevision = event.completion?.resultMessage?.messageRevision,
                ),
                terminalStateCode = event.state.toLearningTerminalCodeOrNull(event.kind),
                createdAtMs = event.occurredAtMs,
            )
        }
        return result is LearningOutboxAppendResult.Inserted
    }
}

private fun CommandAuthorityEventKind.toLearningEventType(): LearningEventType = when (this) {
    CommandAuthorityEventKind.ADMITTED -> LearningEventType.COMMAND_ADMITTED
    CommandAuthorityEventKind.WAITING_APPROVAL -> LearningEventType.COMMAND_WAITING_APPROVAL
    CommandAuthorityEventKind.TERMINAL -> LearningEventType.COMMAND_TERMINAL
}

private fun DurableCommandState.toLearningTerminalCodeOrNull(
    eventKind: CommandAuthorityEventKind,
): String? {
    if (eventKind != CommandAuthorityEventKind.TERMINAL) return null
    return when (this) {
        DurableCommandState.COMPLETED -> "COMPLETED"
        DurableCommandState.FAILED -> "FAILED"
        DurableCommandState.CANCELLED -> "CANCELLED"
        DurableCommandState.MANUAL_CONFIRMATION -> "MANUAL_CONFIRMATION"
        else -> error("command_event_is_not_terminal")
    }
}
