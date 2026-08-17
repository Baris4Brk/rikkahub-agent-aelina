package me.rerere.rikkahub.data.authority.transaction

import me.rerere.rikkahub.data.authority.source.ConversationSourceAuthorityCommit
import me.rerere.rikkahub.data.authority.source.ConversationSourceAuthorityWriter
import me.rerere.rikkahub.data.authority.source.ConversationSourceScope
import me.rerere.rikkahub.data.authority.source.ConversationSourceScopeKind
import me.rerere.rikkahub.data.authority.source.ConversationSourceSnapshot
import me.rerere.rikkahub.service.chat.CommandCompletionAuthority
import me.rerere.rikkahub.service.chat.CommandCompletionKind
import me.rerere.rikkahub.service.chat.CommandCompletionPhase
import me.rerere.rikkahub.service.chat.CommandResultMessageAuthority
import me.rerere.rikkahub.service.chat.CommandClaim
import me.rerere.rikkahub.service.chat.CommandTransactionRunner
import me.rerere.rikkahub.service.chat.DurableCommandState
import me.rerere.rikkahub.service.chat.isTerminal

/** Reuses the single main-database transaction runner already owned by command authority. */
typealias AuthorityTransactionRunner = CommandTransactionRunner

/** Captures the exact raw Conversation write without exposing its content to this coordinator. */
fun interface ConversationGraphAuthorityMutation {
    /** Returns the content-free snapshot derived from the exact graph just persisted. */
    suspend fun persistInCurrentTransaction(): ConversationSourceSnapshot
}

data class TransientConversationFinalizationAuthorityCommit(
    val sources: List<ConversationSourceAuthorityCommit>,
) {
    init {
        require(sources.isNotEmpty()) {
            "Transient Conversation finalization requires at least its default source scope"
        }
    }

    override fun toString(): String =
        "TransientConversationFinalizationAuthorityCommit(scopes=${sources.size}, " +
            "mutated=${sources.count(ConversationSourceAuthorityCommit::didMutate)}, " +
            "outbox=${sources.any(ConversationSourceAuthorityCommit::insertedOutbox)})"
}

/**
 * Fallback finalizer for a regeneration that predates durable command authority.
 *
 * The graph mutation owns any legacy source invalidation that must precede persistence and returns
 * the exact content-free snapshot of that persisted graph. Every known source-authority scope and
 * every resulting invalidation outbox row are then reconciled before the one outer transaction can
 * commit. Dispatch is deliberately coalesced and happens only after that transaction succeeds.
 */
class TransientConversationFinalizationAuthorityCoordinator(
    private val transactions: AuthorityTransactionRunner,
    private val sources: ConversationSourceAuthorityWriter,
) {
    suspend fun finish(
        graphMutation: ConversationGraphAuthorityMutation,
    ): TransientConversationFinalizationAuthorityCommit {
        val commit = transactions.inTransaction {
            val conversation = graphMutation.persistInCurrentTransaction()
            TransientConversationFinalizationAuthorityCommit(
                sources = sources.reconcileAllKnownScopesInCurrentTransaction(conversation),
            )
        }
        sources.dispatchPostCommit(commit.sources)
        return commit
    }
}

/** Captures the full durable command row while exposing only its authority receipt here. */
fun interface CommandAdmissionAuthorityMutation {
    suspend fun admitInCurrentTransaction(
        draft: CommandAdmissionAuthorityDraft,
        conversationSource: ConversationSourceAuthorityLink,
    ): CommandAuthorityMutationReceipt
}

/**
 * Command authority mutation port. Implementations adapt CommandStateTransaction's fenced
 * in-current-transaction APIs; they never open another transaction or publish post-commit work.
 */
interface CommandCompletionAuthorityPort {
    suspend fun markWaitingInCurrentTransaction(
        claim: CommandClaim,
        completion: CommandCompletionAuthority,
        conversationSource: ConversationSourceAuthorityLink,
    ): CommandAuthorityMutationReceipt

    suspend fun finishClaimedInCurrentTransaction(
        claim: CommandClaim,
        completion: CommandCompletionAuthority,
        conversationSource: ConversationSourceAuthorityLink?,
        errorCode: String?,
        terminalizeWaitingLineage: Boolean,
    ): CommandAuthorityMutationReceipt

    suspend fun finishUnclaimedInCurrentTransaction(
        commandId: String,
        completion: CommandCompletionAuthority,
        conversationSource: ConversationSourceAuthorityLink?,
        errorCode: String?,
    ): CommandAuthorityMutationReceipt

    /** Called only after the owning transaction returned successfully. */
    fun dispatchPostCommit(insertedOutbox: Boolean)
}

