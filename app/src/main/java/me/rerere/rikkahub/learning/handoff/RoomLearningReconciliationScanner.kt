package me.rerere.rikkahub.learning.handoff

import androidx.room.withTransaction
import me.rerere.rikkahub.data.authority.reward.RewardFeedbackAuthorityJournalSource
import me.rerere.rikkahub.data.authority.reward.RewardFeedbackJournalCursor
import me.rerere.rikkahub.data.authority.reward.RoomRewardFeedbackAuthorityJournalSource
import me.rerere.rikkahub.data.authority.source.ConversationSourceChangeKind
import me.rerere.rikkahub.data.authority.source.ConversationSourceScope
import me.rerere.rikkahub.data.authority.source.ConversationSourceScopeKind
import me.rerere.rikkahub.data.authority.source.ConversationSourceState
import me.rerere.rikkahub.data.authority.source.SourceAuthorityObjectKind
import me.rerere.rikkahub.data.authority.source.SourceInvalidationAuthorityEvent
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.LearningOutboxDao
import me.rerere.rikkahub.data.db.dao.LearningReconciliationAuthorityDao
import me.rerere.rikkahub.data.db.projection.LearningCommandTerminalAuthorityProjection
import me.rerere.rikkahub.data.db.projection.LearningConversationSourceAuthorityProjection
import me.rerere.rikkahub.data.db.projection.LearningExecutionTerminalAuthorityProjection
import me.rerere.rikkahub.data.db.projection.LearningMessageSourceAuthorityProjection
import me.rerere.rikkahub.data.execution.ExecutionRetentionManager
import me.rerere.rikkahub.data.execution.ExecutionStatus
import me.rerere.rikkahub.data.execution.toLearningTerminalCode
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.model.LearningScopeConsentSource
import me.rerere.rikkahub.learning.model.DisabledLearningScopeConsentSource
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef
import kotlin.math.min
import kotlin.uuid.Uuid

private const val MAX_RECONCILIATION_PAGE_ROWS = 64

