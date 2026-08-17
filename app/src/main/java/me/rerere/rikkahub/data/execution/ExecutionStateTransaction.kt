package me.rerere.rikkahub.data.execution

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.learning.handoff.LearningOutboxAppender
import me.rerere.rikkahub.learning.handoff.LearningOutboxAppendResult
import me.rerere.rikkahub.learning.handoff.LearningOutboxDraft
import me.rerere.rikkahub.learning.model.DisabledLearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.DisabledLearningScopeConsentSource
import me.rerere.rikkahub.learning.model.LearningScopeConsentSource
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningCorrelation
import me.rerere.rikkahub.learning.model.LearningEventCode
import me.rerere.rikkahub.learning.model.LearningEventType
import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource
import me.rerere.rikkahub.learning.model.LearningSourceKind
import me.rerere.rikkahub.learning.model.LearningSourceRef

data class ExecutionMutation(
    val executionId: String,
    val mutationId: String,
    val expectedVersion: Long,
    val source: ExecutionStateSource,
    val reasonCode: String? = null,
    val targetStatus: ExecutionStatus? = null,
    val verificationState: VerificationState? = null,
    val runtime: ExecutionRuntime? = null,
    val executionKind: ExecutionKind? = null,
    val runtimeHandleSummary: String? = null,
    val completionPolicy: CompletionPolicy? = null,
    val runtimeInstanceMarker: String? = null,
    val cancellationResult: String? = null,
    val requestedTerminalOutcome: RequestedTerminalOutcome? = null,
    val terminalDetail: String? = null,
    val heartbeatAtMs: Long? = null,
    val probeAtMs: Long? = null,
    val appendEvent: Boolean = true,
)

sealed interface ExecutionMutationResult {
    data class Applied(val record: ExecutionRecord) : ExecutionMutationResult
    data class Duplicate(val record: ExecutionRecord) : ExecutionMutationResult
    data class Missing(val id: String) : ExecutionMutationResult
    data class Terminal(val record: ExecutionRecord) : ExecutionMutationResult
    data class Invalid(
        val current: ExecutionStatus,
        val requested: ExecutionStatus,
    ) : ExecutionMutationResult
    data class Conflict(val currentVersion: Long) : ExecutionMutationResult
}

/**
 * Receipt for an execution mutation committed inside an authority transaction owned elsewhere.
 * [insertedOutbox] is only a post-commit scheduling signal; it must not be dispatched until the
 * outermost Room transaction has returned successfully.
 */
data class ExecutionMutationCommit(
    val result: ExecutionMutationResult,
    val insertedOutbox: Boolean,
)

internal sealed interface ExecutionReduction {
    data class Next(val record: ExecutionRecord) : ExecutionReduction
    data class Terminal(val record: ExecutionRecord) : ExecutionReduction
    data class Invalid(
        val current: ExecutionStatus,
        val requested: ExecutionStatus,
    ) : ExecutionReduction
}

internal object ExecutionMutationReducer {
    fun reduce(
        existing: ExecutionRecord,
        mutation: ExecutionMutation,
        nowMs: Long,
    ): ExecutionReduction {
        val current = ExecutionStatus.fromWire(existing.status)
        val target = mutation.targetStatus ?: current
        if (current.isTerminal) return ExecutionReduction.Terminal(existing)
        if (!current.canTransitionTo(target)) return ExecutionReduction.Invalid(current, target)

        val next = existing.copy(
            status = target.name,
            runtime = mutation.runtime?.name ?: existing.runtime,
            executionKind = mutation.executionKind?.name ?: existing.executionKind,
            runtimeHandleSummary = mutation.runtimeHandleSummary ?: existing.runtimeHandleSummary,
            updatedAtMs = nowMs,
            startedAtMs = existing.startedAtMs ?: nowMs.takeIf {
                target == ExecutionStatus.starting || target == ExecutionStatus.running
            },
            heartbeatAtMs = mutation.heartbeatAtMs
                ?: nowMs.takeIf { target == ExecutionStatus.running }
                ?: existing.heartbeatAtMs,
            finishedAtMs = nowMs.takeIf { target.isTerminal } ?: existing.finishedAtMs,
            cancellationResult = mutation.cancellationResult ?: existing.cancellationResult,
            requestedTerminalOutcome = mutation.requestedTerminalOutcome?.name
                ?: existing.requestedTerminalOutcome,
            terminalDetail = mutation.terminalDetail ?: existing.terminalDetail,
            stateVersion = existing.stateVersion + 1,
            lastStateSource = mutation.source.name,
            lastReasonCode = mutation.reasonCode ?: existing.lastReasonCode,
            verificationState = mutation.verificationState?.name ?: existing.verificationState,
            lastProbeAtMs = mutation.probeAtMs ?: existing.lastProbeAtMs,
            completionPolicy = mutation.completionPolicy?.name ?: existing.completionPolicy,
            runtimeInstanceMarker = mutation.runtimeInstanceMarker ?: existing.runtimeInstanceMarker,
            cancellationRequestedAtMs = existing.cancellationRequestedAtMs ?: nowMs.takeIf {
                target == ExecutionStatus.cancel_requested
            },
        )
        return ExecutionReduction.Next(next)
    }
}