data class CommandAdmissionAuthorityDraft(
    val commandId: String,
    val conversationId: String,
    val assistantIdSnapshot: String,
    val authoritySubjectId: String?,
    val lineageId: String,
    val parentCommandId: String?,
    val branchAnchorMessageId: String,
    val branchAnchorMessageRevision: Long,
) {
    init {
        listOf(
            commandId,
            conversationId,
            assistantIdSnapshot,
            lineageId,
            branchAnchorMessageId,
        ).forEach { requireAuthorityId(it) }
        authoritySubjectId?.let(::requireAuthorityId)
        parentCommandId?.let(::requireAuthorityId)
        require(branchAnchorMessageRevision > 0L) {
            "Command admission requires an authoritative branch anchor revision"
        }
        require((parentCommandId == null) == (lineageId == commandId)) {
            "Only a root command may own its lineage ID"
        }
    }

    override fun toString(): String =
        "CommandAdmissionAuthorityDraft(root=${parentCommandId == null}, " +
            "anchorRevision=$branchAnchorMessageRevision, subject=${authoritySubjectId != null}, " +
            "ids=<redacted>)"
}

data class CommandAuthorityMutationReceipt(
    val commandId: String,
    val conversationId: String,
    val stateVersion: Long,
    val state: DurableCommandState,
    val completion: CommandCompletionAuthority?,
    val conversationSource: ConversationSourceAuthorityLink?,
    val insertedOutbox: Boolean,
    val duplicate: Boolean,
    val terminalizedCommandIds: List<String> = if (state.isTerminal) {
        listOf(commandId)
    } else {
        emptyList()
    },
) {
    init {
        requireAuthorityId(commandId)
        requireAuthorityId(conversationId)
        terminalizedCommandIds.forEach(::requireAuthorityId)
        require(stateVersion > 0L)
        require(terminalizedCommandIds.toSet().size == terminalizedCommandIds.size)
        require(
            if (state.isTerminal) {
                commandId in terminalizedCommandIds
            } else {
                terminalizedCommandIds.isEmpty()
            },
        ) { "Terminalized command IDs do not match the receipt state" }
        require(!state.isTerminal || completion != null) {
            "Terminal command receipt requires a completion meaning"
        }
        require(completion == null || completion.commandState == state)
    }

    val conversationSourceRevision: Long?
        get() = conversationSource?.sourceRevision

    override fun toString(): String =
        "CommandAuthorityMutationReceipt(version=$stateVersion, state=$state, " +
        "completion=${completion?.kind}, conversationRevision=$conversationSourceRevision, duplicate=$duplicate, " +
            "terminalized=${terminalizedCommandIds.size}, outbox=$insertedOutbox, ids=<redacted>)"
}

data class ConversationCommandAuthorityCommit(
    val source: ConversationSourceAuthorityCommit,
    val command: CommandAuthorityMutationReceipt,
) {
    val insertedOutbox: Boolean
        get() = source.insertedOutbox || command.insertedOutbox

    override fun toString(): String =
        "ConversationCommandAuthorityCommit(source=$source, command=$command)"
}

class AuthorityTransactionConflictException(
    val reasonCode: String,
) : IllegalStateException(reasonCode)

data class ConversationSourceAuthorityLink(
    val scope: ConversationSourceScope,
    val conversationId: String,
    val sourceRevision: Long,
) {
    init {
        requireAuthorityId(conversationId)
        require(sourceRevision > 0L)
    }

    override fun toString(): String =
        "ConversationSourceAuthorityLink(scope=${scope.kind}, revision=$sourceRevision, id=<redacted>)"
}

/**
 * Persists the root user message/source revision and command admission as one decision.
 *
 * The command ID/lineage may be allocated before this call, but neither becomes durable unless the
 * exact branch anchor is present and ACTIVE in this transaction's Conversation graph.
 */
