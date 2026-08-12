package me.rerere.rikkahub.data.authority.transaction

import me.rerere.rikkahub.data.db.entity.PendingChatCommandEntity
import me.rerere.rikkahub.service.chat.CommandAdmissionResult
import me.rerere.rikkahub.service.chat.CommandAuthorityMutationCommit
import me.rerere.rikkahub.service.chat.CommandClaim
import me.rerere.rikkahub.service.chat.CommandCompletionAuthority
import me.rerere.rikkahub.service.chat.CommandLineageFinishResult
import me.rerere.rikkahub.service.chat.CommandStateTransaction
import me.rerere.rikkahub.service.chat.CommandTransitionResult
import me.rerere.rikkahub.service.chat.DurableCommandState

/** Binds a fully encoded command row to combined admission without exposing payload to coordinator. */
class CommandStateAdmissionAuthorityAdapter(
    private val commands: CommandStateTransaction,
) {
    fun mutation(encodedDraft: PendingChatCommandEntity): CommandAdmissionAuthorityMutation =
        CommandAdmissionAuthorityMutation { draft, conversationSource ->
            requireEncodedAdmissionIdentity(encodedDraft, draft)
            val commit = commands.admitInCurrentTransaction(
                draft = encodedDraft.copy(
                    branchAnchorMessageRevision = draft.branchAnchorMessageRevision,
                    conversationSourceRevision = conversationSource.sourceRevision,
                    completionKind = null,
                    resultAssistantMessageId = null,
                    resultAssistantMessageRevision = null,
                ),
                conversationSourceRevision = conversationSource.sourceRevision,
            )
            val (row, duplicate) = when (val result = commit.result) {
                is CommandAdmissionResult.Inserted -> result.row to false
                is CommandAdmissionResult.AlreadyExists -> result.row to true
                is CommandAdmissionResult.DedupeHit -> throw AuthorityTransactionConflictException(
                    "COMMAND_ADMISSION_DEDUPE_HIT",
                )
                is CommandAdmissionResult.Invalid -> throw AuthorityTransactionConflictException(
                    result.code,
                )
                is CommandAdmissionResult.Conflict -> throw AuthorityTransactionConflictException(
                    result.code,
                )
            }
            CommandAuthorityMutationReceipt(
                commandId = row.id,
                conversationId = row.conversationId,
                stateVersion = row.stateVersion,
                state = DurableCommandState.PENDING,
                completion = null,
                conversationSource = conversationSource,
                insertedOutbox = commit.insertedOutbox,
                duplicate = duplicate,
            )
        }
}

/** Production adapter from the combined coordinators to the sole command authority writer. */
class CommandStateCompletionAuthorityAdapter(
    private val commands: CommandStateTransaction,
) : CommandCompletionAuthorityPort {
    override suspend fun markWaitingInCurrentTransaction(
        claim: CommandClaim,
        completion: CommandCompletionAuthority,
        conversationSource: ConversationSourceAuthorityLink,
    ): CommandAuthorityMutationReceipt = commands.markWaitingApprovalInCurrentTransaction(
        claim = claim,
        completion = completion,
        conversationSourceRevision = conversationSource.sourceRevision,
    ).toTransitionReceipt(completion, conversationSource)

    override suspend fun finishClaimedInCurrentTransaction(
        claim: CommandClaim,
        completion: CommandCompletionAuthority,
        conversationSource: ConversationSourceAuthorityLink?,
        errorCode: String?,
        terminalizeWaitingLineage: Boolean,
    ): CommandAuthorityMutationReceipt = if (terminalizeWaitingLineage) {
        commands.finishClaimedAndWaitingLineageInCurrentTransaction(
            claim = claim,
            completion = completion,
            conversationSourceRevision = conversationSource?.sourceRevision,
            errorCode = errorCode,
        ).toLineageReceipt(completion, conversationSource)
    } else {
        commands.finishClaimedInCurrentTransaction(
            claim = claim,
            completion = completion,
            conversationSourceRevision = conversationSource?.sourceRevision,
            errorCode = errorCode,
        ).toTransitionReceipt(completion, conversationSource)
    }

    override suspend fun finishUnclaimedInCurrentTransaction(
        commandId: String,
        completion: CommandCompletionAuthority,
        conversationSource: ConversationSourceAuthorityLink?,
        errorCode: String?,
    ): CommandAuthorityMutationReceipt = commands.finishUnclaimedInCurrentTransaction(
        id = parseAuthorityUuid(commandId),
        completion = completion,
        conversationSourceRevision = conversationSource?.sourceRevision,
        errorCode = errorCode,
    ).toTransitionReceipt(completion, conversationSource)

    override fun dispatchPostCommit(insertedOutbox: Boolean) {
        commands.dispatchExternalPostCommit(insertedOutbox)
    }
}

