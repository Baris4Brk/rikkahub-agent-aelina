package me.rerere.rikkahub.service.chat

import me.rerere.rikkahub.data.db.dao.PendingChatCommandDao
import me.rerere.rikkahub.data.db.entity.PendingChatCommandEntity
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Adapter implemented with AppDatabase.withTransaction by the composition root. */
interface CommandTransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

/** Compatibility runner for tests and the staged rollout before production DI is switched. */
object DirectCommandTransactionRunner : CommandTransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}

enum class CommandAuthorityEventKind { ADMITTED, WAITING_APPROVAL, TERMINAL }

/** Content-free event projection. Payload JSON and error messages never cross this seam. */
data class CommandAuthorityEvent(
    val kind: CommandAuthorityEventKind,
    val commandId: Uuid,
    val stateVersion: Long,
    val state: DurableCommandState,
    val conversationId: Uuid,
    val authoritySubjectId: String?,
    val lineage: CommandLineageContext,
    /** Null means a legacy/non-combined mutation; it must never be inferred from current state. */
    val conversationSourceRevision: Long?,
    val completion: CommandCompletionAuthority?,
    val occurredAtMs: Long,
) {
    init {
        require(conversationSourceRevision == null || conversationSourceRevision > 0L)
        require(completion == null || completion.commandState == state)
        require(
            when (kind) {
                CommandAuthorityEventKind.ADMITTED -> completion == null
                CommandAuthorityEventKind.WAITING_APPROVAL ->
                    completion?.phase == CommandCompletionPhase.WAITING
                CommandAuthorityEventKind.TERMINAL ->
                    completion == null || completion.phase == CommandCompletionPhase.TERMINAL
            },
        ) { "Command authority completion does not match the event boundary" }
    }

    override fun toString(): String =
        "CommandAuthorityEvent(kind=$kind, version=$stateVersion, state=$state, " +
            "authority=${authoritySubjectId != null}, sourceRevision=$conversationSourceRevision, " +
            "completion=${completion?.kind}, occurredAtMs=$occurredAtMs, ids=<redacted>)"
}

fun interface CommandAuthorityEventPort {
    /** Returns true only when this call inserted a new outbox row in the owning transaction. */
    suspend fun appendInCurrentTransaction(event: CommandAuthorityEvent): Boolean
}

object NoOpCommandAuthorityEventPort : CommandAuthorityEventPort {
    override suspend fun appendInCurrentTransaction(event: CommandAuthorityEvent): Boolean = false
}

sealed interface CommandAdmissionResult {
    data class Inserted(val row: PendingChatCommandEntity) : CommandAdmissionResult
    data class AlreadyExists(val row: PendingChatCommandEntity) : CommandAdmissionResult
    data class DedupeHit(val row: PendingChatCommandEntity) : CommandAdmissionResult
    data class Invalid(val code: String) : CommandAdmissionResult
    data class Conflict(val code: String) : CommandAdmissionResult
}

/**
 * Result of an admission performed inside a transaction owned by another authority component.
 * [insertedOutbox] is only a post-commit scheduling hint; callers must never wake derived work
 * until their outer transaction has returned successfully.
 */
data class CommandAuthorityAdmissionCommit(
    val result: CommandAdmissionResult,
    val insertedOutbox: Boolean,
)

/** Result of a completion mutation inside a transaction owned by a combined authority writer. */
data class CommandAuthorityMutationCommit<T>(
    val result: T,
    val insertedOutbox: Boolean,
)

sealed interface CommandClaimResult {
    data class Claimed(
        val row: PendingChatCommandEntity,
        val claim: CommandClaim,
    ) : CommandClaimResult

    data class Unavailable(val row: PendingChatCommandEntity?) : CommandClaimResult
    data class LegacyBlocked(val row: PendingChatCommandEntity) : CommandClaimResult
}

sealed interface CommandTransitionResult {
    data class Applied(val row: PendingChatCommandEntity) : CommandTransitionResult
    data class Renewed(
        val row: PendingChatCommandEntity,
        val claim: CommandClaim,
    ) : CommandTransitionResult
    data class Duplicate(val row: PendingChatCommandEntity) : CommandTransitionResult
    data class Conflict(val row: PendingChatCommandEntity?) : CommandTransitionResult
}

sealed interface CommandLineageFinishResult {
    data class Applied(
        val claimedRow: PendingChatCommandEntity,
        /** Includes the claimed resume command and every WAITING row committed terminal with it. */
        val terminalizedCommandIds: List<Uuid>,
    ) : CommandLineageFinishResult

    data class Duplicate(
        val claimedRow: PendingChatCommandEntity,
        val terminalizedCommandIds: List<Uuid>,
    ) : CommandLineageFinishResult

    data class Conflict(
        val row: PendingChatCommandEntity?,
        val code: String,
    ) : CommandLineageFinishResult
}

sealed interface CommandWaitingCancellationResult {
    data class Applied(val terminalizedCommandIds: List<Uuid>) : CommandWaitingCancellationResult
    data object NoOp : CommandWaitingCancellationResult
    data class Conflict(val code: String) : CommandWaitingCancellationResult
}

private class CommandBatchConflict(
    val code: String,
    val authorityRow: PendingChatCommandEntity? = null,
) : RuntimeException(code)

/**
 * The only v2 writer for durable command authority state.
 *
 * Every mutation is a version CAS. Claimed mutations additionally fence worker UUID, exact lease,
 * and lease time. The clock is sampled inside the transaction after the authoritative row read.
 */
