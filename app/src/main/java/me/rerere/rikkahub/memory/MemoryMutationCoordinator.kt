package me.rerere.rikkahub.memory

interface MemoryMutationCoordinator {
    suspend fun mutate(command: MemoryMutationCommand): MemoryMutationResult

    suspend fun invalidateSourceConversation(scopeId: String, conversationId: String): Int

    suspend fun invalidateSourceMessages(
        scopeId: String,
        conversationId: String,
        messageIds: Set<String>,
    ): Int

    suspend fun runRetention(): Int

    suspend fun purgeScope(scopeId: String): Int
}

class DefaultMemoryMutationCoordinator(
    private val store: MemoryProcessingStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : MemoryMutationCoordinator {
    override suspend fun mutate(command: MemoryMutationCommand): MemoryMutationResult =
        store.mutate(command, nowMs())

    override suspend fun invalidateSourceConversation(scopeId: String, conversationId: String): Int =
        store.invalidateSourceConversation(scopeId, conversationId, nowMs())

    override suspend fun invalidateSourceMessages(
        scopeId: String,
        conversationId: String,
        messageIds: Set<String>,
    ): Int = store.invalidateSourceMessages(scopeId, conversationId, messageIds, nowMs())

    override suspend fun runRetention(): Int = store.runRetention(nowMs())

    override suspend fun purgeScope(scopeId: String): Int = store.purgeScope(scopeId, nowMs())
}
