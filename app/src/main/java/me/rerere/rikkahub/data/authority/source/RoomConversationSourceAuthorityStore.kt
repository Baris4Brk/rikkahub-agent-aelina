package me.rerere.rikkahub.data.authority.source

import me.rerere.rikkahub.data.db.dao.LearningSourceAuthorityDao
import me.rerere.rikkahub.data.db.entity.LearningConversationSourceAuthorityEntity
import me.rerere.rikkahub.data.db.entity.LearningMessageSourceAuthorityEntity

/** Thin adapter over the main AppDatabase DAO; it never opens a transaction. */
class RoomConversationSourceAuthorityStore(
    private val dao: LearningSourceAuthorityDao,
    private val isInAuthorityTransaction: () -> Boolean,
) : ConversationSourceAuthorityStore {
    override suspend fun findConversation(
        scope: ConversationSourceScope,
        conversationId: String,
    ): ConversationSourceAuthorityHead? {
        requireTransaction()
        return dao.findConversation(scope.kind.name, scope.id, conversationId)?.toContract()
    }

    override suspend fun insertConversationInitial(
        head: ConversationSourceAuthorityHead,
    ): Boolean {
        requireTransaction()
        require(head.sourceRevision == 1L && head.previousSourceRevision == null)
        return dao.insertConversationInitialIgnore(head.toEntity()) != -1L
    }

    override suspend fun updateConversationFenced(
        expectedRevision: Long,
        head: ConversationSourceAuthorityHead,
    ): Boolean {
        requireTransaction()
        require(head.previousSourceRevision == expectedRevision)
        require(head.sourceRevision == expectedRevision + 1L)
        return dao.updateConversationFenced(
            scopeKind = head.scope.kind.name,
            scopeId = head.scope.id,
            conversationId = head.conversationId,
            expectedRevision = expectedRevision,
            nextRevision = head.sourceRevision,
            assistantIdSnapshot = head.assistantIdSnapshot,
            sourceState = head.sourceState.name,
            changeKind = head.changeKind.name,
            branchHeadMessageId = head.branchHeadMessageId,
            branchHeadMessageRevision = head.branchHeadMessageRevision,
            occurredAtMs = head.occurredAtMs,
            updatedAtMs = head.updatedAtMs,
        ) == 1
    }

    override suspend fun countConversationScopes(conversationId: String): Int {
        requireTransaction()
        return dao.countConversationScopes(conversationId)
    }

    override suspend fun listConversationScopesAfter(
        conversationId: String,
        afterScopeKind: String,
        afterScopeId: String,
        limit: Int,
    ): List<ConversationSourceAuthorityHead> {
        requireTransaction()
        require(limit in 1..512)
        return dao.listConversationScopesAfter(
            conversationId = conversationId,
            afterScopeKind = afterScopeKind,
            afterScopeId = afterScopeId,
            limit = limit,
        ).map(LearningConversationSourceAuthorityEntity::toContract)
    }

    override suspend fun findMessage(
        scope: ConversationSourceScope,
        messageId: String,
    ): MessageSourceAuthorityHead? {
        requireTransaction()
        return dao.findMessage(scope.kind.name, scope.id, messageId)?.toContract()
    }

    override suspend fun insertMessageInitial(head: MessageSourceAuthorityHead): Boolean {
        requireTransaction()
        require(head.sourceRevision == 1L && head.previousSourceRevision == null)
        return dao.insertMessageInitialIgnore(head.toEntity()) != -1L
    }

    override suspend fun updateMessageFenced(
        expectedRevision: Long,
        head: MessageSourceAuthorityHead,
    ): Boolean {
        requireTransaction()
        require(head.previousSourceRevision == expectedRevision)
        require(head.sourceRevision == expectedRevision + 1L)
        return dao.updateMessageFenced(
            scopeKind = head.scope.kind.name,
            scopeId = head.scope.id,
            conversationId = head.conversationId,
            messageId = head.messageId,
            expectedRevision = expectedRevision,
            nextRevision = head.sourceRevision,
            messageRole = head.messageRole,
            sourceState = head.sourceState.name,
            changeKind = head.changeKind.name,
            payloadIntegritySha256 = head.payloadIntegritySha256,
            occurredAtMs = head.occurredAtMs,
            updatedAtMs = head.updatedAtMs,
        ) == 1
    }

    override suspend fun countMessagesForConversation(
        scope: ConversationSourceScope,
        conversationId: String,
    ): Int {
        requireTransaction()
        return dao.countMessagesForConversation(scope.kind.name, scope.id, conversationId)
    }

    override suspend fun listMessagesForConversationAfter(
        scope: ConversationSourceScope,
        conversationId: String,
        afterMessageId: String,
        limit: Int,
    ): List<MessageSourceAuthorityHead> {
        requireTransaction()
        require(limit in 1..512)
        return dao.listMessagesForConversationAfter(
            scopeKind = scope.kind.name,
            scopeId = scope.id,
            conversationId = conversationId,
            afterMessageId = afterMessageId,
            limit = limit,
        ).map(LearningMessageSourceAuthorityEntity::toContract)
    }

    private fun requireTransaction() {
        check(isInAuthorityTransaction()) { "conversation_source_authority_transaction_required" }
    }
}