class CommandAdmissionAuthorityCoordinator(
    private val transactions: AuthorityTransactionRunner,
    private val sources: ConversationSourceAuthorityWriter,
    private val commands: CommandCompletionAuthorityPort,
) {
    suspend fun activeAnchorRevision(
        scope: ConversationSourceScope,
        messageId: String,
    ): Long? = transactions.inTransaction {
        sources.findActiveMessageInCurrentTransaction(scope, messageId)?.sourceRevision
    }

    suspend fun admit(
        command: CommandAdmissionAuthorityDraft,
        graphMutation: ConversationGraphAuthorityMutation,
        commandMutation: CommandAdmissionAuthorityMutation,
    ): ConversationCommandAuthorityCommit {
        val commit = transactions.inTransaction {
            val conversation = graphMutation.persistInCurrentTransaction()
            require(command.conversationId == conversation.conversationId) {
                "Command admission Conversation mismatch"
            }
            require(command.assistantIdSnapshot == conversation.assistantIdSnapshot) {
                "Command admission assistant mismatch"
            }
            val sourceCommit = sources.reconcileInCurrentTransaction(conversation)
            val sourceLink = sourceCommit.conversation.toAuthorityLink()
            val expectedScope = command.authoritySubjectId?.let { subjectId ->
                ConversationSourceScope(ConversationSourceScopeKind.AUTHORITY_SUBJECT, subjectId)
            } ?: ConversationSourceScope(
                ConversationSourceScopeKind.ASSISTANT,
                command.assistantIdSnapshot,
            )
            if (sourceLink.scope != expectedScope) {
                throw AuthorityTransactionConflictException("COMMAND_SOURCE_SCOPE_CONFLICT")
            }
            val anchor = sourceCommit.requireActiveMessage(
                command.branchAnchorMessageId,
                expectedRole = "USER",
            )
            if (anchor.sourceRevision != command.branchAnchorMessageRevision) {
                throw AuthorityTransactionConflictException("COMMAND_BRANCH_ANCHOR_REVISION_CONFLICT")
            }
            val commandReceipt = commandMutation.admitInCurrentTransaction(
                command,
                conversationSource = sourceLink,
            )
            if (commandReceipt.commandId != command.commandId ||
                commandReceipt.conversationId != command.conversationId ||
                commandReceipt.state != DurableCommandState.PENDING ||
                commandReceipt.completion != null ||
                commandReceipt.conversationSource != sourceLink
            ) {
                throw AuthorityTransactionConflictException("COMMAND_ADMISSION_RECEIPT_CONFLICT")
            }
            ConversationCommandAuthorityCommit(sourceCommit, commandReceipt)
        }
        dispatchPostCommit(commit)
        return commit
    }

    private fun dispatchPostCommit(commit: ConversationCommandAuthorityCommit) {
        sources.dispatchPostCommit(commit.source)
        commands.dispatchPostCommit(commit.command.insertedOutbox)
    }
}

data class ApprovalBarrierAuthorityReceipt(
    val insertedOutbox: Boolean = false,
    val postCommit: suspend () -> Unit = {},
)

/** Redacted approval/execution writer owned by SecondUserApprovalLifecycle. */
fun interface ApprovalBarrierAuthorityMutation {
    suspend fun persistInCurrentTransaction(
        assistantMessage: CommandResultMessageAuthority,
    ): ApprovalBarrierAuthorityReceipt
}

data class WaitingApprovalAuthorityCommit(
    val source: ConversationSourceAuthorityCommit,
    val approval: ApprovalBarrierAuthorityReceipt,
    val command: CommandAuthorityMutationReceipt,
) {
    override fun toString(): String =
        "WaitingApprovalAuthorityCommit(source=$source, command=$command, " +
            "approvalOutbox=${approval.insertedOutbox})"
}

/** Commits the tool-bearing graph, source revision, approval/execution rows and command WAITING. */
class WaitingApprovalAuthorityCoordinator(
    private val transactions: AuthorityTransactionRunner,
    private val sources: ConversationSourceAuthorityWriter,
    private val commands: CommandCompletionAuthorityPort,
) {
    suspend fun checkpoint(
        claim: CommandClaim,
        ownerCommandId: String,
        assistantMessageId: String,
        graphMutation: ConversationGraphAuthorityMutation,
        approvalMutation: ApprovalBarrierAuthorityMutation,
    ): WaitingApprovalAuthorityCommit {
        requireAuthorityId(ownerCommandId)
        require(claim.commandId.toString() == ownerCommandId) {
            "WAITING claim does not own the command"
        }
        val commit = transactions.inTransaction {
            val conversation = graphMutation.persistInCurrentTransaction()
            val sourceCommit = sources.reconcileInCurrentTransaction(conversation)
            val sourceLink = sourceCommit.conversation.toAuthorityLink()
            val assistant = sourceCommit.requireActiveMessage(
                assistantMessageId,
                expectedRole = "ASSISTANT",
            )
            val result = CommandResultMessageAuthority(
                messageId = assistant.messageId,
                messageRevision = assistant.sourceRevision,
            )
            val approvalReceipt = approvalMutation.persistInCurrentTransaction(
                assistantMessage = result,
            )
            val completion = CommandCompletionAuthority(
                kind = CommandCompletionKind.GENERATION_WAITING_APPROVAL,
                phase = CommandCompletionPhase.WAITING,
                commandState = DurableCommandState.WAITING_APPROVAL,
                resultMessage = result,
            )
            val commandReceipt = commands.markWaitingInCurrentTransaction(
                claim,
                completion,
                conversationSource = sourceLink,
            )
            if (commandReceipt.commandId != ownerCommandId ||
                commandReceipt.conversationId != conversation.conversationId ||
                commandReceipt.state != DurableCommandState.WAITING_APPROVAL ||
                commandReceipt.completion != completion ||
                commandReceipt.conversationSource != sourceLink
            ) {
                throw AuthorityTransactionConflictException("COMMAND_WAITING_RECEIPT_CONFLICT")
            }
            WaitingApprovalAuthorityCommit(sourceCommit, approvalReceipt, commandReceipt)
        }
        sources.dispatchPostCommit(commit.source)
        commands.dispatchPostCommit(commit.command.insertedOutbox)
        commit.approval.postCommit()
        return commit
    }
}