/** Database-level CAS boundary for execution snapshots and their append-only event journal. */
class ExecutionStateTransaction(
    private val database: AppDatabase,
    private val recordDao: ExecutionRecordDao,
    private val eventDao: ExecutionEventDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val metrics: ExecutionConsistencyMetrics? = null,
    private val learningOutboxAppender: LearningOutboxAppender? = null,
    private val learningFeatureFlags: LearningFeatureFlagSource =
        DisabledLearningFeatureFlagSource,
    private val learningScopeConsent: LearningScopeConsentSource =
        DisabledLearningScopeConsentSource,
    /** Best-effort scheduling hook invoked only after an outbox-bearing transaction commits. */
    private val learningPostCommitWake: () -> Unit = {},
) {
    suspend fun open(
        draft: ExecutionRecordDraft,
        mutationId: String = "open:${draft.id}",
        source: ExecutionStateSource = ExecutionStateSource.LIVE_EVENT,
        reasonCode: String = "execution_opened",
    ): ExecutionRecord = database.withTransaction {
        openInCurrentTransaction(draft, mutationId, source, reasonCode)
    }

    suspend fun openInCurrentTransaction(
        draft: ExecutionRecordDraft,
        mutationId: String = "open:${draft.id}",
        source: ExecutionStateSource = ExecutionStateSource.LIVE_EVENT,
        reasonCode: String = "execution_opened",
    ): ExecutionRecord {
        check(database.inTransaction()) { "execution_open_requires_authority_transaction" }
        require(!draft.initialStatus.isTerminal) {
            "execution_open_terminal_requires_mutation"
        }
        recordDao.getById(draft.id)?.let { existing ->
            check(existing.hasSameAdmissionIdentityAs(draft)) {
                "execution_open_identity_conflict"
            }
            return existing
        }
        val now = nowMs()
        val created = draft.toRecord(now).copy(
            stateVersion = 1,
            lastStateSource = source.name,
            lastReasonCode = reasonCode,
        )
        if (recordDao.insertIgnore(created) == -1L) {
            val existing = checkNotNull(recordDao.getById(draft.id))
            check(existing.hasSameAdmissionIdentityAs(draft)) {
                "execution_open_identity_conflict"
            }
            return existing
        }
        eventDao.insert(
            ExecutionEventRecord(
                eventId = mutationId,
                executionId = created.id,
                sequence = created.stateVersion,
                previousStatus = null,
                nextStatus = created.status,
                previousVerification = null,
                nextVerification = created.verificationState,
                source = source.name,
                reasonCode = reasonCode,
                createdAtMs = now,
            ),
        )
        return created
    }

    suspend fun mutate(mutation: ExecutionMutation): ExecutionMutationResult {
        val commit = database.withTransaction { mutateInCurrentTransaction(mutation) }
        dispatchExternalPostCommit(commit)
        return commit.result
    }

    /**
     * Mutates snapshot, journal, and learning outbox inside the caller's existing Room
     * transaction. This method never schedules derived work.
     */
    suspend fun mutateInCurrentTransaction(
        mutation: ExecutionMutation,
    ): ExecutionMutationCommit {
        check(database.inTransaction()) { "execution_mutation_requires_authority_transaction" }
        val existing = recordDao.getById(mutation.executionId)
            ?: return ExecutionMutationCommit(
                ExecutionMutationResult.Missing(mutation.executionId),
                insertedOutbox = false,
            )
        val duplicateEvent = mutation.mutationId
            .takeIf { mutation.appendEvent }
            ?.let { eventDao.getById(it) }
        if (duplicateEvent != null) {
            check(duplicateEvent.hasSameJournalIdentityAs(mutation, existing.stateVersion)) {
                "execution_mutation_identity_conflict"
            }
            check(existing.stateVersion >= duplicateEvent.sequence) {
                "execution_event_ahead_of_snapshot"
            }
            return ExecutionMutationCommit(
                result = ExecutionMutationResult.Duplicate(existing),
                insertedOutbox = appendExecutionTerminalIfEnabled(existing, duplicateEvent),
            )
        }
        if (existing.stateVersion != mutation.expectedVersion) {
            metrics?.recordCasConflict()
            return ExecutionMutationCommit(
                ExecutionMutationResult.Conflict(existing.stateVersion),
                insertedOutbox = false,
            )
        }
        var insertedOutbox = false
        val result = when (val reduced = ExecutionMutationReducer.reduce(existing, mutation, nowMs())) {
            is ExecutionReduction.Invalid -> ExecutionMutationResult.Invalid(
                reduced.current,
                reduced.requested,
            )
            is ExecutionReduction.Terminal -> ExecutionMutationResult.Terminal(reduced.record)
            is ExecutionReduction.Next -> {
                val next = reduced.record
                if (ExecutionStatus.fromWire(next.status).isTerminal && !mutation.appendEvent) {
                    error("execution_terminal_event_required")
                }
                val updated = recordDao.compareAndSet(
                    id = next.id,
                    expectedVersion = existing.stateVersion,
                    nextVersion = next.stateVersion,
                    status = next.status,
                    runtime = next.runtime,
                    executionKind = next.executionKind,
                    runtimeHandleSummary = next.runtimeHandleSummary,
                    updatedAtMs = next.updatedAtMs,
                    startedAtMs = next.startedAtMs,
                    heartbeatAtMs = next.heartbeatAtMs,
                    finishedAtMs = next.finishedAtMs,
                    cancellationResult = next.cancellationResult,
                    terminalDetail = next.terminalDetail,
                    lastStateSource = next.lastStateSource,
                    lastReasonCode = next.lastReasonCode,
                    verificationState = next.verificationState,
                    lastProbeAtMs = next.lastProbeAtMs,
                    completionPolicy = next.completionPolicy,
                    runtimeInstanceMarker = next.runtimeInstanceMarker,
                    cancellationRequestedAtMs = next.cancellationRequestedAtMs,
                    requestedTerminalOutcome = next.requestedTerminalOutcome,
                )
                if (updated != 1) {
                    metrics?.recordCasConflict()
                    return ExecutionMutationCommit(
                        ExecutionMutationResult.Conflict(
                            recordDao.getById(next.id)?.stateVersion ?: existing.stateVersion,
                        ),
                        insertedOutbox = false,
                    )
                }
                if (mutation.appendEvent) {
                    val event = ExecutionEventRecord(
                        eventId = mutation.mutationId,
                        executionId = next.id,
                        sequence = next.stateVersion,
                        previousStatus = existing.status,
                        nextStatus = next.status,
                        previousVerification = existing.verificationState,
                        nextVerification = next.verificationState,
                        source = mutation.source.name,
                        reasonCode = mutation.reasonCode,
                        createdAtMs = next.updatedAtMs,
                    )
                    eventDao.insert(event)
                    insertedOutbox = appendExecutionTerminalIfEnabled(next, event)
                }
                ExecutionMutationResult.Applied(next)
            }
        }
        return ExecutionMutationCommit(result, insertedOutbox)
    }

    /** Dispatches a receipt only after the transaction that owns it has committed. */
    fun dispatchExternalPostCommit(commit: ExecutionMutationCommit) {
        if (commit.insertedOutbox) {
            try {
                learningPostCommitWake()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The authority mutation and outbox row are already durable. Startup/periodic
                // reconciliation is the recovery path when best-effort scheduling is unavailable.
            }
        }
    }

    private suspend fun appendExecutionTerminalIfEnabled(
        record: ExecutionRecord,
        event: ExecutionEventRecord,
    ): Boolean {
        val previousStatus = event.previousStatus?.let(::strictExecutionStatus) ?: return false
        val nextStatus = strictExecutionStatus(event.nextStatus)
        if (previousStatus.isTerminal || !nextStatus.isTerminal) return false
        check(record.id == event.executionId && record.stateVersion == event.sequence) {
            "execution_terminal_event_snapshot_mismatch"
        }
        check(
            record.status == event.nextStatus &&
                record.verificationState == event.nextVerification,
        ) {
            "execution_terminal_event_state_mismatch"
        }

        val flags = learningFeatureFlags.current()
        if (!flags.isValid || !flags.effective.handoff) return false
        val appender = checkNotNull(learningOutboxAppender) {
            "learning_handoff_enabled_without_outbox_appender"
        }
        // Null/invalid scope is a legacy imported row. It remains ineligible; never infer a scope
        // from a principal string at terminal time.
        val scope = record.learningScopeOrNull() ?: return false
        if (!learningScopeConsent.captureAllowed(scope)) return false
        val toolIdentity = listOf(record.toolCallId, record.toolName, record.toolSchemaFingerprint)
        val hasAnyToolIdentity = toolIdentity.any { it != null }
        if (hasAnyToolIdentity &&
            (toolIdentity.any { it == null } ||
                record.owningAssistantMessageId == null ||
                record.owningAssistantMessageRevision?.let { it > 0L } != true)
        ) {
            // The assistant checkpoint is bound later by the WAITING/final owning transaction.
            // Reconciliation will deterministically emit schema-v2 after that exact pair exists.
            return false
        }
        val eventSchemaVersion = if (hasAnyToolIdentity) 2 else 1
        val appendResult = appender.appendInCurrentAuthorityTransaction { streamId ->
            val sourceId = LearningCanonicalId.executionEventSourceId(event.eventId)
            LearningOutboxDraft(
                streamId = streamId,
                eventCode = LearningEventCode(
                    rawCode = LearningEventType.EXECUTION_TERMINAL.name,
                    schemaVersion = eventSchemaVersion,
                ),
                source = LearningSourceRef(
                    sourceKind = LearningSourceKind.EXECUTION_EVENT,
                    sourceId = sourceId,
                    sourceRevision = event.sequence,
                    missingRevisionReason = null,
                    databaseStreamId = streamId,
                    scope = scope,
                    occurredAtMs = event.createdAtMs,
                ),
                correlation = record.toLearningCorrelation(),
                terminalStateCode = nextStatus.toLearningTerminalCode(),
                createdAtMs = event.createdAtMs,
            )
        }
        return appendResult is LearningOutboxAppendResult.Inserted
    }
}

