package me.rerere.rikkahub.service.chat

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.execution.ApprovalAuthorityCommitFailure
import me.rerere.rikkahub.data.execution.ApprovalResumeAuthorityCommit
import me.rerere.rikkahub.data.db.dao.PendingChatCommandDao
import me.rerere.rikkahub.data.db.entity.PendingChatCommandEntity
import java.nio.charset.StandardCharsets
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
    workerId: String = Uuid.random().toString(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    onWakeUp: (WakeUp) -> Unit = {},
    private val commandStateTransaction: CommandStateTransaction? = null,
) {
    /** Always persisted as a UUID even for legacy callers that supplied a display label. */
    private val workerId: String = workerId.toCommandWorkerUuid().toString()
    @Volatile
    private var wakeUpListener: (WakeUp) -> Unit = onWakeUp

    fun setWakeUpListener(listener: (WakeUp) -> Unit) {
        wakeUpListener = listener
    }

    suspend fun admitFenced(command: PendingChatCommandEntity): CommandAdmissionResult =
        requireNotNull(commandStateTransaction) {
            "Fenced command state transaction is not configured"
        }.admit(command).also { result ->
            if (result is CommandAdmissionResult.Inserted) runCatching { wakeUpListener(WakeUp) }
        }

    /**
     * Ensures the one deterministic resume row while the caller owns the approval Room
     * transaction. The waiting owner is the stable parent (not the retryable approval child), so
     * replay resolves to the same admission identity.
     */
    suspend fun ensureApprovalResumeInCurrentTransaction(
        conversationId: Uuid,
        approvalId: String,
        resolutionRequestId: String,
        resolvedAtMs: Long?,
        approvalCommandId: Uuid,
        owningWaitingCommandId: String?,
    ): ApprovalResumeAuthorityCommit {
        val transaction = requireNotNull(commandStateTransaction) {
            "Fenced command state transaction is not configured"
        }
        val waitingId = owningWaitingCommandId
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: throw ApprovalAuthorityCommitFailure("approval_waiting_owner_missing")
        val waiting = dao.findById(waitingId.toString())
            ?: throw ApprovalAuthorityCommitFailure("approval_waiting_owner_missing")
        val approvalChild = dao.findById(approvalCommandId.toString())
            ?: throw ApprovalAuthorityCommitFailure("approval_command_missing")
        val waitingLineage = CommandLineageContext.fromAuthorityRowOrNull(waiting)
            ?: throw ApprovalAuthorityCommitFailure("approval_waiting_lineage_invalid")
        val approvalLineage = CommandLineageContext.fromAuthorityRowOrNull(approvalChild)
            ?: throw ApprovalAuthorityCommitFailure("approval_command_lineage_invalid")
        when {
            waiting.conversationId != conversationId.toString() ||
                approvalChild.conversationId != conversationId.toString() ->
                throw ApprovalAuthorityCommitFailure("approval_command_conversation_mismatch")
            approvalChild.state != DurableCommandState.RUNNING.name || approvalChild.type != TOOL_APPROVAL_TYPE ->
                throw ApprovalAuthorityCommitFailure("approval_command_not_running")
            approvalChild.parentCommandId != waiting.id ->
                throw ApprovalAuthorityCommitFailure("approval_command_parent_mismatch")
            waitingLineage.assistantIdSnapshot != approvalLineage.assistantIdSnapshot ||
                waitingLineage.lineageId != approvalLineage.lineageId ||
                waitingLineage.branchAnchorMessageId != approvalLineage.branchAnchorMessageId ->
                throw ApprovalAuthorityCommitFailure("approval_command_lineage_mismatch")
            waiting.authoritySubjectId != approvalChild.authoritySubjectId ->
                throw ApprovalAuthorityCommitFailure("approval_command_scope_mismatch")
        }
        val stableResolvedAt = resolvedAtMs
            ?.takeIf { it >= 0L }
            ?: throw ApprovalAuthorityCommitFailure("approval_resolution_time_missing")
        val stableRequestId = resolutionRequestId.takeIf { it.isNotBlank() }
            ?: throw ApprovalAuthorityCommitFailure("approval_resolution_request_missing")
        val resumeId = approvalResumeCommandId(approvalId, stableRequestId)
        val (type, payload) = CommandCodec.encodeDurable(
            ResumeAfterApprovalCommand,
            CommandOrigin.INTERNAL,
        )
        val draft = PendingChatCommandEntity(
            id = resumeId.toString(),
            schemaVersion = 2,
            conversationId = conversationId.toString(),
            authoritySubjectId = waiting.authoritySubjectId,
            assistantIdSnapshot = waitingLineage.assistantIdSnapshot.toString(),
            lineageId = waitingLineage.lineageId.toString(),
            parentCommandId = waiting.id,
            branchAnchorMessageId = waitingLineage.branchAnchorMessageId.toString(),
            branchAnchorMessageRevision = waitingLineage.branchAnchorMessageRevision,
            conversationSourceRevision = waiting.conversationSourceRevision,
            stateVersion = 0L,
            type = type,
            payloadJson = payload,
            state = DurableCommandState.PENDING.name,
            priority = 0,
            sequence = 0L,
            expectedTargetVersion = null,
            expectedBranchHeadMessageId = null,
            dedupeKey = approvalResumeDedupeKey(approvalId, stableRequestId),
            idempotencyKey = resumeId.toString(),
            attempt = 0,
            claimedBy = null,
            leaseUntil = null,
            createdAt = stableResolvedAt,
            startedAt = null,
            finishedAt = null,
            expiresAt = null,
            lastErrorCode = null,
            lastErrorMessage = null,
        )
        if (draft.branchAnchorMessageRevision == null) {
            throw ApprovalAuthorityCommitFailure("approval_waiting_branch_revision_missing")
        }
        val sourceRevision = waiting.conversationSourceRevision
            ?: throw ApprovalAuthorityCommitFailure("approval_waiting_source_revision_missing")
        val admission = transaction.admitInCurrentTransaction(draft, sourceRevision)
        val row = when (val result = admission.result) {
            is CommandAdmissionResult.Inserted -> result.row
            is CommandAdmissionResult.AlreadyExists -> result.row
            is CommandAdmissionResult.DedupeHit ->
                throw ApprovalAuthorityCommitFailure("approval_resume_dedupe_conflict")
            is CommandAdmissionResult.Invalid ->
                throw ApprovalAuthorityCommitFailure(result.code.lowercase())
            is CommandAdmissionResult.Conflict ->
                throw ApprovalAuthorityCommitFailure(result.code.lowercase())
        }
        if (row.id != resumeId.toString()) {
            throw ApprovalAuthorityCommitFailure("approval_resume_identity_conflict")
        }
        return ApprovalResumeAuthorityCommit(
            commandId = row.id,
            insertedCommand = admission.result is CommandAdmissionResult.Inserted,
            insertedOutbox = admission.insertedOutbox,
        )
    }

    /** Must be called only after the outer approval transaction commits successfully. */
    suspend fun approvalResumeCommitted(
        commit: ApprovalResumeAuthorityCommit,
    ): PendingChatCommandEntity? {
        requireNotNull(commandStateTransaction) {
            "Fenced command state transaction is not configured"
        }.dispatchExternalPostCommit(commit.insertedOutbox)
        runCatching { wakeUpListener(WakeUp) }
        return dao.findById(commit.commandId)
    }

    fun approvalResumeCommandId(approvalId: String, resolutionRequestId: String): Uuid =
        Uuid.parse(
            java.util.UUID.nameUUIDFromBytes(
                "approval-resume:v1\u0000$approvalId\u0000$resolutionRequestId"
                    .toByteArray(StandardCharsets.UTF_8),
            ).toString(),
        )

    fun approvalResumeDedupeKey(approvalId: String, resolutionRequestId: String): String =
        "approval-resume:$approvalId:$resolutionRequestId".take(MAX_DEDUPE_KEY_CHARS)

    /** Strict v2 claim API. The returned opaque token is required for every later mutation. */
    suspend fun claimFenced(
        id: Uuid,
        lease: Duration = 30.seconds,
    ): CommandClaimResult = requireNotNull(commandStateTransaction) {
        "Fenced command state transaction is not configured"
    }.claim(id, Uuid.parse(workerId), lease)

    suspend fun renewFenced(
        claim: CommandClaim,
        lease: Duration = 30.seconds,
    ): CommandTransitionResult = requireNotNull(commandStateTransaction) {
        "Fenced command state transaction is not configured"
    }.renew(claim, lease)

    suspend fun finishFenced(
        claim: CommandClaim,
        terminal: DurableCommandState,
        errorCode: String? = null,
    ): CommandTransitionResult = requireNotNull(commandStateTransaction) {
        "Fenced command state transaction is not configured"
    }.finishClaimed(claim, terminal, errorCode)

    suspend fun finishFencedAndWaitingLineage(
        claim: CommandClaim,
        terminal: DurableCommandState,
        errorCode: String? = null,
    ): CommandLineageFinishResult = requireNotNull(commandStateTransaction) {
        "Fenced command state transaction is not configured"
    }.finishClaimedAndWaitingLineage(claim, terminal, errorCode)

    suspend fun markWaitingApprovalFenced(claim: CommandClaim): CommandTransitionResult =
        requireNotNull(commandStateTransaction) {
            "Fenced command state transaction is not configured"
        }.markWaitingApproval(claim)

    suspend fun finishUnclaimedFenced(
        id: Uuid,
        terminal: DurableCommandState,
        errorCode: String? = null,
    ): CommandTransitionResult = requireNotNull(commandStateTransaction) {
        "Fenced command state transaction is not configured"
    }.finishUnclaimed(id, terminal, errorCode)

    suspend fun recoverExpiredFenced(limit: Int = 64): Int =
        requireNotNull(commandStateTransaction) {
            "Fenced command state transaction is not configured"
        }.recoverExpired(limit)

    suspend fun cancelWaitingForConversation(
        conversationId: Uuid,
        errorCode: String,
    ): CommandWaitingCancellationResult = requireNotNull(commandStateTransaction) {
        "Fenced command state transaction is not configured"
    }.cancelWaitingForConversation(conversationId, errorCode)
    /**
     * Persist a command before waking a runtime. A failed wake cannot lose the row because
     * callers can always invoke [scanPending] after startup, completion, or lease expiry.
     */
    suspend fun submitDurable(
        command: PendingChatCommandEntity,
        wakeUp: Boolean = true,
    ): DurableSubmitResult {
        val lineage = CommandLineageContext.fromAuthorityRowOrNull(command)
        val hasAuthorityMetadata = command.assistantIdSnapshot != null ||
            command.lineageId != null ||
            command.parentCommandId != null ||
            command.branchAnchorMessageId != null
        if (hasAuthorityMetadata && lineage == null) {
            return DurableSubmitResult.InvalidPayload("Incomplete command authority lineage")
        }
        if (lineage != null && commandStateTransaction != null) {
            val result = commandStateTransaction.admit(command)
            if (wakeUp && result is CommandAdmissionResult.Inserted) {
                runCatching { wakeUpListener(WakeUp) }
            }
            return when (result) {
                is CommandAdmissionResult.Inserted -> DurableSubmitResult.Inserted(
                    Uuid.parse(result.row.id),
                )
                is CommandAdmissionResult.AlreadyExists -> DurableSubmitResult.AlreadyExists(
                    Uuid.parse(result.row.id),
                )
                is CommandAdmissionResult.DedupeHit -> DurableSubmitResult.DedupeHit(
                    Uuid.parse(result.row.id),
                )
                is CommandAdmissionResult.Invalid -> DurableSubmitResult.InvalidPayload(result.code)
                is CommandAdmissionResult.Conflict -> DurableSubmitResult.InvalidPayload(result.code)
            }
        }
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

    /** Runtime recovery must page within its own conversation and must never replay WAITING. */
    suspend fun scanReplayable(
        conversationId: Uuid,
        now: Long = nowMillis(),
        limit: Int = 128,
    ): List<PendingChatCommandEntity> = dao.findReplayableForConversation(
        conversationId = conversationId.toString(),
        now = now,
        limit = limit,
    )

    /** Suspension rows are read separately so they can gate FIFO without being dispatched. */
    suspend fun scanWaiting(
        conversationId: Uuid,
        limit: Int = 256,
    ): List<PendingChatCommandEntity> = dao.findWaitingForConversation(
        conversationId = conversationId.toString(),
        limit = limit,
    )

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
        val stableFailure = error as? DurableCommandFailure
        val changed = dao.finish(
            id = id,
            state = state.name,
            finishedAt = nowMillis(),
            errorCode = stableFailure?.durableErrorCode ?: error?.javaClass?.simpleName,
            errorMessage = stableFailure?.durableErrorMessage ?: error?.message,
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

    suspend fun clearPending(conversationId: Uuid): Int =
        commandStateTransaction?.cancelConversationPending(
            conversationId = conversationId,
            code = "QUEUE_CLEARED",
        ) ?: dao.clearPending(conversationId.toString())

    /** An authority epoch is an admission capability, not a replayable queue attribute. */
    suspend fun cancelByAuthoritySubject(subjectId: String): Int {
        commandStateTransaction?.let { transaction ->
            val changed = transaction.cancelByAuthoritySubject(
                subjectId = subjectId,
                code = "AUTHORITY_REVOKED",
            )
            if (changed > 0) runCatching { wakeUpListener(WakeUp) }
            return changed
        }
        val changed = dao.cancelByAuthoritySubject(
            subjectId = subjectId,
            finishedAt = nowMillis(),
        )
        if (changed > 0) wakeUpListener(WakeUp)
        return changed
    }

    /** Cancels pre-v39 second-user rows which have no epoch snapshot to validate. */
    suspend fun cancelLegacyUnscopedForConversation(conversationId: Uuid): Int {
        commandStateTransaction?.let { transaction ->
            val changed = transaction.cancelLegacyUnscopedForConversation(
                conversationId = conversationId,
                code = "LEGACY_SCOPE_REJECTED",
            )
            if (changed > 0) runCatching { wakeUpListener(WakeUp) }
            return changed
        }
        val changed = dao.cancelLegacyUnscopedForConversation(
            conversationId = conversationId.toString(),
            finishedAt = nowMillis(),
        )
        if (changed > 0) wakeUpListener(WakeUp)
        return changed
    }

    suspend fun countActive(conversationId: Uuid): Int = dao.countActive(conversationId.toString())

    suspend fun findAuthorityRow(commandId: Uuid): PendingChatCommandEntity? =
        dao.findById(commandId.toString())

    suspend fun findSingleWaitingForConversation(
        conversationId: Uuid,
    ): PendingChatCommandEntity? = dao.findWaitingForConversation(
        conversationId = conversationId.toString(),
        // Two rows are enough to prove that the result is not unique. Unlike filtering an
        // arbitrary active prefix, this cannot overlook a WAITING row behind unrelated work.
        limit = 2,
    )
        .singleOrNull()

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

    /**
     * One-shot authoritative startup snapshot, including a previous process's RUNNING rows whose
     * lease has not expired yet. The new process cannot rely on that old worker to finish them.
     */
    suspend fun listActive(): List<PendingChatCommandEntity> = dao.listActive()

    fun decodeEnvelope(
        entity: PendingChatCommandEntity,
        origin: CommandOrigin? = null,
    ): CommandEnvelope<out ChatCommand>? {
        // Payloads are versioned independently from the Room schema. Unknown future
        // versions must be surfaced for manual confirmation, never silently replayed.
        if (entity.schemaVersion !in 1..2) return null
        val command = CommandCodec.decode(entity.type, entity.payloadJson) ?: return null
        return CommandEnvelope(
            id = Uuid.parse(entity.id),
            conversationId = Uuid.parse(entity.conversationId),
            command = command,
            origin = origin ?: CommandCodec.decodeDurableOrigin(entity.payloadJson),
            sequence = entity.sequence,
            expiresAt = entity.expiresAt?.let { kotlin.time.Instant.fromEpochMilliseconds(it) },
            dedupeKey = entity.dedupeKey,
            lineage = CommandLineageContext.fromAuthorityRowOrNull(entity),
        )
    }

    /** New recovery paths must reject legacy rows rather than inventing authority metadata. */
    fun decodeFencedEnvelope(
        entity: PendingChatCommandEntity,
        origin: CommandOrigin? = null,
    ): CommandEnvelope<out ChatCommand>? = decodeEnvelope(entity, origin)
        ?.takeIf { it.lineage != null && entity.stateVersion > 0L }
}

private fun String.toCommandWorkerUuid(): Uuid {
    val parsed = runCatching { Uuid.parse(this) }.getOrNull()
        ?.takeUnless { it.toString() == NIL_UUID }
    if (parsed != null) return parsed
    return Uuid.parse(java.util.UUID.nameUUIDFromBytes(encodeToByteArray()).toString())
}

private const val TOOL_APPROVAL_TYPE = "tool_approval"
private const val MAX_DEDUPE_KEY_CHARS = 300