data class CommandFinalAuthorityRequest(
    val claim: CommandClaim,
    val commandId: String,
    val conversationId: String,
    val terminalState: DurableCommandState,
    val completionKind: CommandCompletionKind,
    val resultAssistantMessageId: String?,
    val errorCode: String?,
    val terminalizeWaitingLineage: Boolean,
) {
    init {
        requireAuthorityId(commandId)
        requireAuthorityId(conversationId)
        resultAssistantMessageId?.let(::requireAuthorityId)
        require(claim.commandId.toString() == commandId)
        require(terminalState.isTerminal)
        require(completionKind != CommandCompletionKind.GENERATION_WAITING_APPROVAL)
        require(errorCode == null || errorCode.matches(Regex("[A-Z][A-Z0-9_]{0,63}")))
    }

    override fun toString(): String =
        "CommandFinalAuthorityRequest(state=$terminalState, kind=$completionKind, " +
            "result=${resultAssistantMessageId != null}, lineage=$terminalizeWaitingLineage, " +
            "ids=<redacted>)"
}

data class FinalConversationAuthorityCommit(
    val source: ConversationSourceAuthorityCommit,
    val command: CommandAuthorityMutationReceipt,
) {
    override fun toString(): String =
        "FinalConversationAuthorityCommit(source=$source, command=$command)"
}

fun interface FinalResultAuthorityMutation {
    suspend fun persistInCurrentTransaction(
        assistantMessage: CommandResultMessageAuthority?,
    )
}

private val NoOpFinalResultAuthorityMutation = FinalResultAuthorityMutation { }

/**
 * Final graph/source and command terminal authority commit. A clean model result requires the exact
 * assistant message ID/revision resolved from this same transaction.
 */
