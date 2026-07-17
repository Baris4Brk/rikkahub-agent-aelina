package me.rerere.rikkahub.service.chat

import me.rerere.rikkahub.data.model.Conversation

data class ConversationSnapshot(
    val conversation: Conversation,
    val revision: Long,
)

sealed interface PersistResult {
    data object Persisted : PersistResult
    data object IgnoredOlderRevision : PersistResult
    data class Failed(val error: Throwable) : PersistResult
}

interface PersistenceCoordinator {
    suspend fun persistIfNewer(snapshot: ConversationSnapshot): PersistResult
    suspend fun flushThrough(revision: Long): PersistResult
}

class NoOpPersistenceCoordinator : PersistenceCoordinator {
    override suspend fun persistIfNewer(snapshot: ConversationSnapshot): PersistResult = PersistResult.Persisted
    override suspend fun flushThrough(revision: Long): PersistResult = PersistResult.Persisted
}
