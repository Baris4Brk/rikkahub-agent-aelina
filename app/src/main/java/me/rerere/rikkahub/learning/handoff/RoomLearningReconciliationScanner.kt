package me.rerere.rikkahub.learning.handoff

import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.LearningOutboxDao
import me.rerere.rikkahub.data.db.dao.LearningReconciliationAuthorityDao
import me.rerere.rikkahub.data.db.projection.LearningCommandTerminalAuthorityProjection
import me.rerere.rikkahub.data.db.projection.LearningExecutionTerminalAuthorityProjection
import me.rerere.rikkahub.data.execution.ExecutionRetentionManager
import me.rerere.rikkahub.data.execution.ExecutionStatus
import me.rerere.rikkahub.data.execution.toLearningTerminalCode
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import kotlin.math.min
import kotlin.uuid.Uuid

private const val MAX_RECONCILIATION_PAGE_ROWS = 64

/**
 * Repairs only terminal Learning events that remain provable from content-free authority rows.
 *
 * The scanner does not read command payloads, tool arguments, outputs, errors, or conversation
 * messages. Every bounded page is read and repaired in one primary-database transaction after
 * re-validating the fixed stream and bootstrap head. Rows predating frozen scope/lineage metadata
 * are skipped; guessing their authority would be privilege escalation.
 */
class RoomLearningReconciliationScanner(
    private val database: AppDatabase,
    private val authorityDao: LearningReconciliationAuthorityDao =
        database.learningReconciliationAuthorityDao(),
    private val outboxDao: LearningOutboxDao = database.learningOutboxDao(),
    private val supportedWindowMs: Long = ExecutionRetentionManager.RETENTION_AGE_MS,
) : LearningReconciliationScanner {
    init {
        require(supportedWindowMs > 0L) { "Invalid reconciliation window" }
    }

    override suspend fun scanAndRepairProvableTerminalEvents(
        stream: LearningOutboxDescriptor,
        frozenNowMs: Long,
        limits: LearningBootstrapScanLimits,
    ): LearningBootstrapCoverage {
        require(frozenNowMs >= 0L) { "Negative reconciliation clock" }
        require(limits.maxRowsPerPage <= MAX_RECONCILIATION_PAGE_ROWS) {
            "Unsafe reconciliation page size"
        }
        val windowStartMs = if (frozenNowMs < supportedWindowMs) {
            0L
        } else {
            frozenNowMs - supportedWindowMs
        }
        val budget = ReconciliationPageBudget(limits.maxPages)
        val commandFloor = scanCommands(
            stream = stream,
            windowStartMs = windowStartMs,
            windowEndMs = frozenNowMs,
            pageSize = limits.maxRowsPerPage,
            budget = budget,
        )
        val executionFloor = scanExecutions(
            stream = stream,
            windowStartMs = windowStartMs,
            windowEndMs = frozenNowMs,
            pageSize = limits.maxRowsPerPage,
            budget = budget,
        )
        return LearningBootstrapCoverage(
            coverageStartMs = listOfNotNull(commandFloor, executionFloor).minOrNull(),
            commandCoverageStartMs = commandFloor,
            executionCoverageStartMs = executionFloor,
        )
    }

    private suspend fun scanCommands(
        stream: LearningOutboxDescriptor,
        windowStartMs: Long,
        windowEndMs: Long,
        pageSize: Int,
        budget: ReconciliationPageBudget,
    ): Long? {
        var cursorTime: Long? = null
        var cursorId: String? = null
        var coverageFloor: Long? = null
        while (true) {
            budget.claimPage()
            val page = database.withTransaction {
                requireFixedOutboxLineage(stream)
                val rows = authorityDao.listTerminalCommandsAfter(
                    windowStartMs = windowStartMs,
                    windowEndMs = windowEndMs,
                    afterFinishedAtMs = cursorTime,
                    afterId = cursorId,
                    limit = pageSize,
                )
                var pageFloor: Long? = null
                rows.forEach { row ->
                    projectCommandTerminalDraft(row, stream.streamId)?.let { draft ->
                        appendValidatedBusinessDraft(outboxDao, stream.streamId) { draft }
                        pageFloor = minimumTime(pageFloor, draft.source?.occurredAtMs)
                    }
                }
                AuthorityPageResult.fromCommands(rows, pageFloor)
            }
            coverageFloor = minimumTime(coverageFloor, page.provenCoverageFloorMs)
            if (page.rowCount < pageSize) return coverageFloor
            if (!budget.hasRemaining) budget.exhausted()
            cursorTime = checkNotNull(page.lastFinishedAtMs)
            cursorId = checkNotNull(page.lastId)
        }
    }

    private suspend fun scanExecutions(
        stream: LearningOutboxDescriptor,
        windowStartMs: Long,
        windowEndMs: Long,
        pageSize: Int,
        budget: ReconciliationPageBudget,
    ): Long? {
        var cursorTime: Long? = null
        var cursorId: String? = null
        var coverageFloor: Long? = null
        while (true) {
            budget.claimPage()
            val page = database.withTransaction {
                requireFixedOutboxLineage(stream)
                val rows = authorityDao.listTerminalExecutionsAfter(
                    windowStartMs = windowStartMs,
                    windowEndMs = windowEndMs,
                    afterFinishedAtMs = cursorTime,
                    afterId = cursorId,
                    limit = pageSize,
                )
                var pageFloor: Long? = null
                rows.forEach { row ->
                    projectExecutionTerminalDraft(row, stream.streamId)?.let { draft ->
                        appendValidatedBusinessDraft(outboxDao, stream.streamId) { draft }
                        pageFloor = minimumTime(pageFloor, draft.source?.occurredAtMs)
                    }
                }
                AuthorityPageResult.fromExecutions(rows, pageFloor)
            }
            coverageFloor = minimumTime(coverageFloor, page.provenCoverageFloorMs)
            if (page.rowCount < pageSize) return coverageFloor
            if (!budget.hasRemaining) budget.exhausted()
            cursorTime = checkNotNull(page.lastFinishedAtMs)
            cursorId = checkNotNull(page.lastId)
        }
    }

    private suspend fun requireFixedOutboxLineage(fixed: LearningOutboxDescriptor) {
        val currentStream = readHealthyLearningOutboxStream(outboxDao)
        if (currentStream != fixed.streamId) {
            throw LearningBootstrapException(LearningBootstrapFailureCode.STREAM_CHANGED)
        }
        val currentHead = outboxDao.headSequence(currentStream.toString())
            ?: throw LearningBootstrapException(LearningBootstrapFailureCode.HEAD_REWIND)
        if (currentHead < fixed.headSequence) {
            throw LearningBootstrapException(LearningBootstrapFailureCode.HEAD_REWIND)
        }
    }
}