class FinalConversationAuthorityCoordinator(
    private val transactions: AuthorityTransactionRunner,
    private val sources: ConversationSourceAuthorityWriter,
    private val commands: CommandCompletionAuthorityPort,
) {
    suspend fun finish(
        request: CommandFinalAuthorityRequest,
        graphMutation: ConversationGraphAuthorityMutation,
        resultMutation: FinalResultAuthorityMutation = NoOpFinalResultAuthorityMutation,
    ): FinalConversationAuthorityCommit {
        val commit = transactions.inTransaction {
            val conversation = graphMutation.persistInCurrentTransaction()
            if (conversation.conversationId != request.conversationId) {
                throw AuthorityTransactionConflictException("COMMAND_FINAL_CONVERSATION_CONFLICT")
            }
            val sourceCommit = sources.reconcileInCurrentTransaction(conversation)
            val sourceLink = sourceCommit.conversation.toAuthorityLink()
            val resultMessage = request.resultAssistantMessageId?.let { messageId ->
                val head = sourceCommit.requireActiveMessage(messageId, expectedRole = "ASSISTANT")
                CommandResultMessageAuthority(head.messageId, head.sourceRevision)
            }
            resultMutation.persistInCurrentTransaction(resultMessage)
            val completion = CommandCompletionAuthority(
                kind = request.completionKind,
                phase = CommandCompletionPhase.TERMINAL,
                commandState = request.terminalState,
                resultMessage = resultMessage,
            )
            val commandReceipt = commands.finishClaimedInCurrentTransaction(
                claim = request.claim,
                completion = completion,
                conversationSource = sourceLink,
                errorCode = request.errorCode,
                terminalizeWaitingLineage = request.terminalizeWaitingLineage,
            )
            if (commandReceipt.commandId != request.commandId ||
                commandReceipt.conversationId != request.conversationId ||
                commandReceipt.state != request.terminalState ||
                commandReceipt.completion != completion ||
                commandReceipt.conversationSource != sourceLink
            ) {
                throw AuthorityTransactionConflictException("COMMAND_FINAL_RECEIPT_CONFLICT")
            }
            FinalConversationAuthorityCommit(sourceCommit, commandReceipt)
        }
        sources.dispatchPostCommit(commit.source)
        commands.dispatchPostCommit(commit.command.insertedOutbox)
        return commit
    }

    /** Terminalizes an admitted command that never acquired a worker claim (queue/control gate). */
    suspend fun finishUnclaimed(
        commandId: String,
        conversationId: String,
        terminalState: DurableCommandState,
        completionKind: CommandCompletionKind,
        errorCode: String?,
        graphMutation: ConversationGraphAuthorityMutation,
    ): FinalConversationAuthorityCommit {
        requireAuthorityId(commandId)
        requireAuthorityId(conversationId)
        require(terminalState.isTerminal)
        val commit = transactions.inTransaction {
            val conversation = graphMutation.persistInCurrentTransaction()
            if (conversation.conversationId != conversationId) {
                throw AuthorityTransactionConflictException("COMMAND_FINAL_CONVERSATION_CONFLICT")
            }
            val sourceCommit = sources.reconcileInCurrentTransaction(conversation)
            val sourceLink = sourceCommit.conversation.toAuthorityLink()
            val completion = CommandCompletionAuthority(
                kind = completionKind,
                phase = CommandCompletionPhase.TERMINAL,
                commandState = terminalState,
                resultMessage = null,
            )
            val receipt = commands.finishUnclaimedInCurrentTransaction(
                commandId = commandId,
                completion = completion,
                conversationSource = sourceLink,
                errorCode = errorCode,
            )
            if (receipt.commandId != commandId || receipt.conversationId != conversationId ||
                receipt.state != terminalState || receipt.completion != completion ||
                receipt.conversationSource != sourceLink
            ) {
                throw AuthorityTransactionConflictException("COMMAND_FINAL_RECEIPT_CONFLICT")
            }
            FinalConversationAuthorityCommit(sourceCommit, receipt)
        }
        sources.dispatchPostCommit(commit.source)
        commands.dispatchPostCommit(commit.command.insertedOutbox)
        return commit
    }

    /**
     * Used only when the final graph transaction did not commit. It records an explicit UNKNOWN
     * outcome in a separate command transaction and is therefore incapable of carrying a result
     * assistant-message pair.
     */
    suspend fun finishAfterFinalSaveFailure(
        claim: CommandClaim,
        commandId: String,
        conversationId: String,
        errorCode: String = "FINAL_SAVE_FAILED",
        terminalizeWaitingLineage: Boolean = false,
    ): CommandAuthorityMutationReceipt {
        requireAuthorityId(commandId)
        requireAuthorityId(conversationId)
        require(claim.commandId.toString() == commandId) {
            "Final-save failure claim does not own the command"
        }
        require(errorCode.matches(Regex("[A-Z][A-Z0-9_]{0,63}")))
        val completion = CommandCompletionAuthority(
            kind = CommandCompletionKind.FAILED_FINAL_SAVE,
            phase = CommandCompletionPhase.TERMINAL,
            commandState = DurableCommandState.FAILED,
            resultMessage = null,
        )
        val receipt = transactions.inTransaction {
            val observed = commands.finishClaimedInCurrentTransaction(
                claim = claim,
                completion = completion,
                conversationSource = null,
                errorCode = errorCode,
                terminalizeWaitingLineage = terminalizeWaitingLineage,
            )
            if (observed.commandId != commandId ||
                observed.conversationId != conversationId ||
                observed.state != DurableCommandState.FAILED ||
                observed.completion != completion ||
                observed.conversationSource != null
            ) {
                throw AuthorityTransactionConflictException(
                    "COMMAND_FINAL_SAVE_FAILURE_RECEIPT_CONFLICT",
                )
            }
            observed
        }
        commands.dispatchPostCommit(receipt.insertedOutbox)
        return receipt
    }
}

private fun me.rerere.rikkahub.data.authority.source.ConversationSourceAuthorityHead
    .toAuthorityLink(): ConversationSourceAuthorityLink = ConversationSourceAuthorityLink(
    scope = scope,
    conversationId = conversationId,
    sourceRevision = sourceRevision,
)

private fun requireAuthorityId(value: String) {
    require(
        value.length in 1..256 && value.all { char ->
            char in 'a'..'z' ||
                char in 'A'..'Z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '_' ||
                char == '.' ||
                char == ':' ||
                char == '@'
        },
    ) { "Invalid authority identifier" }
}