class CommandStateTransaction(
    private val dao: PendingChatCommandDao,
    private val transactions: CommandTransactionRunner = DirectCommandTransactionRunner,
    private val events: CommandAuthorityEventPort = NoOpCommandAuthorityEventPort,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val learningPostCommitWake: () -> Unit = {},
) {
    suspend fun admit(draft: PendingChatCommandEntity): CommandAdmissionResult =
        authorityTransaction { transactionEvents ->
            admitInCurrentTransaction(
                draft = draft,
                transactionEvents = transactionEvents,
                conversationSourceRevision = null,
            )
        }

    /**
     * Admission seam for a larger [AppDatabase][me.rerere.rikkahub.data.db.AppDatabase]
     * transaction (for example approval resolution). This method deliberately does not open a
     * transaction and does not schedule learning work. The outer owner must call
     * [dispatchExternalPostCommit] only after its transaction commits.
     */
    suspend fun admitInCurrentTransaction(
        draft: PendingChatCommandEntity,
    ): CommandAuthorityAdmissionCommit {
        return admitInCurrentTransaction(draft, conversationSourceRevision = null)
    }

    /** Combined admission seam; [conversationSourceRevision] is from the exact graph commit. */
    suspend fun admitInCurrentTransaction(
        draft: PendingChatCommandEntity,
        conversationSourceRevision: Long?,
    ): CommandAuthorityAdmissionCommit {
        require(conversationSourceRevision == null || conversationSourceRevision > 0L)
        var insertedOutbox = false
        val trackingEvents = CommandAuthorityEventPort { event ->
            events.appendInCurrentTransaction(event).also { inserted ->
                if (inserted) insertedOutbox = true
            }
        }
        return CommandAuthorityAdmissionCommit(
            result = admitInCurrentTransaction(
                draft = draft,
                transactionEvents = trackingEvents,
                conversationSourceRevision = conversationSourceRevision,
            ),
            insertedOutbox = insertedOutbox,
        )
    }

    /** Completes the scheduling half of [admitInCurrentTransaction] after the outer commit. */
    fun dispatchExternalPostCommit(insertedOutbox: Boolean) {
        if (insertedOutbox) runCatching { learningPostCommitWake() }
    }

    suspend fun claim(
        id: Uuid,
        workerId: Uuid,
        lease: Duration = 30.seconds,
    ): CommandClaimResult = transactions.inTransaction {
        require(workerId.toString() != NIL_UUID) { "Worker UUID cannot be nil" }
        require(lease > Duration.ZERO) { "Lease must be positive" }
        val existing = dao.findById(id.toString())
            ?: return@inTransaction CommandClaimResult.Unavailable(null)
        if (CommandLineageContext.fromAuthorityRowOrNull(existing) == null || existing.stateVersion <= 0L) {
            return@inTransaction CommandClaimResult.LegacyBlocked(existing)
        }
        val now = checkedNow()
        val leaseUntil = Math.addExact(now, lease.inWholeMilliseconds)
        val changed = dao.claimFenced(
            id = existing.id,
            expectedVersion = existing.stateVersion,
            workerId = workerId.toString(),
            leaseUntil = leaseUntil,
            now = now,
        )
        if (changed != 1) return@inTransaction CommandClaimResult.Unavailable(dao.findById(existing.id))
        val claimed = requireReloadedVersion(existing.id, existing.stateVersion + 1L)
        CommandClaimResult.Claimed(
            row = claimed,
            claim = CommandClaim.create(id, workerId, claimed.stateVersion, leaseUntil),
        )
    }

    suspend fun renew(
        claim: CommandClaim,
        lease: Duration = 30.seconds,
    ): CommandTransitionResult = transactions.inTransaction {
        require(lease > Duration.ZERO) { "Lease must be positive" }
        val now = checkedNow()
        val nextLease = Math.addExact(now, lease.inWholeMilliseconds)
        if (now > claim.leaseUntilMs || nextLease <= claim.leaseUntilMs) {
            return@inTransaction CommandTransitionResult.Conflict(dao.findById(claim.commandId.toString()))
        }
        val changed = dao.renewLeaseFenced(
            id = claim.commandId.toString(),
            expectedVersion = claim.stateVersion,
            workerId = claim.workerId.toString(),
            expectedLeaseUntil = claim.leaseUntilMs,
            leaseUntil = nextLease,
            now = now,
        )
        if (changed != 1) {
            return@inTransaction CommandTransitionResult.Conflict(dao.findById(claim.commandId.toString()))
        }
        val row = requireReloadedVersion(claim.commandId.toString(), claim.stateVersion + 1L)
        CommandTransitionResult.Renewed(
            row,
            CommandClaim.create(claim.commandId, claim.workerId, row.stateVersion, nextLease),
        )
    }

    suspend fun markWaitingApproval(claim: CommandClaim): CommandTransitionResult =
        transactions.inTransaction {
            val existing = dao.findById(claim.commandId.toString())
            if (existing?.state == DurableCommandState.WAITING_APPROVAL.name) {
                return@inTransaction if (existing.isExactWaitingDuplicate(claim)) {
                    CommandTransitionResult.Duplicate(existing)
                } else {
                    CommandTransitionResult.Conflict(existing)
                }
            }
            val now = checkedNow()
            val changed = dao.markWaitingApprovalFenced(
                id = claim.commandId.toString(),
                expectedVersion = claim.stateVersion,
                workerId = claim.workerId.toString(),
                expectedLeaseUntil = claim.leaseUntilMs,
                now = now,
            )
            if (changed != 1) {
                return@inTransaction CommandTransitionResult.Conflict(dao.findById(claim.commandId.toString()))
            }
            val row = requireReloadedVersion(claim.commandId.toString(), claim.stateVersion + 1L)
            // WAITING is deliberately not projected to the learning outbox. It is a suspension
            // checkpoint, not an immutable learning event; only admission and terminal rows cross
            // that seam until the approval projection and command authority share one transaction.
            CommandTransitionResult.Applied(row)
        }

    /**
     * Combined approval checkpoint. The caller owns the surrounding graph/approval transaction;
     * this method neither opens a transaction nor dispatches post-commit work.
     */
    suspend fun markWaitingApprovalInCurrentTransaction(
        claim: CommandClaim,
        completion: CommandCompletionAuthority,
        conversationSourceRevision: Long,
    ): CommandAuthorityMutationCommit<CommandTransitionResult> {
        require(completion.kind == CommandCompletionKind.GENERATION_WAITING_APPROVAL)
        require(completion.phase == CommandCompletionPhase.WAITING)
        require(completion.commandState == DurableCommandState.WAITING_APPROVAL)
        require(conversationSourceRevision > 0L)
        val result = requireNotNull(completion.resultMessage)
        var insertedOutbox = false
        val trackingEvents = trackingExternalEvents { insertedOutbox = true }
        val existing = dao.findById(claim.commandId.toString())
        if (existing?.state == DurableCommandState.WAITING_APPROVAL.name) {
            if (!existing.isExactWaitingCompletionDuplicate(
                    claim = claim,
                    completion = completion,
                    conversationSourceRevision = conversationSourceRevision,
                )
            ) {
                return CommandAuthorityMutationCommit(
                    CommandTransitionResult.Conflict(existing),
                    insertedOutbox = false,
                )
            }
            trackingEvents.appendInCurrentTransaction(
                existing.authorityEvent(
                    CommandAuthorityEventKind.WAITING_APPROVAL,
                    combinedAuthority = true,
                ),
            )
            return CommandAuthorityMutationCommit(
                CommandTransitionResult.Duplicate(existing),
                insertedOutbox,
            )
        }
        val now = checkedNow()
        val changed = dao.markWaitingApprovalWithCompletionFenced(
            id = claim.commandId.toString(),
            conversationId = requireConversationId(existing),
            expectedVersion = claim.stateVersion,
            workerId = claim.workerId.toString(),
            expectedLeaseUntil = claim.leaseUntilMs,
            now = now,
            conversationSourceRevision = conversationSourceRevision,
            completionKind = completion.kind.name,
            resultMessageId = result.messageId,
            resultMessageRevision = result.messageRevision,
        )
        if (changed != 1) {
            return CommandAuthorityMutationCommit(
                CommandTransitionResult.Conflict(dao.findById(claim.commandId.toString())),
                insertedOutbox = false,
            )
        }
        val row = requireReloadedVersion(claim.commandId.toString(), claim.stateVersion + 1L)
        trackingEvents.appendInCurrentTransaction(
            row.authorityEvent(
                CommandAuthorityEventKind.WAITING_APPROVAL,
                combinedAuthority = true,
            ),
        )
        return CommandAuthorityMutationCommit(CommandTransitionResult.Applied(row), insertedOutbox)
    }

    suspend fun finishClaimed(
        claim: CommandClaim,
        terminal: DurableCommandState,
        errorCode: String? = null,
    ): CommandTransitionResult = authorityTransaction { transactionEvents ->
        require(terminal.isTerminal) { "Claimed finish requires a terminal state" }
        require(errorCode == null || isSafeErrorCode(errorCode)) { "Invalid command error code" }
        val existing = dao.findById(claim.commandId.toString())
        if (existing?.state == terminal.name) {
            return@authorityTransaction if (existing.isExactClaimedTerminalDuplicate(
                    claim = claim,
                    errorCode = errorCode,
                )
            ) {
                transactionEvents.appendInCurrentTransaction(
                    existing.authorityEvent(CommandAuthorityEventKind.TERMINAL),
                )
                CommandTransitionResult.Duplicate(existing)
            } else {
                CommandTransitionResult.Conflict(existing)
            }
        }
        val now = checkedNow()
        val changed = dao.finishClaimedFenced(
            id = claim.commandId.toString(),
            expectedVersion = claim.stateVersion,
            workerId = claim.workerId.toString(),
            expectedLeaseUntil = claim.leaseUntilMs,
            nextState = terminal.name,
            finishedAt = now,
            now = now,
            errorCode = errorCode,
            errorMessage = null,
        )
        if (changed != 1) {
            return@authorityTransaction CommandTransitionResult.Conflict(
                dao.findById(claim.commandId.toString()),
            )
        }
        val row = requireReloadedVersion(claim.commandId.toString(), claim.stateVersion + 1L)
        transactionEvents.appendInCurrentTransaction(
            row.authorityEvent(CommandAuthorityEventKind.TERMINAL),
        )
        CommandTransitionResult.Applied(row)
    }

    /**
     * Combined final-save/control mutation. A null source revision is accepted only for the
     * explicit FAILED_FINAL_SAVE outcome, because the graph transaction did not commit.
     */
    suspend fun finishClaimedInCurrentTransaction(
        claim: CommandClaim,
        completion: CommandCompletionAuthority,
        conversationSourceRevision: Long?,
        errorCode: String? = null,
    ): CommandAuthorityMutationCommit<CommandTransitionResult> {
        require(completion.phase == CommandCompletionPhase.TERMINAL)
        require(errorCode == null || isSafeErrorCode(errorCode)) { "Invalid command error code" }
        require(
            (completion.kind == CommandCompletionKind.FAILED_FINAL_SAVE) ==
                (conversationSourceRevision == null),
        ) { "Only FAILED_FINAL_SAVE may omit the exact Conversation source revision" }
        require(conversationSourceRevision == null || conversationSourceRevision > 0L)
        var insertedOutbox = false
        val trackingEvents = trackingExternalEvents { insertedOutbox = true }
        val existing = dao.findById(claim.commandId.toString())
        if (existing?.state == completion.commandState.name) {
            if (!existing.isExactClaimedCompletionDuplicate(
                    claim = claim,
                    completion = completion,
                    conversationSourceRevision = conversationSourceRevision,
                    errorCode = errorCode,
                )
            ) {
                return CommandAuthorityMutationCommit(
                    CommandTransitionResult.Conflict(existing),
                    insertedOutbox = false,
                )
            }
            trackingEvents.appendInCurrentTransaction(
                existing.authorityEvent(
                    CommandAuthorityEventKind.TERMINAL,
                    combinedAuthority = true,
                ),
            )
            return CommandAuthorityMutationCommit(
                CommandTransitionResult.Duplicate(existing),
                insertedOutbox,
            )
        }
        val now = checkedNow()
        val changed = dao.finishClaimedWithCompletionFenced(
            id = claim.commandId.toString(),
            conversationId = requireConversationId(existing),
            expectedVersion = claim.stateVersion,
            workerId = claim.workerId.toString(),
            expectedLeaseUntil = claim.leaseUntilMs,
            nextState = completion.commandState.name,
            finishedAt = now,
            now = now,
            errorCode = errorCode,
            conversationSourceRevision = conversationSourceRevision,
            completionKind = completion.kind.name,
            resultMessageId = completion.resultMessage?.messageId,
            resultMessageRevision = completion.resultMessage?.messageRevision,
        )
        if (changed != 1) {
            return CommandAuthorityMutationCommit(
                CommandTransitionResult.Conflict(dao.findById(claim.commandId.toString())),
                insertedOutbox = false,
            )
        }
        val row = requireReloadedVersion(claim.commandId.toString(), claim.stateVersion + 1L)
        trackingEvents.appendInCurrentTransaction(
            row.authorityEvent(
                CommandAuthorityEventKind.TERMINAL,
                combinedAuthority = true,
            ),
        )
        return CommandAuthorityMutationCommit(CommandTransitionResult.Applied(row), insertedOutbox)
    }

    suspend fun finishUnclaimedInCurrentTransaction(
        id: Uuid,
        completion: CommandCompletionAuthority,
        conversationSourceRevision: Long?,
        errorCode: String? = null,
    ): CommandAuthorityMutationCommit<CommandTransitionResult> {
        require(completion.phase == CommandCompletionPhase.TERMINAL)
        require(errorCode == null || isSafeErrorCode(errorCode)) { "Invalid command error code" }
        require(
            (completion.kind == CommandCompletionKind.FAILED_FINAL_SAVE) ==
                (conversationSourceRevision == null),
        ) { "Only FAILED_FINAL_SAVE may omit the exact Conversation source revision" }
        require(conversationSourceRevision == null || conversationSourceRevision > 0L)
        var insertedOutbox = false
        val trackingEvents = trackingExternalEvents { insertedOutbox = true }
        val existing = dao.findById(id.toString())
            ?: return CommandAuthorityMutationCommit(
                CommandTransitionResult.Conflict(null),
                insertedOutbox = false,
            )
        if (existing.state == completion.commandState.name) {
            if (!existing.isExactUnclaimedCompletionDuplicate(
                    completion = completion,
                    conversationSourceRevision = conversationSourceRevision,
                    errorCode = errorCode,
                )
            ) {
                return CommandAuthorityMutationCommit(
                    CommandTransitionResult.Conflict(existing),
                    insertedOutbox = false,
                )
            }
            trackingEvents.appendInCurrentTransaction(
                existing.authorityEvent(
                    CommandAuthorityEventKind.TERMINAL,
                    combinedAuthority = true,
                ),
            )
            return CommandAuthorityMutationCommit(
                CommandTransitionResult.Duplicate(existing),
                insertedOutbox,
            )
        }
        val current = runCatching { DurableCommandState.valueOf(existing.state) }.getOrNull()
            ?: return CommandAuthorityMutationCommit(
                CommandTransitionResult.Conflict(existing),
                insertedOutbox = false,
            )
        if (current.isTerminal || current == DurableCommandState.RUNNING) {
            return CommandAuthorityMutationCommit(
                CommandTransitionResult.Conflict(existing),
                insertedOutbox = false,
            )
        }
        val now = checkedNow()
        val changed = dao.finishUnclaimedWithCompletionFenced(
            id = existing.id,
            conversationId = existing.conversationId,
            expectedState = existing.state,
            expectedVersion = existing.stateVersion,
            nextState = completion.commandState.name,
            finishedAt = now,
            errorCode = errorCode,
            conversationSourceRevision = conversationSourceRevision,
            completionKind = completion.kind.name,
            resultMessageId = completion.resultMessage?.messageId,
            resultMessageRevision = completion.resultMessage?.messageRevision,
        )
        if (changed != 1) {
            return CommandAuthorityMutationCommit(
                CommandTransitionResult.Conflict(dao.findById(existing.id)),
                insertedOutbox = false,
            )
        }
        val row = requireReloadedVersion(existing.id, existing.stateVersion + 1L)
        trackingEvents.appendInCurrentTransaction(
            row.authorityEvent(
                CommandAuthorityEventKind.TERMINAL,
                combinedAuthority = true,
            ),
        )
        return CommandAuthorityMutationCommit(CommandTransitionResult.Applied(row), insertedOutbox)
    }

    /** Combined resume terminal plus every bounded WAITING ancestor in the same lineage. */
    suspend fun finishClaimedAndWaitingLineageInCurrentTransaction(
        claim: CommandClaim,
        completion: CommandCompletionAuthority,
        conversationSourceRevision: Long?,
        errorCode: String? = null,
        waitingLimit: Int = MAX_ATOMIC_WAITING_ROWS,
    ): CommandAuthorityMutationCommit<CommandLineageFinishResult> {
        val boundedLimit = bounded(waitingLimit)
        val existing = dao.findById(claim.commandId.toString())
            ?: return CommandAuthorityMutationCommit(
                CommandLineageFinishResult.Conflict(null, "RESUME_COMMAND_MISSING"),
                false,
            )
        if (existing.type != RESUME_AFTER_APPROVAL_TYPE) {
            return CommandAuthorityMutationCommit(
                CommandLineageFinishResult.Conflict(existing, "LINEAGE_FINISH_REQUIRES_RESUME"),
                false,
            )
        }
        val lineage = CommandLineageContext.fromAuthorityRowOrNull(existing)
            ?: return CommandAuthorityMutationCommit(
                CommandLineageFinishResult.Conflict(existing, "RESUME_LINEAGE_INVALID"),
                false,
            )
        val claimed = finishClaimedInCurrentTransaction(
            claim = claim,
            completion = completion,
            conversationSourceRevision = conversationSourceRevision,
            errorCode = errorCode,
        )
        val claimedRow = when (val result = claimed.result) {
            is CommandTransitionResult.Applied -> result.row
            is CommandTransitionResult.Duplicate -> result.row
            is CommandTransitionResult.Conflict -> return CommandAuthorityMutationCommit(
                CommandLineageFinishResult.Conflict(result.row, "RESUME_CLAIM_FENCE_CONFLICT"),
                claimed.insertedOutbox,
            )
            is CommandTransitionResult.Renewed -> error("Finish cannot renew a command")
        }
        val waitingCount = dao.countWaitingByLineage(
            conversationId = existing.conversationId,
            lineageId = lineage.lineageId.toString(),
        )
        if (waitingCount > boundedLimit) {
            return CommandAuthorityMutationCommit(
                CommandLineageFinishResult.Conflict(
                    claimedRow,
                    "WAITING_LINEAGE_LIMIT_EXCEEDED",
                ),
                claimed.insertedOutbox,
            )
        }
        val waitingRows = dao.listWaitingByLineage(
            conversationId = existing.conversationId,
            lineageId = lineage.lineageId.toString(),
            limit = boundedLimit,
        )
        if (waitingRows.size != waitingCount) {
            return CommandAuthorityMutationCommit(
                CommandLineageFinishResult.Conflict(
                    claimedRow,
                    "WAITING_LINEAGE_SNAPSHOT_INCOMPLETE",
                ),
                claimed.insertedOutbox,
            )
        }
        var insertedOutbox = claimed.insertedOutbox
        var applied = claimed.result is CommandTransitionResult.Applied
        val terminalizedIds = ArrayList<Uuid>(waitingRows.size + 1).apply {
            add(claim.commandId)
        }
        waitingRows.forEach { waiting ->
            val waitingId = waiting.id.parseUuidOrNull()
                ?: return CommandAuthorityMutationCommit(
                    CommandLineageFinishResult.Conflict(
                        waiting,
                        "WAITING_COMMAND_ID_INVALID",
                    ),
                    insertedOutbox,
                )
            val child = finishUnclaimedInCurrentTransaction(
                id = waitingId,
                completion = completion,
                conversationSourceRevision = conversationSourceRevision,
                errorCode = errorCode,
            )
            insertedOutbox = insertedOutbox || child.insertedOutbox
            when (val result = child.result) {
                is CommandTransitionResult.Applied -> applied = true
                is CommandTransitionResult.Duplicate -> Unit
                is CommandTransitionResult.Conflict -> return CommandAuthorityMutationCommit(
                    CommandLineageFinishResult.Conflict(
                        result.row,
                        "WAITING_CAS_CONFLICT",
                    ),
                    insertedOutbox,
                )
                is CommandTransitionResult.Renewed -> error("Finish cannot renew a command")
            }
            terminalizedIds += waitingId
        }
        val result = if (applied) {
            CommandLineageFinishResult.Applied(claimedRow, terminalizedIds)
        } else {
            CommandLineageFinishResult.Duplicate(claimedRow, terminalizedIds)
        }
        return CommandAuthorityMutationCommit(result, insertedOutbox)
    }

    /**
     * Commits a final resume and every suspended row in its lineage as one authority decision.
     * A resume which suspends again must use [markWaitingApproval] and never call this method.
     */
    suspend fun finishClaimedAndWaitingLineage(
        claim: CommandClaim,
        terminal: DurableCommandState,
        errorCode: String? = null,
        waitingLimit: Int = MAX_ATOMIC_WAITING_ROWS,
    ): CommandLineageFinishResult {
        require(terminal.isTerminal) { "Lineage finish requires a terminal state" }
        require(errorCode == null || isSafeErrorCode(errorCode)) { "Invalid command error code" }
        val boundedLimit = bounded(waitingLimit)
        return try {
            authorityTransaction { transactionEvents ->
                val existing = dao.findById(claim.commandId.toString())
                    ?: throw CommandBatchConflict("RESUME_COMMAND_MISSING")
                if (existing.type != RESUME_AFTER_APPROVAL_TYPE) {
                    throw CommandBatchConflict("LINEAGE_FINISH_REQUIRES_RESUME", existing)
                }
                val lineage = CommandLineageContext.fromAuthorityRowOrNull(existing)
                    ?: throw CommandBatchConflict("RESUME_LINEAGE_INVALID", existing)
                val now = checkedNow()
                var claimedChanged = false
                val claimedRow = if (existing.state == terminal.name) {
                    if (!existing.isExactClaimedTerminalDuplicate(claim, errorCode)) {
                        throw CommandBatchConflict("RESUME_TERMINAL_IDENTITY_CONFLICT", existing)
                    }
                    transactionEvents.appendInCurrentTransaction(
                        existing.authorityEvent(CommandAuthorityEventKind.TERMINAL),
                    )
                    existing
                } else {
                    val nextVersion = runCatching { Math.addExact(claim.stateVersion, 1L) }
                        .getOrElse {
                            throw CommandBatchConflict("RESUME_VERSION_EXHAUSTED", existing)
                        }
                    val changed = dao.finishClaimedFenced(
                        id = existing.id,
                        expectedVersion = claim.stateVersion,
                        workerId = claim.workerId.toString(),
                        expectedLeaseUntil = claim.leaseUntilMs,
                        nextState = terminal.name,
                        finishedAt = now,
                        now = now,
                        errorCode = errorCode,
                        errorMessage = null,
                    )
                    if (changed != 1) {
                        throw CommandBatchConflict(
                            "RESUME_CLAIM_FENCE_CONFLICT",
                            dao.findById(existing.id),
                        )
                    }
                    claimedChanged = true
                    requireReloadedVersion(existing.id, nextVersion).also { row ->
                        transactionEvents.appendInCurrentTransaction(
                            row.authorityEvent(CommandAuthorityEventKind.TERMINAL),
                        )
                    }
                }

                val waitingCount = dao.countWaitingByLineage(
                    conversationId = existing.conversationId,
                    lineageId = lineage.lineageId.toString(),
                )
                if (waitingCount > boundedLimit) {
                    throw CommandBatchConflict("WAITING_LINEAGE_LIMIT_EXCEEDED", claimedRow)
                }
                val waitingRows = dao.listWaitingByLineage(
                    conversationId = existing.conversationId,
                    lineageId = lineage.lineageId.toString(),
                    limit = boundedLimit,
                )
                if (waitingRows.size != waitingCount) {
                    throw CommandBatchConflict("WAITING_LINEAGE_SNAPSHOT_INCOMPLETE", claimedRow)
                }
                val waitingIds = terminalizeWaitingRows(
                    rows = waitingRows,
                    conversationId = existing.conversationId,
                    lineageId = lineage.lineageId.toString(),
                    terminal = terminal,
                    errorCode = errorCode,
                    finishedAt = now,
                    transactionEvents = transactionEvents,
                )
                val allIds = ArrayList<Uuid>(waitingIds.size + 1).apply {
                    add(checkNotNull(claimedRow.id.parseUuidOrNull()))
                    addAll(waitingIds)
                }
                if (claimedChanged || waitingIds.isNotEmpty()) {
                    CommandLineageFinishResult.Applied(claimedRow, allIds)
                } else {
                    CommandLineageFinishResult.Duplicate(claimedRow, allIds)
                }
            }
        } catch (conflict: CommandBatchConflict) {
            CommandLineageFinishResult.Conflict(conflict.authorityRow, conflict.code)
        }
    }

    /**
     * Atomically cancels the complete bounded WAITING snapshot for a conversation. Comparing
     * COUNT with LIMIT makes overflow fail closed instead of leaving a hidden approval barrier.
     */
    suspend fun cancelWaitingForConversation(
        conversationId: Uuid,
        code: String,
        limit: Int = MAX_ATOMIC_WAITING_ROWS,
    ): CommandWaitingCancellationResult {
        require(isSafeErrorCode(code)) { "Invalid command cancellation code" }
        val boundedLimit = bounded(limit)
        return try {
            authorityTransaction { transactionEvents ->
                val count = dao.countWaitingForConversation(conversationId.toString())
                if (count == 0) {
                    return@authorityTransaction CommandWaitingCancellationResult.NoOp
                }
                if (count > boundedLimit) {
                    throw CommandBatchConflict("WAITING_CONVERSATION_LIMIT_EXCEEDED")
                }
                val rows = dao.listWaitingForConversation(conversationId.toString(), boundedLimit)
                if (rows.size != count) {
                    throw CommandBatchConflict("WAITING_CONVERSATION_SNAPSHOT_INCOMPLETE")
                }
                val terminalized = terminalizeWaitingRows(
                    rows = rows,
                    conversationId = conversationId.toString(),
                    lineageId = null,
                    terminal = DurableCommandState.CANCELLED,
                    errorCode = code,
                    finishedAt = checkedNow(),
                    transactionEvents = transactionEvents,
                )
                CommandWaitingCancellationResult.Applied(terminalized)
            }
        } catch (conflict: CommandBatchConflict) {
            CommandWaitingCancellationResult.Conflict(conflict.code)
        }
    }

    /**
     * Finalizes an admitted command that never owns an execution lease (queue controls, rejects,
     * and superseded work). The row version and current state are still fenced in the same
     * transaction as the terminal authority event; callers never receive a raw DAO mutation.
     */
    suspend fun finishUnclaimed(
        id: Uuid,
        terminal: DurableCommandState,
        errorCode: String? = null,
    ): CommandTransitionResult = authorityTransaction { transactionEvents ->
        require(terminal.isTerminal) { "Unclaimed finish requires a terminal state" }
        require(errorCode == null || isSafeErrorCode(errorCode)) { "Invalid command error code" }
        val existing = dao.findById(id.toString())
            ?: return@authorityTransaction CommandTransitionResult.Conflict(null)
        if (existing.state == terminal.name) {
            return@authorityTransaction if (existing.isExactUnclaimedTerminalDuplicate(errorCode)) {
                CommandLineageContext.fromAuthorityRowOrNull(existing)?.let {
                    transactionEvents.appendInCurrentTransaction(
                        existing.authorityEvent(CommandAuthorityEventKind.TERMINAL),
                    )
                }
                CommandTransitionResult.Duplicate(existing)
            } else {
                CommandTransitionResult.Conflict(existing)
            }
        }
        val current = runCatching { DurableCommandState.valueOf(existing.state) }.getOrNull()
            ?: return@authorityTransaction CommandTransitionResult.Conflict(existing)
        if (current.isTerminal || current == DurableCommandState.RUNNING) {
            return@authorityTransaction CommandTransitionResult.Conflict(existing)
        }
        val now = checkedNow()
        val changed = dao.finishUnclaimedFenced(
            id = existing.id,
            expectedState = existing.state,
            expectedVersion = existing.stateVersion,
            nextState = terminal.name,
            finishedAt = now,
            errorCode = errorCode,
            errorMessage = null,
        )
        if (changed != 1) {
            return@authorityTransaction CommandTransitionResult.Conflict(dao.findById(existing.id))
        }
        val row = requireReloadedVersion(existing.id, existing.stateVersion + 1L)
        CommandLineageContext.fromAuthorityRowOrNull(row)?.let {
            transactionEvents.appendInCurrentTransaction(
                row.authorityEvent(CommandAuthorityEventKind.TERMINAL),
            )
        }
        CommandTransitionResult.Applied(row)
    }

    suspend fun recoverExpired(limit: Int = 64): Int {
        require(limit in 1..256) { "Invalid recovery batch size" }
        val snapshot = dao.listExpiredRunning(checkedNow(), limit)
        var recovered = 0
        snapshot.forEach { row ->
            val leaseUntil = row.leaseUntil ?: return@forEach
            val changed = authorityTransaction { _ ->
                // Re-sample time inside the transaction. A queued recovery must not use a
                // pre-transaction clock value as its lease-expiry authority.
                val now = checkedNow()
                dao.interruptExpiredFenced(
                    id = row.id,
                    expectedVersion = row.stateVersion,
                    expectedLeaseUntil = leaseUntil,
                    now = now,
                    message = "Worker lease expired",
                )
            }
            if (changed == 1) recovered++
        }
        val expiredPending = dao.listExpiredPending(checkedNow(), limit)
        expiredPending.forEach { snapshotRow ->
            val changed = authorityTransaction { transactionEvents ->
                val now = checkedNow()
                val expiresAt = snapshotRow.expiresAt
                    ?: return@authorityTransaction 0
                if (expiresAt > now) return@authorityTransaction 0
                val nextVersion = runCatching {
                    Math.addExact(snapshotRow.stateVersion, 1L)
                }.getOrElse { throw IllegalStateException("Command version exhausted", it) }
                val result = dao.finishUnclaimedFenced(
                    id = snapshotRow.id,
                    expectedState = DurableCommandState.PENDING.name,
                    expectedVersion = snapshotRow.stateVersion,
                    nextState = DurableCommandState.CANCELLED.name,
                    finishedAt = now,
                    errorCode = COMMAND_EXPIRED_CODE,
                    errorMessage = null,
                )
                if (result == 1) {
                    val row = requireReloadedVersion(snapshotRow.id, nextVersion)
                    CommandLineageContext.fromAuthorityRowOrNull(row)?.let {
                        transactionEvents.appendInCurrentTransaction(
                            row.authorityEvent(CommandAuthorityEventKind.TERMINAL),
                        )
                    }
                }
                result
            }
            if (changed == 1) recovered++
        }
        return recovered
    }

    suspend fun cancelConversationPending(
        conversationId: Uuid,
        code: String,
        limit: Int = 256,
    ): Int = cancelRows(dao.listActiveForConversation(conversationId.toString(), bounded(limit)), code)

    suspend fun cancelByAuthoritySubject(
        subjectId: String,
        code: String,
        limit: Int = 256,
    ): Int = cancelRows(dao.listActiveForAuthoritySubject(subjectId, bounded(limit)), code)

    suspend fun cancelLegacyUnscopedForConversation(
        conversationId: Uuid,
        code: String,
        limit: Int = 256,
    ): Int = cancelRows(
        dao.listLegacyUnscopedActiveForConversation(conversationId.toString(), bounded(limit)),
        code,
    )

    private suspend fun cancelRows(rows: List<PendingChatCommandEntity>, code: String): Int {
        require(isSafeErrorCode(code)) { "Invalid command cancellation code" }
        var changedCount = 0
        rows.forEach { snapshot ->
            val result = authorityTransaction { transactionEvents ->
                val now = checkedNow()
                val changed = dao.finishUnclaimedFenced(
                    id = snapshot.id,
                    expectedState = snapshot.state,
                    expectedVersion = snapshot.stateVersion,
                    nextState = DurableCommandState.CANCELLED.name,
                    finishedAt = now,
                    errorCode = code,
                    errorMessage = null,
                )
                if (changed != 1) return@authorityTransaction false
                val row = requireReloadedVersion(snapshot.id, snapshot.stateVersion + 1L)
                CommandLineageContext.fromAuthorityRowOrNull(row)?.let {
                    transactionEvents.appendInCurrentTransaction(
                        row.authorityEvent(CommandAuthorityEventKind.TERMINAL),
                    )
                }
                true
            }
            if (result) changedCount++
        }
        return changedCount
    }

    /** Must only be called from an owning [transactions] block. */
    private suspend fun terminalizeWaitingRows(
        rows: List<PendingChatCommandEntity>,
        conversationId: String,
        lineageId: String?,
        terminal: DurableCommandState,
        errorCode: String?,
        finishedAt: Long,
        transactionEvents: CommandAuthorityEventPort,
    ): List<Uuid> {
        val terminalized = ArrayList<Uuid>(rows.size)
        rows.forEach { snapshot ->
            val snapshotLineage = CommandLineageContext.fromAuthorityRowOrNull(snapshot)
                ?: throw CommandBatchConflict("WAITING_LINEAGE_INVALID", snapshot)
            if (snapshot.conversationId != conversationId) {
                throw CommandBatchConflict("WAITING_CONVERSATION_MISMATCH", snapshot)
            }
            if (lineageId != null && snapshotLineage.lineageId.toString() != lineageId) {
                throw CommandBatchConflict("WAITING_LINEAGE_MISMATCH", snapshot)
            }
            if (snapshot.state != DurableCommandState.WAITING_APPROVAL.name) {
                throw CommandBatchConflict("WAITING_STATE_CHANGED", snapshot)
            }
            val nextVersion = runCatching { Math.addExact(snapshot.stateVersion, 1L) }
                .getOrElse { throw CommandBatchConflict("WAITING_VERSION_EXHAUSTED", snapshot) }
            val changed = dao.finishUnclaimedFenced(
                id = snapshot.id,
                expectedState = DurableCommandState.WAITING_APPROVAL.name,
                expectedVersion = snapshot.stateVersion,
                nextState = terminal.name,
                finishedAt = finishedAt,
                errorCode = errorCode,
                errorMessage = null,
            )
            if (changed != 1) {
                throw CommandBatchConflict("WAITING_CAS_CONFLICT", dao.findById(snapshot.id))
            }
            val row = requireReloadedVersion(snapshot.id, nextVersion)
            if (row.state != terminal.name || !row.isExactTerminalIdentity(errorCode)) {
                throw CommandBatchConflict("WAITING_TERMINAL_IDENTITY_CONFLICT", row)
            }
            transactionEvents.appendInCurrentTransaction(
                row.authorityEvent(CommandAuthorityEventKind.TERMINAL),
            )
            terminalized.add(
                snapshot.id.parseUuidOrNull()
                    ?: throw CommandBatchConflict("WAITING_COMMAND_ID_INVALID", row),
            )
        }
        return terminalized
    }

    /** The caller owns the database transaction and the event port's post-commit lifecycle. */
    private suspend fun admitInCurrentTransaction(
        draft: PendingChatCommandEntity,
        transactionEvents: CommandAuthorityEventPort,
        conversationSourceRevision: Long?,
    ): CommandAdmissionResult {
        val authorityDraft = draft.copy(
            conversationSourceRevision = conversationSourceRevision,
            completionKind = null,
            resultAssistantMessageId = null,
            resultAssistantMessageRevision = null,
        )
        val invalid = admissionViolation(authorityDraft)
        if (invalid != null) return CommandAdmissionResult.Invalid(invalid)

        dao.findByIdempotencyKey(authorityDraft.idempotencyKey)?.let { existing ->
            return if (existing.hasSameAdmissionIdentity(authorityDraft.copy(stateVersion = 1L))) {
                if (existing.stateVersion == 1L &&
                    existing.state == DurableCommandState.PENDING.name
                ) {
                    transactionEvents.appendInCurrentTransaction(
                        existing.admissionEvent(
                            combinedAuthority = conversationSourceRevision != null,
                        ),
                    )
                }
                CommandAdmissionResult.AlreadyExists(existing)
            } else {
                CommandAdmissionResult.Conflict("IDEMPOTENCY_IDENTITY_CONFLICT")
            }
        }
        val lineageInvalid = authorityLineageViolation(authorityDraft)
        if (lineageInvalid != null) return CommandAdmissionResult.Invalid(lineageInvalid)
        authorityDraft.dedupeKey?.let { key ->
            dao.findActiveByDedupeKey(authorityDraft.conversationId, key)?.let { existing ->
                return CommandAdmissionResult.DedupeHit(existing)
            }
        }

        val currentMax = dao.maxSequenceForConversation(authorityDraft.conversationId) ?: 0L
        val nextSequence = runCatching { Math.addExact(maxOf(0L, currentMax), 1L) }
            .getOrElse { return CommandAdmissionResult.Conflict("SEQUENCE_EXHAUSTED") }
        // The database transaction owns ordering. Process-local AtomicLong values are only
        // submission hints and are deliberately ignored here.
        val admitted = authorityDraft.copy(sequence = nextSequence, stateVersion = 1L)
        if (dao.insert(admitted) == -1L) {
            val raced = dao.findByIdempotencyKey(draft.idempotencyKey)
                ?: return CommandAdmissionResult.Conflict("INSERT_CONFLICT")
            return if (raced.hasSameAdmissionIdentity(admitted)) {
                transactionEvents.appendInCurrentTransaction(
                    raced.admissionEvent(combinedAuthority = conversationSourceRevision != null),
                )
                CommandAdmissionResult.AlreadyExists(raced)
            } else {
                CommandAdmissionResult.Conflict("IDEMPOTENCY_IDENTITY_CONFLICT")
            }
        }
        transactionEvents.appendInCurrentTransaction(
            admitted.authorityEvent(
                CommandAuthorityEventKind.ADMITTED,
                combinedAuthority = conversationSourceRevision != null,
            ),
        )
        return CommandAdmissionResult.Inserted(admitted)
    }

    private fun admissionViolation(row: PendingChatCommandEntity): String? = when {
        row.state != DurableCommandState.PENDING.name -> "ADMISSION_STATE_INVALID"
        row.stateVersion != 0L -> "ADMISSION_VERSION_INVALID"
        row.conversationSourceRevision != null && row.branchAnchorMessageRevision == null ->
            "ADMISSION_BRANCH_REVISION_MISSING"
        CommandLineageContext.fromAuthorityRowOrNull(row) == null -> "ADMISSION_LINEAGE_MISSING"
        row.id.parseUuidOrNull() == null || row.conversationId.parseUuidOrNull() == null ->
            "ADMISSION_ID_INVALID"
        else -> null
    }

    /** Parent identity is authority data and is therefore checked inside the admission tx. */
    private suspend fun authorityLineageViolation(row: PendingChatCommandEntity): String? {
        val lineage = CommandLineageContext.fromAuthorityRowOrNull(row)
            ?: return "ADMISSION_LINEAGE_MISSING"
        val rowId = row.id.parseUuidOrNull() ?: return "ADMISSION_ID_INVALID"
        val parentId = lineage.parentCommandId
        if (parentId == null) {
            return if (lineage.lineageId == rowId) null else "ADMISSION_ROOT_LINEAGE_INVALID"
        }
        if (parentId == rowId) return "ADMISSION_PARENT_SELF_REFERENCE"
        val parent = dao.findById(parentId.toString()) ?: return "ADMISSION_PARENT_MISSING"
        val parentLineage = CommandLineageContext.fromAuthorityRowOrNull(parent)
            ?: return "ADMISSION_PARENT_LINEAGE_INVALID"
        if (parent.stateVersion <= 0L) return "ADMISSION_PARENT_VERSION_INVALID"
        return when {
            parent.conversationId != row.conversationId -> "ADMISSION_PARENT_CONVERSATION_MISMATCH"
            parentLineage.assistantIdSnapshot != lineage.assistantIdSnapshot ->
                "ADMISSION_PARENT_ASSISTANT_MISMATCH"
            parentLineage.lineageId != lineage.lineageId -> "ADMISSION_PARENT_LINEAGE_MISMATCH"
            parentLineage.branchAnchorMessageId != lineage.branchAnchorMessageId ->
                "ADMISSION_PARENT_BRANCH_MISMATCH"
            parentLineage.branchAnchorMessageRevision != lineage.branchAnchorMessageRevision ->
                "ADMISSION_PARENT_BRANCH_REVISION_MISMATCH"
            parent.authoritySubjectId != row.authoritySubjectId -> "ADMISSION_PARENT_SCOPE_MISMATCH"
            row.type == TOOL_APPROVAL_TYPE &&
                parent.state != DurableCommandState.WAITING_APPROVAL.name ->
                "ADMISSION_APPROVAL_PARENT_NOT_WAITING"
            row.type == RESUME_AFTER_APPROVAL_TYPE &&
                dao.countWaitingByLineage(row.conversationId, lineage.lineageId.toString()) == 0 ->
                "ADMISSION_RESUME_LINEAGE_NOT_WAITING"
            else -> null
        }
    }

    private fun PendingChatCommandEntity.isExactWaitingDuplicate(claim: CommandClaim): Boolean =
        stateVersion == runCatching { Math.addExact(claim.stateVersion, 1L) }.getOrNull() &&
            claimedBy == null && leaseUntil == null && finishedAt == null &&
            lastErrorCode == null && lastErrorMessage == null &&
            CommandLineageContext.fromAuthorityRowOrNull(this) != null

    private fun PendingChatCommandEntity.isExactWaitingCompletionDuplicate(
        claim: CommandClaim,
        completion: CommandCompletionAuthority,
        conversationSourceRevision: Long,
    ): Boolean =
        isExactWaitingDuplicate(claim) &&
            this.conversationSourceRevision == conversationSourceRevision &&
            hasExactCompletion(completion)

    private fun PendingChatCommandEntity.isExactClaimedTerminalDuplicate(
        claim: CommandClaim,
        errorCode: String?,
    ): Boolean =
        stateVersion == runCatching { Math.addExact(claim.stateVersion, 1L) }.getOrNull() &&
            isExactTerminalIdentity(errorCode) &&
            CommandLineageContext.fromAuthorityRowOrNull(this) != null

    private fun PendingChatCommandEntity.isExactUnclaimedTerminalDuplicate(
        errorCode: String?,
    ): Boolean = stateVersion > 0L && isExactTerminalIdentity(errorCode)

    private fun PendingChatCommandEntity.isExactTerminalIdentity(errorCode: String?): Boolean =
        finishedAt != null && claimedBy == null && leaseUntil == null &&
            lastErrorCode == errorCode && lastErrorMessage == null

    private fun PendingChatCommandEntity.isExactClaimedCompletionDuplicate(
        claim: CommandClaim,
        completion: CommandCompletionAuthority,
        conversationSourceRevision: Long?,
        errorCode: String?,
    ): Boolean =
        isExactClaimedTerminalDuplicate(claim, errorCode) &&
            this.conversationSourceRevision == conversationSourceRevision &&
            hasExactCompletion(completion)

    private fun PendingChatCommandEntity.isExactUnclaimedCompletionDuplicate(
        completion: CommandCompletionAuthority,
        conversationSourceRevision: Long?,
        errorCode: String?,
    ): Boolean =
        isExactUnclaimedTerminalDuplicate(errorCode) &&
            this.conversationSourceRevision == conversationSourceRevision &&
            hasExactCompletion(completion)

    private fun PendingChatCommandEntity.hasExactCompletion(
        completion: CommandCompletionAuthority,
    ): Boolean =
        completionKind == completion.kind.name &&
            resultAssistantMessageId == completion.resultMessage?.messageId &&
            resultAssistantMessageRevision == completion.resultMessage?.messageRevision

    private fun requireConversationId(row: PendingChatCommandEntity?): String =
        row?.conversationId ?: throw CommandBatchConflict("COMMAND_MISSING")

    private suspend fun requireReloadedVersion(id: String, expected: Long): PendingChatCommandEntity {
        val row = checkNotNull(dao.findById(id)) { "Command disappeared after CAS" }
        check(row.stateVersion == expected) { "Command version did not advance exactly once" }
        return row
    }

    private fun PendingChatCommandEntity.authorityEvent(
        kind: CommandAuthorityEventKind,
        occurredAtMs: Long? = null,
        combinedAuthority: Boolean = false,
    ): CommandAuthorityEvent =
        CommandAuthorityEvent(
            kind = kind,
            commandId = checkNotNull(id.parseUuidOrNull()),
            stateVersion = stateVersion,
            state = DurableCommandState.valueOf(state),
            conversationId = checkNotNull(conversationId.parseUuidOrNull()),
            authoritySubjectId = authoritySubjectId,
            lineage = checkNotNull(CommandLineageContext.fromAuthorityRowOrNull(this)).copy(
                branchAnchorMessageRevision = branchAnchorMessageRevision.takeIf {
                    combinedAuthority
                },
            ),
            conversationSourceRevision = conversationSourceRevision.takeIf { combinedAuthority },
            completion = if (combinedAuthority && kind != CommandAuthorityEventKind.ADMITTED) {
                completionAuthority(kind)
            } else {
                null
            },
            occurredAtMs = occurredAtMs ?: when (kind) {
                CommandAuthorityEventKind.ADMITTED -> createdAt
                CommandAuthorityEventKind.WAITING_APPROVAL -> checkedNow()
                CommandAuthorityEventKind.TERMINAL -> checkNotNull(finishedAt)
            },
        )

    private fun PendingChatCommandEntity.completionAuthority(
        eventKind: CommandAuthorityEventKind,
    ): CommandCompletionAuthority? {
        val completionCode = completionKind ?: return null
        val completionKind = checkNotNull(CommandCompletionKind.parseOrNull(completionCode)) {
            "Unknown command completion authority"
        }
        val result = if (resultAssistantMessageId == null && resultAssistantMessageRevision == null) {
            null
        } else {
            CommandResultMessageAuthority(
                messageId = checkNotNull(resultAssistantMessageId),
                messageRevision = checkNotNull(resultAssistantMessageRevision),
            )
        }
        return CommandCompletionAuthority(
            kind = completionKind,
            phase = when (eventKind) {
                CommandAuthorityEventKind.WAITING_APPROVAL -> CommandCompletionPhase.WAITING
                CommandAuthorityEventKind.TERMINAL -> CommandCompletionPhase.TERMINAL
                CommandAuthorityEventKind.ADMITTED -> error(
                    "Admission row cannot carry command completion authority",
                )
            },
            commandState = DurableCommandState.valueOf(state),
            resultMessage = result,
        )
    }

    private fun PendingChatCommandEntity.admissionEvent(
        combinedAuthority: Boolean,
    ): CommandAuthorityEvent =
        authorityEvent(
            CommandAuthorityEventKind.ADMITTED,
            combinedAuthority = combinedAuthority,
        ).copy(
            stateVersion = 1L,
            state = DurableCommandState.PENDING,
            occurredAtMs = createdAt,
        )

    /**
     * Tracks only newly inserted outbox rows. The wake happens after the Room transaction returns;
     * a rollback never schedules derived work, while a scheduler failure never rolls authority
     * state back. Duplicate/idempotent events do not generate another wake.
     */
    private suspend fun <T> authorityTransaction(
        block: suspend (CommandAuthorityEventPort) -> T,
    ): T {
        var insertedOutbox = false
        val trackingEvents = CommandAuthorityEventPort { event ->
            events.appendInCurrentTransaction(event).also { inserted ->
                if (inserted) insertedOutbox = true
            }
        }
        val result = transactions.inTransaction { block(trackingEvents) }
        if (insertedOutbox) runCatching { learningPostCommitWake() }
        return result
    }

    private fun trackingExternalEvents(
        onInserted: () -> Unit,
    ): CommandAuthorityEventPort = CommandAuthorityEventPort { event ->
        events.appendInCurrentTransaction(event).also { inserted ->
            if (inserted) onInserted()
        }
    }

    private fun checkedNow(): Long = nowMs().also { require(it >= 0L) { "Negative command clock" } }
    private fun bounded(limit: Int): Int = limit.also { require(it in 1..256) { "Invalid batch size" } }
}