private fun LearningConversationSourceAuthorityEntity.toContract() =
    ConversationSourceAuthorityHead(
        scope = ConversationSourceScope(
            kind = ConversationSourceScopeKind.valueOf(scopeKind),
            id = scopeId,
        ),
        conversationId = conversationId,
        assistantIdSnapshot = assistantIdSnapshot,
        sourceRevision = sourceRevision,
        previousSourceRevision = previousSourceRevision,
        sourceState = ConversationSourceState.valueOf(sourceState),
        changeKind = ConversationSourceChangeKind.valueOf(changeKind),
        branchHeadMessageId = branchHeadMessageId,
        branchHeadMessageRevision = branchHeadMessageRevision,
        occurredAtMs = occurredAtMs,
        updatedAtMs = updatedAtMs,
    )

private fun ConversationSourceAuthorityHead.toEntity() =
    LearningConversationSourceAuthorityEntity(
        scopeKind = scope.kind.name,
        scopeId = scope.id,
        conversationId = conversationId,
        assistantIdSnapshot = assistantIdSnapshot,
        sourceRevision = sourceRevision,
        previousSourceRevision = previousSourceRevision,
        sourceState = sourceState.name,
        changeKind = changeKind.name,
        branchHeadMessageId = branchHeadMessageId,
        branchHeadMessageRevision = branchHeadMessageRevision,
        occurredAtMs = occurredAtMs,
        updatedAtMs = updatedAtMs,
    )

private fun LearningMessageSourceAuthorityEntity.toContract() =
    MessageSourceAuthorityHead(
        scope = ConversationSourceScope(
            kind = ConversationSourceScopeKind.valueOf(scopeKind),
            id = scopeId,
        ),
        conversationId = conversationId,
        messageId = messageId,
        messageRole = messageRole,
        sourceRevision = sourceRevision,
        previousSourceRevision = previousSourceRevision,
        sourceState = ConversationSourceState.valueOf(sourceState),
        changeKind = ConversationSourceChangeKind.valueOf(changeKind),
        payloadIntegritySha256 = payloadIntegritySha256,
        occurredAtMs = occurredAtMs,
        updatedAtMs = updatedAtMs,
    )

private fun MessageSourceAuthorityHead.toEntity() =
    LearningMessageSourceAuthorityEntity(
        scopeKind = scope.kind.name,
        scopeId = scope.id,
        conversationId = conversationId,
        messageId = messageId,
        messageRole = messageRole,
        sourceRevision = sourceRevision,
        previousSourceRevision = previousSourceRevision,
        sourceState = sourceState.name,
        changeKind = changeKind.name,
        payloadIntegritySha256 = payloadIntegritySha256,
        occurredAtMs = occurredAtMs,
        updatedAtMs = updatedAtMs,
    )
