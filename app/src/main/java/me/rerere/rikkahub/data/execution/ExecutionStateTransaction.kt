package me.rerere.rikkahub.data.execution

import androidx.room.withTransaction
import me.rerere.rikkahub.data.db.AppDatabase

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
) {
    suspend fun open(
        draft: ExecutionRecordDraft,
        mutationId: String = "open:${draft.id}",
        source: ExecutionStateSource = ExecutionStateSource.LIVE_EVENT,
        reasonCode: String = "execution_opened",
    ): ExecutionRecord = database.withTransaction {
        recordDao.getById(draft.id)?.let { return@withTransaction it }
        val now = nowMs()
        val created = draft.toRecord(now).copy(
            stateVersion = 1,
            lastStateSource = source.name,
            lastReasonCode = reasonCode,
        )
        if (recordDao.insertIgnore(created) == -1L) {
            return@withTransaction checkNotNull(recordDao.getById(draft.id))
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
        created
    }

    suspend fun mutate(mutation: ExecutionMutation): ExecutionMutationResult =
        database.withTransaction {
            val existing = recordDao.getById(mutation.executionId)
                ?: return@withTransaction ExecutionMutationResult.Missing(mutation.executionId)
            if (mutation.appendEvent && eventDao.getById(mutation.mutationId) != null) {
                return@withTransaction ExecutionMutationResult.Duplicate(existing)
            }
            if (existing.stateVersion != mutation.expectedVersion) {
                return@withTransaction ExecutionMutationResult.Conflict(existing.stateVersion)
            }
            when (val reduced = ExecutionMutationReducer.reduce(existing, mutation, nowMs())) {
                is ExecutionReduction.Invalid -> ExecutionMutationResult.Invalid(
                    reduced.current,
                    reduced.requested,
                )
                is ExecutionReduction.Terminal -> ExecutionMutationResult.Terminal(reduced.record)
                is ExecutionReduction.Next -> {
                    val next = reduced.record
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
                    )
                    if (updated != 1) {
                        return@withTransaction ExecutionMutationResult.Conflict(
                            recordDao.getById(next.id)?.stateVersion ?: existing.stateVersion,
                        )
                    }
                    if (mutation.appendEvent) {
                        eventDao.insert(
                            ExecutionEventRecord(
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
                            ),
                        )
                    }
                    ExecutionMutationResult.Applied(next)
                }
            }
        }
}