val DurableCommandState.isTerminal: Boolean
    get() = this in setOf(
        DurableCommandState.COMPLETED,
        DurableCommandState.FAILED,
        DurableCommandState.CANCELLED,
        DurableCommandState.MANUAL_CONFIRMATION,
    )

private fun String.parseUuidOrNull(): Uuid? = runCatching { Uuid.parse(this) }
    .getOrNull()
    ?.takeUnless { it.toString() == NIL_UUID }

private fun PendingChatCommandEntity.hasSameAdmissionIdentity(other: PendingChatCommandEntity): Boolean {
    if (admissionIdentityProjection() != other.admissionIdentityProjection()) return false
    val bothAtAdmission = state == DurableCommandState.PENDING.name && stateVersion == 1L &&
        other.state == DurableCommandState.PENDING.name && other.stateVersion == 1L
    return !bothAtAdmission || conversationSourceRevision == other.conversationSourceRevision
}

private fun PendingChatCommandEntity.admissionIdentityProjection(): PendingChatCommandEntity = copy(
    state = DurableCommandState.PENDING.name,
    attempt = 0,
    claimedBy = null,
    leaseUntil = null,
    startedAt = null,
    finishedAt = null,
    lastErrorCode = null,
    lastErrorMessage = null,
    conversationSourceRevision = null,
    completionKind = null,
    resultAssistantMessageId = null,
    resultAssistantMessageRevision = null,
    sequence = 0L,
    stateVersion = 0L,
)

private fun isSafeErrorCode(code: String): Boolean = code.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))

private const val MAX_ATOMIC_WAITING_ROWS = 256
private const val TOOL_APPROVAL_TYPE = "tool_approval"
private const val RESUME_AFTER_APPROVAL_TYPE = "resume_after_approval"
private const val COMMAND_EXPIRED_CODE = "COMMAND_EXPIRED"
