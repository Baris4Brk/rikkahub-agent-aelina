package me.rerere.rikkahub.memory

interface MemoryMutationCoordinator {
    suspend fun mutate(command: MemoryMutationCommand): MemoryMutationResult

    suspend fun invalidateSources(
        batch: MemorySourceInvalidationBatch,
        nowMs: Long,
    ): Int {
        var affected = 0
        batch.scopes.forEach { request ->
            affected += if (request.invalidateWholeConversation) {
                invalidateSourceConversation(request.scopeId, batch.conversationId, nowMs)
            } else {
                invalidateSourceMessages(
                    request.scopeId,
                    batch.conversationId,
                    request.removedMessageIds,
                    nowMs,
                ) + invalidateSourceVersions(
                    request.scopeId,
                    batch.conversationId,
                    request.removedSourceVersions,
                    nowMs,
                )
            }
        }
        return affected
    }

    suspend fun invalidateSourceConversation(scopeId: String, conversationId: String): Int

    suspend fun invalidateSourceConversation(
        scopeId: String,
        conversationId: String,
        nowMs: Long,
    ): Int = invalidateSourceConversation(scopeId, conversationId)

    suspend fun invalidateSourceMessages(
        scopeId: String,
        conversationId: String,
        messageIds: Set<String>,
    ): Int

    suspend fun invalidateSourceMessages(
        scopeId: String,
        conversationId: String,
        messageIds: Set<String>,
        nowMs: Long,
    ): Int = invalidateSourceMessages(scopeId, conversationId, messageIds)

    suspend fun invalidateSourceVersions(
        scopeId: String,
        conversationId: String,
        sourceVersions: Set<MemorySourceVersion>,
        nowMs: Long,
    ): Int = invalidateSourceMessages(
        scopeId = scopeId,
        conversationId = conversationId,
        messageIds = sourceVersions.mapTo(mutableSetOf(), MemorySourceVersion::messageId),
        nowMs = nowMs,
    )

    suspend fun runRetention(): Int

    suspend fun purgeScope(scopeId: String): Int
}

class DefaultMemoryMutationCoordinator(
    private val store: MemoryProcessingStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : MemoryMutationCoordinator {
    override suspend fun mutate(command: MemoryMutationCommand): MemoryMutationResult =
        store.mutate(command, nowMs())

    override suspend fun invalidateSources(
        batch: MemorySourceInvalidationBatch,
        nowMs: Long,
    ): Int = store.invalidateSources(batch, nowMs)

    override suspend fun invalidateSourceConversation(scopeId: String, conversationId: String): Int =
        invalidateSourceConversation(scopeId, conversationId, nowMs())

    override suspend fun invalidateSourceConversation(
        scopeId: String,
        conversationId: String,
        nowMs: Long,
    ): Int = store.invalidateSourceConversation(scopeId, conversationId, nowMs)

    override suspend fun invalidateSourceMessages(
        scopeId: String,
        conversationId: String,
        messageIds: Set<String>,
    ): Int = invalidateSourceMessages(scopeId, conversationId, messageIds, nowMs())

    override suspend fun invalidateSourceMessages(
        scopeId: String,
        conversationId: String,
        messageIds: Set<String>,
        nowMs: Long,
    ): Int = store.invalidateSourceMessages(scopeId, conversationId, messageIds, nowMs)

    override suspend fun invalidateSourceVersions(
        scopeId: String,
        conversationId: String,
        sourceVersions: Set<MemorySourceVersion>,
        nowMs: Long,
    ): Int = store.invalidateSourceVersions(scopeId, conversationId, sourceVersions, nowMs)

    override suspend fun runRetention(): Int = store.runRetention(nowMs())

    override suspend fun purgeScope(scopeId: String): Int = store.purgeScope(scopeId, nowMs())
}
