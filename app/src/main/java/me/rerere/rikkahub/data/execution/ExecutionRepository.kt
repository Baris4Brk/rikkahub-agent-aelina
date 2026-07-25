package me.rerere.rikkahub.data.execution

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Input for a new authoritative execution row. All fields are deliberately non-secret. */
data class ExecutionRecordDraft(
    val id: String,
    val traceId: String,
    val parentExecutionId: String? = null,
    val commandId: String? = null,
    val conversationId: String? = null,
    val subjectId: String,
    val subjectType: String,
    val origin: String,
    val capabilityKeys: String,
    val resourceSummary: String,
    val runtime: ExecutionRuntime,
    val idempotencyKey: String? = null,
    val initialStatus: ExecutionStatus = ExecutionStatus.queued,
)

sealed interface ExecutionTransitionResult {
    data class Applied(val record: ExecutionRecord) : ExecutionTransitionResult
    data class Missing(val id: String) : ExecutionTransitionResult
    data class Terminal(val record: ExecutionRecord) : ExecutionTransitionResult
    data class Invalid(val current: ExecutionStatus, val requested: ExecutionStatus) : ExecutionTransitionResult
}

/**
 * Serialised source of truth for execution lifecycle writes.
 *
 * Unlike the older summary ledger, illegal transitions are not silently overwritten: terminal
 * state is immutable, and callers can distinguish an already-terminal record from an invalid
 * state jump. The runtime observer is best-effort around this repository; the repository itself
 * stays strict so future task-center and recovery UI can trust the persisted result.
 */
class ExecutionRepository(
    private val dao: ExecutionRecordDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    suspend fun open(draft: ExecutionRecordDraft): ExecutionRecord = mutex.withLock {
        val existing = dao.getById(draft.id)
        if (existing != null) return@withLock existing
        val now = nowMs()
        val created = ExecutionRecord(
            id = draft.id,
            traceId = draft.traceId,
            parentExecutionId = draft.parentExecutionId,
            commandId = draft.commandId,
            conversationId = draft.conversationId,
            subjectId = draft.subjectId.take(160),
            subjectType = draft.subjectType.take(80),
            origin = draft.origin.take(80),
            capabilityKeys = draft.capabilityKeys.take(MAX_CAPABILITIES_CHARS),
            resourceSummary = draft.resourceSummary.take(MAX_RESOURCE_SUMMARY_CHARS),
            runtime = draft.runtime.name,
            idempotencyKey = draft.idempotencyKey?.take(MAX_IDEMPOTENCY_CHARS),
            status = draft.initialStatus.name,
            createdAtMs = now,
            updatedAtMs = now,
            startedAtMs = if (draft.initialStatus in setOf(ExecutionStatus.starting, ExecutionStatus.running)) now else null,
            heartbeatAtMs = if (draft.initialStatus == ExecutionStatus.running) now else null,
            finishedAtMs = if (draft.initialStatus.isTerminal) now else null,
        )
        val inserted = dao.insertIgnore(created)
        if (inserted != -1L) created else checkNotNull(dao.getById(draft.id))
    }

    suspend fun transition(
        id: String,
        target: ExecutionStatus,
        runtimeHandleSummary: String? = null,
        cancellationResult: String? = null,
        detail: String? = null,
    ): ExecutionTransitionResult = mutex.withLock {
        val existing = dao.getById(id) ?: return@withLock ExecutionTransitionResult.Missing(id)
        val current = ExecutionStatus.fromWire(existing.status)
        if (current.isTerminal) return@withLock ExecutionTransitionResult.Terminal(existing)
        if (!current.canTransitionTo(target)) {
            return@withLock ExecutionTransitionResult.Invalid(current, target)
        }
        val now = nowMs()
        val updated = existing.copy(
            status = target.name,
            updatedAtMs = now,
            startedAtMs = existing.startedAtMs ?: if (
                target == ExecutionStatus.starting || target == ExecutionStatus.running
            ) now else null,
            heartbeatAtMs = if (target == ExecutionStatus.running) now else existing.heartbeatAtMs,
            finishedAtMs = if (target.isTerminal) now else existing.finishedAtMs,
            runtimeHandleSummary = runtimeHandleSummary?.take(MAX_HANDLE_CHARS)
                ?: existing.runtimeHandleSummary,
            cancellationResult = cancellationResult?.take(MAX_CANCELLATION_CHARS)
                ?: existing.cancellationResult,
            terminalDetail = detail?.take(MAX_DETAIL_CHARS) ?: existing.terminalDetail,
        )
        dao.update(updated)
        ExecutionTransitionResult.Applied(updated)
    }

    suspend fun heartbeat(id: String): ExecutionTransitionResult = mutex.withLock {
        val existing = dao.getById(id) ?: return@withLock ExecutionTransitionResult.Missing(id)
        val current = ExecutionStatus.fromWire(existing.status)
        if (current.isTerminal) return@withLock ExecutionTransitionResult.Terminal(existing)
        val updated = existing.copy(
            updatedAtMs = nowMs(),
            heartbeatAtMs = nowMs(),
        )
        dao.update(updated)
        ExecutionTransitionResult.Applied(updated)
    }

    suspend fun bindRuntime(
        id: String,
        runtime: ExecutionRuntime,
        runtimeHandleSummary: String,
    ): ExecutionTransitionResult = mutex.withLock {
        val existing = dao.getById(id) ?: return@withLock ExecutionTransitionResult.Missing(id)
        val current = ExecutionStatus.fromWire(existing.status)
        if (current.isTerminal) return@withLock ExecutionTransitionResult.Terminal(existing)
        val updated = existing.copy(
            runtime = runtime.name,
            runtimeHandleSummary = runtimeHandleSummary.take(MAX_HANDLE_CHARS),
            updatedAtMs = nowMs(),
        )
        dao.update(updated)
        ExecutionTransitionResult.Applied(updated)
    }

    suspend fun get(id: String): ExecutionRecord? = dao.getById(id)

    suspend fun getInFlight(): List<ExecutionRecord> = dao.getInFlight()

    suspend fun findLatestByIdempotencyKey(key: String): ExecutionRecord? =
        dao.getLatestByIdempotencyKey(key)

    fun observeRecent(limit: Int = 100): Flow<List<ExecutionRecord>> = dao.observeRecent(limit)

    suspend fun getRecent(limit: Int = 100): List<ExecutionRecord> = dao.getRecent(limit)

    companion object {
        private const val MAX_CAPABILITIES_CHARS = 500
        private const val MAX_RESOURCE_SUMMARY_CHARS = 500
        private const val MAX_IDEMPOTENCY_CHARS = 300
        private const val MAX_HANDLE_CHARS = 500
        private const val MAX_CANCELLATION_CHARS = 160
        private const val MAX_DETAIL_CHARS = 500
    }
}