/** Same canonical projection as [LearningCommandAuthorityEventPort]'s direct terminal writer. */
internal fun projectCommandTerminalDraft(
    row: LearningCommandTerminalAuthorityProjection,
    streamId: Uuid,
): LearningOutboxDraft? {
    val terminalState = row.state.takeIf {
        it in setOf("COMPLETED", "FAILED", "CANCELLED", "MANUAL_CONFIRMATION")
    } ?: return null
    if (row.stateVersion <= 0L) return null
    val finishedAtMs = row.finishedAtMs?.takeIf { it >= 0L } ?: return null
    val commandId = row.commandId.parseNonNilUuidOrNull() ?: return null
    val conversationId = row.conversationId.parseNonNilUuidOrNull() ?: return null
    val assistantId = row.assistantIdSnapshot?.parseNonNilUuidOrNull() ?: return null
    val lineageId = row.lineageId?.parseNonNilUuidOrNull() ?: return null
    val parentCommandId = row.parentCommandId?.let { it.parseNonNilUuidOrNull() ?: return null }
    val branchAnchorMessageId =
        row.branchAnchorMessageId?.parseNonNilUuidOrNull() ?: return null
    // A P1 admission may already have frozen anchor/conversation revisions while an older
    // terminal path still leaves completion null. Reconstruct that transitional row as v1;
    // never manufacture a v2 completion from admission-only evidence.
    val hasP1Boundary = row.completionKind != null ||
        row.resultAssistantMessageId != null || row.resultAssistantMessageRevision != null
    val eventSchemaVersion = if (hasP1Boundary) {
        if (row.branchAnchorMessageRevision?.let { it > 0L } != true) return null
        if (row.conversationSourceRevision?.let { it > 0L } != true) return null
        if (row.completionKind == null) return null
        if ((row.resultAssistantMessageId == null) != (row.resultAssistantMessageRevision == null)) {
            return null
        }
        if (row.resultAssistantMessageRevision?.let { it > 0L } == false) return null
        2
    } else {
        1
    }
    val scope = if (row.authoritySubjectId != null) {
        try {
            LearningScope.AuthoritySubject(row.authoritySubjectId)
        } catch (_: IllegalArgumentException) {
            return null
        }
    } else {
        LearningScope.Assistant(assistantId)
    }
    return try {
        LearningOutboxDraft(
            streamId = streamId,
            eventCode = LearningEventCode(
                LearningEventType.COMMAND_TERMINAL.name,
                eventSchemaVersion,
            ),
            source = LearningSourceRef(
                sourceKind = LearningSourceKind.COMMAND,
                sourceId = commandId.toString(),
                sourceRevision = row.stateVersion,
                missingRevisionReason = null,
                databaseStreamId = streamId,
                scope = scope,
                occurredAtMs = finishedAtMs,
            ),
            correlation = LearningCorrelation(
                conversationId = conversationId.toString(),
                conversationSourceRevision = row.conversationSourceRevision,
                commandId = commandId.toString(),
                lineageId = lineageId.toString(),
                parentCommandId = parentCommandId?.toString(),
                branchAnchorMessageId = branchAnchorMessageId.toString(),
                branchAnchorMessageRevision = row.branchAnchorMessageRevision,
                completionKindCode = row.completionKind,
                messageId = row.resultAssistantMessageId,
                messageRevision = row.resultAssistantMessageRevision,
            ),
            terminalStateCode = terminalState,
            createdAtMs = finishedAtMs,
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Same canonical projection as ExecutionStateTransaction's direct terminal writer. */
internal fun projectExecutionTerminalDraft(
    row: LearningExecutionTerminalAuthorityProjection,
    streamId: Uuid,
): LearningOutboxDraft? {
    val nextStatus = row.status.strictExecutionStatusOrNull()?.takeIf { it.isTerminal }
        ?: return null
    val previousStatus = row.eventPreviousStatus?.strictExecutionStatusOrNull()
        ?.takeUnless { it.isTerminal }
        ?: return null
    if (
        previousStatus == nextStatus ||
        row.executionId != row.eventExecutionId ||
        row.stateVersion <= 0L ||
        row.stateVersion != row.eventSequence ||
        row.status != row.eventNextStatus ||
        row.verificationState != row.eventNextVerification
    ) {
        return null
    }
    val finishedAtMs = row.finishedAtMs?.takeIf { it >= 0L } ?: return null
    if (row.updatedAtMs != finishedAtMs || row.eventCreatedAtMs != finishedAtMs) return null
    val scopeKind = row.learningScopeKind ?: return null
    val scopeId = row.learningScopeId ?: return null
    val scope = LearningScope.parseOrNull(scopeKind, scopeId) ?: return null
    val sourceId = try {
        LearningCanonicalId.executionEventSourceId(row.eventId)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val toolIdentity = listOf(row.toolCallId, row.toolName, row.toolSchemaFingerprint)
    val hasP1ToolIdentity = toolIdentity.any { it != null }
    val executionSchemaVersion = if (hasP1ToolIdentity) {
        // P1 executions may become terminal before their assistant checkpoint is durable. Never
        // promote that interim projection to schema-v2: final/WAITING authority binds the exact
        // message pair and the next deterministic reconciliation pass can then emit complete v2.
        if (toolIdentity.any { it == null } ||
            row.owningAssistantMessageId == null ||
            row.owningAssistantMessageRevision?.let { it > 0L } != true
        ) {
            return null
        }
        2
    } else {
        1
    }
    return try {
        LearningOutboxDraft(
            streamId = streamId,
            eventCode = LearningEventCode(
                LearningEventType.EXECUTION_TERMINAL.name,
                executionSchemaVersion,
            ),
            source = LearningSourceRef(
                sourceKind = LearningSourceKind.EXECUTION_EVENT,
                sourceId = sourceId,
                sourceRevision = row.eventSequence,
                missingRevisionReason = null,
                databaseStreamId = streamId,
                scope = scope,
                occurredAtMs = row.eventCreatedAtMs,
            ),
            correlation = LearningCorrelation(
                conversationId = row.conversationId,
                commandId = row.commandId,
                generationRunId = row.traceId,
                executionId = row.executionId,
                toolCallId = row.toolCallId,
                toolName = row.toolName,
                toolSchemaFingerprint = row.toolSchemaFingerprint,
                messageId = row.owningAssistantMessageId,
                messageRevision = row.owningAssistantMessageRevision,
            ),
            terminalStateCode = nextStatus.toLearningTerminalCode(),
            createdAtMs = row.eventCreatedAtMs,
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

private data class AuthorityPageResult(
    val rowCount: Int,
    val lastFinishedAtMs: Long?,
    val lastId: String?,
    val provenCoverageFloorMs: Long?,
) {
    companion object {
        fun fromCommands(
            rows: List<LearningCommandTerminalAuthorityProjection>,
            floorMs: Long?,
        ): AuthorityPageResult = AuthorityPageResult(
            rowCount = rows.size,
            lastFinishedAtMs = rows.lastOrNull()?.finishedAtMs,
            lastId = rows.lastOrNull()?.commandId,
            provenCoverageFloorMs = floorMs,
        )

        fun fromExecutions(
            rows: List<LearningExecutionTerminalAuthorityProjection>,
            floorMs: Long?,
        ): AuthorityPageResult = AuthorityPageResult(
            rowCount = rows.size,
            lastFinishedAtMs = rows.lastOrNull()?.finishedAtMs,
            lastId = rows.lastOrNull()?.executionId,
            provenCoverageFloorMs = floorMs,
        )
    }
}

private class ReconciliationPageBudget(private val maximum: Int) {
    private var claimed = 0
    val hasRemaining: Boolean get() = claimed < maximum

    fun claimPage() {
        if (!hasRemaining) exhausted()
        claimed += 1
    }

    fun exhausted(): Nothing = throw LearningBootstrapException(
        LearningBootstrapFailureCode.REPLAY_BUDGET_EXHAUSTED,
    )
}

private fun minimumTime(first: Long?, second: Long?): Long? = when {
    first == null -> second
    second == null -> first
    else -> min(first, second)
}

private fun String.strictExecutionStatusOrNull(): ExecutionStatus? =
    ExecutionStatus.entries.firstOrNull { it.name == this }

private fun String.parseNonNilUuidOrNull(): Uuid? {
    val parsed = try {
        Uuid.parse(this)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return parsed.takeUnless {
        it.toString() == "00000000-0000-0000-0000-000000000000"
    }
}