private fun CommandAuthorityMutationCommit<CommandTransitionResult>.toTransitionReceipt(
    completion: CommandCompletionAuthority,
    conversationSource: ConversationSourceAuthorityLink?,
): CommandAuthorityMutationReceipt {
    val (row, duplicate) = when (val observed = result) {
        is CommandTransitionResult.Applied -> observed.row to false
        is CommandTransitionResult.Duplicate -> observed.row to true
        is CommandTransitionResult.Conflict -> throw AuthorityTransactionConflictException(
            "COMMAND_COMPLETION_CAS_CONFLICT",
        )
        is CommandTransitionResult.Renewed -> error("Completion adapter received a lease renewal")
    }
    return CommandAuthorityMutationReceipt(
        commandId = row.id,
        conversationId = row.conversationId,
        stateVersion = row.stateVersion,
        state = completion.commandState,
        completion = completion,
        conversationSource = conversationSource,
        insertedOutbox = insertedOutbox,
        duplicate = duplicate,
    )
}

private fun CommandAuthorityMutationCommit<CommandLineageFinishResult>.toLineageReceipt(
    completion: CommandCompletionAuthority,
    conversationSource: ConversationSourceAuthorityLink?,
): CommandAuthorityMutationReceipt {
    val (row, ids, duplicate) = when (val observed = result) {
        is CommandLineageFinishResult.Applied ->
            Triple(observed.claimedRow, observed.terminalizedCommandIds, false)
        is CommandLineageFinishResult.Duplicate ->
            Triple(observed.claimedRow, observed.terminalizedCommandIds, true)
        is CommandLineageFinishResult.Conflict -> throw AuthorityTransactionConflictException(
            observed.code,
        )
    }
    return CommandAuthorityMutationReceipt(
        commandId = row.id,
        conversationId = row.conversationId,
        stateVersion = row.stateVersion,
        state = completion.commandState,
        completion = completion,
        conversationSource = conversationSource,
        insertedOutbox = insertedOutbox,
        duplicate = duplicate,
        terminalizedCommandIds = ids.map { it.toString() },
    )
}

private fun parseAuthorityUuid(raw: String): kotlin.uuid.Uuid = runCatching {
    kotlin.uuid.Uuid.parse(raw)
}.getOrElse {
    throw AuthorityTransactionConflictException("COMMAND_AUTHORITY_ID_INVALID")
}

private fun requireEncodedAdmissionIdentity(
    encoded: PendingChatCommandEntity,
    authority: CommandAdmissionAuthorityDraft,
) {
    require(
        encoded.id == authority.commandId &&
            encoded.conversationId == authority.conversationId &&
            encoded.assistantIdSnapshot == authority.assistantIdSnapshot &&
            encoded.authoritySubjectId == authority.authoritySubjectId &&
            encoded.lineageId == authority.lineageId &&
            encoded.parentCommandId == authority.parentCommandId &&
            encoded.branchAnchorMessageId == authority.branchAnchorMessageId,
    ) { "Encoded command admission identity differs from authority draft" }
}
