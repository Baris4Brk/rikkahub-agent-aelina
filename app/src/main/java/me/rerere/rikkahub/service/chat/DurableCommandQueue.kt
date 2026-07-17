package me.rerere.rikkahub.service.chat

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.dao.PendingChatCommandDao
import me.rerere.rikkahub.data.db.entity.PendingChatCommandEntity
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** A wake hint; the Room rows remain the source of truth if this signal is lost. */
data object WakeUp

enum class DurableCommandState {
    PENDING,
    RUNNING,
    WAITING_APPROVAL,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    MANUAL_CONFIRMATION,
}

enum class RecoveryAction {
    RESUME,
    RETRY,
    REPAIR,
    MANUAL_CONFIRMATION,
}

data class RecoveryDecision(
    val action: RecoveryAction,
    val reason: String,
)

sealed interface DurableSubmitResult {
    data class Inserted(val commandId: Uuid) : DurableSubmitResult
    data class AlreadyExists(val commandId: Uuid) : DurableSubmitResult
    data class DedupeHit(val commandId: Uuid) : DurableSubmitResult
    data class InvalidPayload(val reason: String) : DurableSubmitResult
}

class DurableCommandQueue(
    private val dao: PendingChatCommandDao,
    private val workerId: String = "rikkahub-${Uuid.random()}",
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    onWakeUp: (WakeUp) -> Unit = {},
) {
    @Volatile
    private var wakeUpListener: (WakeUp) -> Unit = onWakeUp

    fun setWakeUpListener(listener: (WakeUp) -> Unit) {
        wakeUpListener = listener
    }
    /**
     * Persist a command before waking a runtime. A failed wake cannot lose the row because
     * callers can always invoke [scanPending] after startup, completion, or lease expiry.
     */
    suspend fun submitDurable(
        command: PendingChatCommandEntity,
        wakeUp: Boolean = true,
    ): DurableSubmitResult {
        val existing = dao.findByIdempotencyKey(command.idempotencyKey)
        if (existing != null) return DurableSubmitResult.AlreadyExists(Uuid.parse(existing.id))
        command.dedupeKey?.let { dedupeKey ->
            val active = dao.findActiveByDedupeKey(command.conversationId, dedupeKey)
            if (active != null) return DurableSubmitResult.DedupeHit(Uuid.parse(active.id))
        }
        // Room returns the SQLite rowId for a successful @Insert (any positive value),
        // and -1 for OnConflictStrategy.IGNORE. Treating only rowId=1 as success makes
        // every later insert look like a failure even though the row was committed.
        val inserted = dao.insert(command) != -1L
        if (!inserted) {
            val raced = dao.findByIdempotencyKey(command.idempotencyKey)
            return if (raced != null) {
                DurableSubmitResult.AlreadyExists(Uuid.parse(raced.id))
            } else {
                DurableSubmitResult.InvalidPayload("Command could not be inserted")
            }
        }
        if (wakeUp) wakeUpListener(WakeUp)
        return DurableSubmitResult.Inserted(Uuid.parse(command.id))
    }

    /** Compatibility wrapper for existing callers. */
    suspend fun submit(command: PendingChatCommandEntity): Boolean =
        submitDurable(command).let { it is DurableSubmitResult.Inserted }

    suspend fun claim(
        id: String,
        now: Long = nowMillis(),
        lease: Duration = 30.seconds,
    ): Boolean = dao.claim(id, workerId, now + lease.inWholeMilliseconds, now) == 1

    suspend fun claimNext(
        conversationId: Uuid,
        now: Long = nowMillis(),
        lease: Duration = 30.seconds,
    ): PendingChatCommandEntity? {
        val candidates = dao.findPending(conversationId.toString(), now, limit = 32)
        for (candidate in candidates) {
            if (dao.claim(candidate.id, workerId, now + lease.inWholeMilliseconds, now) == 1) {
                return dao.findById(candidate.id) ?: candidate.copy(
                    state = DurableCommandState.RUNNING.name,
                    claimedBy = workerId,
                    leaseUntil = now + lease.inWholeMilliseconds,
                    attempt = candidate.attempt + 1,
                )
            }
        }
        return null
    }

    suspend fun scanPending(
        now: Long = nowMillis(),
        limit: Int = 128,
    ): List<PendingChatCommandEntity> = dao.findPendingGlobally(now, limit)

    suspend fun renew(
        id: String,
        now: Long = nowMillis(),
        lease: Duration = 30.seconds,
    ): Boolean = dao.renewLease(id, workerId, now + lease.inWholeMilliseconds) == 1

    suspend fun recoverExpired(now: Long = nowMillis()): Int = dao.interruptExpired(now)

    suspend fun complete(
        id: String,
        state: DurableCommandState,
        error: Throwable? = null,
    ): Boolean {
        val changed = dao.finish(
            id = id,
            state = state.name,
            finishedAt = nowMillis(),
            errorCode = error?.javaClass?.simpleName,
            errorMessage = error?.message,
        ) == 1
        if (changed) wakeUpListener(WakeUp)
        return changed || dao.findById(id)?.state == state.name
    }

    suspend fun resolvePending(
        id: String,
        state: DurableCommandState,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): Boolean {
        val changed = dao.resolvePending(
            id = id,
            state = state.name,
            finishedAt = nowMillis(),
            errorCode = errorCode,
            errorMessage = errorMessage,
        ) == 1
        if (changed) wakeUpListener(WakeUp)
        return changed || dao.findById(id)?.state == state.name
    }

    suspend fun rewritePendingCommand(
        id: Uuid,
        command: ChatCommand,
        origin: CommandOrigin = CommandOrigin.INTERNAL,
    ): Boolean {
        val (type, payload) = CommandCodec.encodeDurable(command, origin)
        val changed = dao.rewritePendingCommand(id.toString(), type, payload) == 1
        if (changed) wakeUpListener(WakeUp)
        return changed
    }

    suspend fun clearPending(conversationId: Uuid): Int = dao.clearPending(conversationId.toString())

    suspend fun countActive(conversationId: Uuid): Int = dao.countActive(conversationId.toString())

    suspend fun decideRecovery(command: PendingChatCommandEntity): RecoveryDecision = when {
        command.state != DurableCommandState.INTERRUPTED.name ->
            RecoveryDecision(RecoveryAction.REPAIR, "Command is not interrupted")
        command.lastErrorCode == "SIDE_EFFECT_UNKNOWN" ->
            RecoveryDecision(RecoveryAction.MANUAL_CONFIRMATION, "External side effect is unknown")
        command.type == "mcp_task" && command.payloadJson.contains("taskId") ->
            RecoveryDecision(RecoveryAction.RESUME, "MCP task can be queried by taskId")
        command.type == "repair" ->
            RecoveryDecision(RecoveryAction.REPAIR, "Only local message repair is required")
        else -> RecoveryDecision(RecoveryAction.RETRY, "No resumable external task was recorded")
    }

    fun observe(conversationId: Uuid): Flow<List<PendingChatCommandEntity>> =
        dao.observe(conversationId.toString())

    fun observePending(): Flow<List<PendingChatCommandEntity>> = dao.observePending()

    fun decodeEnvelope(
        entity: PendingChatCommandEntity,
        origin: CommandOrigin? = null,
    ): CommandEnvelope<out ChatCommand>? {
        // Payloads are versioned independently from the Room schema. Unknown future
        // versions must be surfaced for manual confirmation, never silently replayed.
        if (entity.schemaVersion !in 1..1) return null
        val command = CommandCodec.decode(entity.type, entity.payloadJson) ?: return null
        return CommandEnvelope(
            id = Uuid.parse(entity.id),
            conversationId = Uuid.parse(entity.conversationId),
            command = command,
            origin = origin ?: CommandCodec.decodeDurableOrigin(entity.payloadJson),
            sequence = entity.sequence,
            expiresAt = entity.expiresAt?.let { kotlin.time.Instant.fromEpochMilliseconds(it) },
            dedupeKey = entity.dedupeKey,
        )
    }
}