/**
 * Repairs Learning events that remain provable from content-free authority rows and journals.
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
    private val rewardFeedbackJournal: RewardFeedbackAuthorityJournalSource =
        RoomRewardFeedbackAuthorityJournalSource(database.rewardFeedbackAuthorityDao()),
    private val scopeConsent: LearningScopeConsentSource = DisabledLearningScopeConsentSource,
    private val supportedWindowMs: Long = ExecutionRetentionManager.RETENTION_AGE_MS,
) : LearningReconciliationScanner {
    init {
        require(supportedWindowMs > 0L) { "Invalid reconciliation window" }
    }

    override suspend fun scanAndRepairProvableTerminalEvents(
        stream: LearningOutboxDescriptor,
        cursorAccess: LearningReconciliationCursorAccess,
        frozenNowMs: Long,
        limits: LearningBootstrapScanLimits,
    ): LearningBootstrapCoverage {
        require(frozenNowMs >= 0L) { "Negative reconciliation clock" }
        require(cursorAccess.streamId == stream.streamId.toString()) {
            "Reconciliation cursor access is bound to another stream"
        }
        require(cursorAccess.replayGeneration >= 0L) { "Negative reconciliation replay" }
        require(limits.maxRowsPerPage <= MAX_RECONCILIATION_PAGE_ROWS) {
            "Unsafe reconciliation page size"
        }
        var raw = cursorAccess.load()
        var cursor = LearningReconciliationCursorV1Codec.decode(raw)
        val requestedWindowStart = windowStart(frozenNowMs)
        val mustRestart = cursor == null || (
            cursor.state == LearningReconciliationCursorStateV1.COMPLETE &&
                (cursor.frozenHeadSequence != stream.headSequence ||
                    cursor.windowStartMs != requestedWindowStart ||
                    cursor.windowEndMs != frozenNowMs)
            )
        if (mustRestart) {
            val initialized = LearningReconciliationCursorV1.initialize(
                streamId = stream.streamId.toString(),
                frozenHeadSequence = stream.headSequence,
                windowStartMs = requestedWindowStart,
                windowEndMs = frozenNowMs,
            )
            val initializedRaw = LearningReconciliationCursorV1Codec.encode(initialized)
            if (!cursorAccess.compareAndSet(raw, initializedRaw)) {
                throw LearningCheckpointConflictException()
            }
            raw = initializedRaw
            cursor = initialized
        }
        var current = checkNotNull(cursor)
        validateContinuation(current, stream, frozenNowMs)
        var pages = 0
        while (current.state != LearningReconciliationCursorStateV1.COMPLETE) {
            if (pages >= limits.maxPages) throw LearningReconciliationWorkRemainsException()
            val fixed = stream.copy(headSequence = current.frozenHeadSequence)
            val page = scanOnePage(fixed, current, limits.maxRowsPerPage)
            val next = if (page.rowCount == 0) {
                if (current.phase == LearningReconciliationPhaseV1.FEEDBACK_REVISION) {
                    current.complete()
                } else {
                    current.nextPhase()
                }
            } else {
                val after = checkNotNull(page.after)
                current.advance(
                    after = after,
                    observedCoverageFloorMs = page.provenCoverageFloorMs?.coerceIn(
                        current.windowStartMs,
                        after.orderingTimeMs,
                    ),
                )
            }
            val nextRaw = LearningReconciliationCursorV1Codec.encode(next)
            if (!cursorAccess.compareAndSet(raw, nextRaw)) {
                throw LearningCheckpointConflictException()
            }
            raw = nextRaw
            current = next
            pages += 1
        }
        return current.toCoverage()
    }

    private fun windowStart(windowEndMs: Long): Long =
        if (windowEndMs < supportedWindowMs) 0L else windowEndMs - supportedWindowMs

    private fun validateContinuation(
        cursor: LearningReconciliationCursorV1,
        stream: LearningOutboxDescriptor,
        frozenNowMs: Long,
    ) {
        if (cursor.streamId != stream.streamId.toString()) {
            throw LearningBootstrapException(LearningBootstrapFailureCode.STREAM_CHANGED)
        }
        if (cursor.frozenHeadSequence > stream.headSequence) {
            throw LearningBootstrapException(LearningBootstrapFailureCode.HEAD_REWIND)
        }
        if (cursor.windowEndMs > frozenNowMs) {
            throw LearningBootstrapException(LearningBootstrapFailureCode.CLOCK_ROLLBACK)
        }
        if (cursor.windowStartMs != windowStart(cursor.windowEndMs)) {
            throw LearningBootstrapException(LearningBootstrapFailureCode.INVALID_CHECKPOINT)
        }
    }

    private suspend fun scanOnePage(
        stream: LearningOutboxDescriptor,
        cursor: LearningReconciliationCursorV1,
        pageSize: Int,
    ): DurableAuthorityPage = when (cursor.phase) {
        LearningReconciliationPhaseV1.COMMAND -> scanCommandPage(stream, cursor, pageSize)
        LearningReconciliationPhaseV1.EXECUTION -> scanExecutionPage(stream, cursor, pageSize)
        LearningReconciliationPhaseV1.CONVERSATION_SOURCE ->
            scanConversationSourcePage(stream, cursor, pageSize)
        LearningReconciliationPhaseV1.MESSAGE_SOURCE ->
            scanMessageSourcePage(stream, cursor, pageSize)
        LearningReconciliationPhaseV1.FEEDBACK_REVISION ->
            scanFeedbackPage(stream, cursor, pageSize)
    }

    private suspend fun scanCommandPage(
        stream: LearningOutboxDescriptor,
        cursor: LearningReconciliationCursorV1,
        pageSize: Int,
    ): DurableAuthorityPage = database.withTransaction {
        requireFixedOutboxLineage(stream)
        val after = cursor.command.after as? LearningReconciliationAfterKeyV1.Command
        val rows = authorityDao.listTerminalCommandsAfter(
            cursor.windowStartMs,
            cursor.windowEndMs,
            after?.finishedAtMs,
            after?.id,
            pageSize,
        )
        var floor: Long? = null
        rows.forEach { row ->
            projectCommandTerminalDraft(row, stream.streamId)?.let { draft ->
                if (!scopeConsent.captureAllowed(checkNotNull(draft.source).scope)) return@let
                appendValidatedBusinessDraft(outboxDao, stream.streamId) { draft }
                floor = minimumTime(floor, draft.source?.occurredAtMs)
            }
        }
        DurableAuthorityPage(
            rows.size,
            rows.lastOrNull()?.let {
                LearningReconciliationAfterKeyV1.Command(checkNotNull(it.finishedAtMs), it.commandId)
            },
            floor,
        )
    }

    private suspend fun scanExecutionPage(
        stream: LearningOutboxDescriptor,
        cursor: LearningReconciliationCursorV1,
        pageSize: Int,
    ): DurableAuthorityPage = database.withTransaction {
        requireFixedOutboxLineage(stream)
        val after = cursor.execution.after as? LearningReconciliationAfterKeyV1.Execution
        val rows = authorityDao.listTerminalExecutionsAfter(
            cursor.windowStartMs,
            cursor.windowEndMs,
            after?.finishedAtMs,
            after?.id,
            pageSize,
        )
        var floor: Long? = null
        rows.forEach { row ->
            projectExecutionTerminalDraft(row, stream.streamId)?.let { draft ->
                if (!scopeConsent.captureAllowed(checkNotNull(draft.source).scope)) return@let
                appendValidatedBusinessDraft(outboxDao, stream.streamId) { draft }
                floor = minimumTime(floor, draft.source?.occurredAtMs)
            }
        }
        DurableAuthorityPage(
            rows.size,
            rows.lastOrNull()?.let {
                LearningReconciliationAfterKeyV1.Execution(
                    checkNotNull(it.finishedAtMs),
                    it.executionId,
                )
            },
            floor,
        )
    }

    private suspend fun scanConversationSourcePage(
        stream: LearningOutboxDescriptor,
        cursor: LearningReconciliationCursorV1,
        pageSize: Int,
    ): DurableAuthorityPage = database.withTransaction {
        requireFixedOutboxLineage(stream)
        val after = cursor.conversationSource.after as?
            LearningReconciliationAfterKeyV1.ConversationSource
        val rows = authorityDao.listConversationSourceHeadsAfter(
            cursor.windowStartMs,
            cursor.windowEndMs,
            after?.updatedAtMs ?: -1L,
            after?.conversationId.orEmpty(),
            after?.scopeKind.orEmpty(),
            after?.scopeId.orEmpty(),
            pageSize,
        )
        var floor: Long? = null
        rows.forEach { row ->
            projectConversationSourceInvalidationDraft(row, stream.streamId)?.let { draft ->
                val source = checkNotNull(draft.source)
                val active = draft.correlation?.sourceStateCode == "ACTIVE"
                if (active && !scopeConsent.captureAllowed(source.scope)) return@let
                appendValidatedBusinessDraft(outboxDao, stream.streamId) { draft }
                floor = minimumTime(floor, draft.source?.occurredAtMs)
            }
        }
        DurableAuthorityPage(
            rows.size,
            rows.lastOrNull()?.let {
                LearningReconciliationAfterKeyV1.ConversationSource(
                    it.updatedAtMs,
                    it.conversationId,
                    it.scopeKind,
                    it.scopeId,
                )
            },
            floor,
        )
    }

    private suspend fun scanMessageSourcePage(
        stream: LearningOutboxDescriptor,
        cursor: LearningReconciliationCursorV1,
        pageSize: Int,
    ): DurableAuthorityPage = database.withTransaction {
        requireFixedOutboxLineage(stream)
        val after = cursor.messageSource.after as?
            LearningReconciliationAfterKeyV1.MessageSource
        val rows = authorityDao.listMessageSourceHeadsAfter(
            cursor.windowStartMs,
            cursor.windowEndMs,
            after?.updatedAtMs ?: -1L,
            after?.conversationId.orEmpty(),
            after?.messageId.orEmpty(),
            after?.scopeKind.orEmpty(),
            after?.scopeId.orEmpty(),
            pageSize,
        )
        var floor: Long? = null
        rows.forEach { row ->
            projectMessageSourceInvalidationDraft(row, stream.streamId)?.let { draft ->
                val source = checkNotNull(draft.source)
                val active = draft.correlation?.sourceStateCode == "ACTIVE"
                if (active && !scopeConsent.captureAllowed(source.scope)) return@let
                appendValidatedBusinessDraft(outboxDao, stream.streamId) { draft }
                floor = minimumTime(floor, draft.source?.occurredAtMs)
            }
        }
        DurableAuthorityPage(
            rows.size,
            rows.lastOrNull()?.let {
                LearningReconciliationAfterKeyV1.MessageSource(
                    it.updatedAtMs,
                    it.conversationId,
                    it.messageId,
                    it.scopeKind,
                    it.scopeId,
                )
            },
            floor,
        )
    }

    private suspend fun scanFeedbackPage(
        stream: LearningOutboxDescriptor,
        cursor: LearningReconciliationCursorV1,
        pageSize: Int,
    ): DurableAuthorityPage = database.withTransaction {
        requireFixedOutboxLineage(stream)
        val after = cursor.feedbackRevision.after as?
            LearningReconciliationAfterKeyV1.FeedbackRevision
        val page = rewardFeedbackJournal.listJournalPage(
            fromInclusiveMs = cursor.windowStartMs,
            toExclusiveMs = Math.addExact(cursor.windowEndMs, 1L),
            after = after?.let {
                RewardFeedbackJournalCursor(it.updatedAtMs, it.feedbackId, it.sourceRevision)
            } ?: RewardFeedbackJournalCursor(),
            limit = pageSize,
        )
        var floor: Long? = null
        page.events.forEach { event ->
            val scope = LearningScope.parseOrNull(event.scopeKind, event.scopeId)
            if (scope == null || !scopeConsent.captureAllowed(scope)) return@forEach
            appendValidatedBusinessDraft(outboxDao, stream.streamId) { streamId ->
                event.toLearningOutboxDraft(streamId)
            }
            floor = minimumTime(floor, event.occurredAtMs)
        }
        DurableAuthorityPage(
            page.events.size,
            page.nextCursor?.let {
                LearningReconciliationAfterKeyV1.FeedbackRevision(
                    it.updatedAtMs,
                    it.feedbackId,
                    it.sourceRevision,
                )
            },
            floor,
        )
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

private data class DurableAuthorityPage(
    val rowCount: Int,
    val after: LearningReconciliationAfterKeyV1?,
    val provenCoverageFloorMs: Long?,
)

private fun LearningReconciliationCursorV1.toCoverage(): LearningBootstrapCoverage {
    check(state == LearningReconciliationCursorStateV1.COMPLETE)
    val sourceFloor = minimumTime(
        conversationSource.coverageFloorMs,
        messageSource.coverageFloorMs,
    )
    val floors = listOfNotNull(
        command.coverageFloorMs,
        execution.coverageFloorMs,
        sourceFloor,
        feedbackRevision.coverageFloorMs,
    )
    return LearningBootstrapCoverage(
        coverageStartMs = floors.minOrNull(),
        commandCoverageStartMs = command.coverageFloorMs,
        executionCoverageStartMs = execution.coverageFloorMs,
        sourceAuthorityCoverageStartMs = sourceFloor,
        feedbackCoverageStartMs = feedbackRevision.coverageFloorMs,
    )
}

/** Same canonical schema-v2 projection as the direct Conversation source authority writer. */
internal fun projectConversationSourceInvalidationDraft(
    row: LearningConversationSourceAuthorityProjection,
    streamId: Uuid,
): LearningOutboxDraft? {
    if (row.sourceRevision <= 1L || row.previousSourceRevision != row.sourceRevision - 1L) {
        // A revision-one non-ACTIVE head has no historical revision to invalidate.
        return null
    }
    val scope = row.toConversationSourceScopeOrNull() ?: return null
    return try {
        SourceInvalidationAuthorityEvent(
            scope = scope,
            conversationId = row.conversationId,
            objectKind = SourceAuthorityObjectKind.CONVERSATION,
            sourceId = row.conversationId,
            sourceRevision = row.sourceRevision,
            previousSourceRevision = requireNotNull(row.previousSourceRevision),
            conversationSourceRevision = row.sourceRevision,
            sourceState = ConversationSourceState.valueOf(row.sourceState),
            changeKind = ConversationSourceChangeKind.valueOf(row.changeKind),
            occurredAtMs = row.occurredAtMs,
        ).toLearningOutboxDraft(streamId)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Same canonical schema-v2 projection as the direct message source authority writer. */
internal fun projectMessageSourceInvalidationDraft(
    row: LearningMessageSourceAuthorityProjection,
    streamId: Uuid,
): LearningOutboxDraft? {
    if (row.sourceRevision <= 1L || row.previousSourceRevision != row.sourceRevision - 1L) {
        return null
    }
    val scope = row.toConversationSourceScopeOrNull() ?: return null
    return try {
        SourceInvalidationAuthorityEvent(
            scope = scope,
            conversationId = row.conversationId,
            objectKind = SourceAuthorityObjectKind.MESSAGE,
            sourceId = row.messageId,
            sourceRevision = row.sourceRevision,
            previousSourceRevision = requireNotNull(row.previousSourceRevision),
            conversationSourceRevision = row.conversationSourceRevision,
            sourceState = ConversationSourceState.valueOf(row.sourceState),
            changeKind = ConversationSourceChangeKind.valueOf(row.changeKind),
            occurredAtMs = row.occurredAtMs,
        ).toLearningOutboxDraft(streamId)
    } catch (_: IllegalArgumentException) {
        null
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

private data class ConversationSourceCursor(
    val updatedAtMs: Long = -1L,
    val conversationId: String = "",
    val scopeKind: String = "",
    val scopeId: String = "",
)

private data class MessageSourceCursor(
    val updatedAtMs: Long = -1L,
    val conversationId: String = "",
    val messageId: String = "",
    val scopeKind: String = "",
    val scopeId: String = "",
)

private data class SourceAuthorityPageResult(
    val rowCount: Int,
    val conversationCursor: ConversationSourceCursor?,
    val messageCursor: MessageSourceCursor?,
    val provenCoverageFloorMs: Long?,
) {
    companion object {
        fun fromConversations(
            rows: List<LearningConversationSourceAuthorityProjection>,
            floorMs: Long?,
        ): SourceAuthorityPageResult = SourceAuthorityPageResult(
            rowCount = rows.size,
            conversationCursor = rows.lastOrNull()?.let { row ->
                ConversationSourceCursor(
                    updatedAtMs = row.updatedAtMs,
                    conversationId = row.conversationId,
                    scopeKind = row.scopeKind,
                    scopeId = row.scopeId,
                )
            },
            messageCursor = null,
            provenCoverageFloorMs = floorMs,
        )

        fun fromMessages(
            rows: List<LearningMessageSourceAuthorityProjection>,
            floorMs: Long?,
        ): SourceAuthorityPageResult = SourceAuthorityPageResult(
            rowCount = rows.size,
            conversationCursor = null,
            messageCursor = rows.lastOrNull()?.let { row ->
                MessageSourceCursor(
                    updatedAtMs = row.updatedAtMs,
                    conversationId = row.conversationId,
                    messageId = row.messageId,
                    scopeKind = row.scopeKind,
                    scopeId = row.scopeId,
                )
            },
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

private fun LearningConversationSourceAuthorityProjection.toConversationSourceScopeOrNull():
    ConversationSourceScope? = parseConversationSourceScopeOrNull(scopeKind, scopeId)

private fun LearningMessageSourceAuthorityProjection.toConversationSourceScopeOrNull():
    ConversationSourceScope? = parseConversationSourceScopeOrNull(scopeKind, scopeId)

private fun parseConversationSourceScopeOrNull(
    scopeKind: String,
    scopeId: String,
): ConversationSourceScope? = try {
    ConversationSourceScope(
        kind = ConversationSourceScopeKind.valueOf(scopeKind),
        id = scopeId,
    )
} catch (_: IllegalArgumentException) {
    null
}