/** Columns intentionally omitted here are mutable execution state updated by the CAS reducer. */
internal fun ExecutionRecord.hasSameAdmissionIdentityAs(draft: ExecutionRecordDraft): Boolean =
    id == draft.id &&
        traceId == draft.traceId &&
        parentExecutionId == draft.parentExecutionId &&
        commandId == draft.commandId &&
        conversationId == draft.conversationId &&
        toolCallId == draft.toolCallId &&
        toolName == draft.toolName &&
        toolSchemaFingerprint == draft.toolSchemaFingerprint &&
        learningScopeKind == draft.learningScope.kind.name &&
        learningScopeId == draft.learningScope.storageId &&
        subjectId == draft.subjectId &&
        subjectType == draft.subjectType &&
        origin == draft.origin &&
        capabilityKeys == draft.capabilityKeys &&
        resourceSummary == draft.resourceSummary &&
        idempotencyKey == draft.idempotencyKey &&
        executionKind == draft.executionKind.name &&
        completionPolicy == draft.completionPolicy.name

/** Checks every mutation identity field represented by the append-only event journal. */
internal fun ExecutionEventRecord.hasSameJournalIdentityAs(
    mutation: ExecutionMutation,
    currentVersion: Long,
): Boolean {
    val previousStatus = previousStatus ?: return false
    val previousVerification = previousVerification ?: return false
    // Repository retries rebuild expectedVersion from the latest snapshot. Accept that replay
    // shape as well as the producer's original CAS version, but no unrelated version.
    val versionMatches = mutation.expectedVersion == sequence - 1L ||
        mutation.expectedVersion == currentVersion
    return eventId == mutation.mutationId &&
        executionId == mutation.executionId &&
        versionMatches &&
        source == mutation.source.name &&
        reasonCode == mutation.reasonCode &&
        nextStatus == (mutation.targetStatus?.name ?: previousStatus) &&
        nextVerification == (mutation.verificationState?.name ?: previousVerification)
}

internal fun ExecutionRecord.toLearningCorrelation(): LearningCorrelation = LearningCorrelation(
    conversationId = conversationId,
    commandId = commandId,
    generationRunId = traceId,
    executionId = id,
    toolCallId = toolCallId,
    toolName = toolName,
    toolSchemaFingerprint = toolSchemaFingerprint,
    messageId = owningAssistantMessageId,
    messageRevision = owningAssistantMessageRevision,
)

private fun strictExecutionStatus(value: String): ExecutionStatus =
    checkNotNull(ExecutionStatus.entries.firstOrNull { it.name == value }) {
        "invalid_execution_event_status"
    }

internal fun ExecutionStatus.toLearningTerminalCode(): String = when (this) {
    ExecutionStatus.succeeded -> "SUCCEEDED"
    ExecutionStatus.failed -> "FAILED"
    ExecutionStatus.cancelled -> "CANCELLED"
    ExecutionStatus.timed_out -> "TIMED_OUT"
    ExecutionStatus.orphaned -> "ORPHANED"
    ExecutionStatus.unknown -> "UNKNOWN"
    else -> error("execution_status_is_not_terminal")
}
