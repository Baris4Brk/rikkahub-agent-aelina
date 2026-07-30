package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.assistant.SecondUserAuthorityService
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

sealed interface ConversationDeletionResult {
    data class Deleted(val conversationId: Uuid) : ConversationDeletionResult
    data class RetainedSecondUser(val conversationId: Uuid) : ConversationDeletionResult
    data class Missing(val conversationId: Uuid) : ConversationDeletionResult
}

/** Result of a metadata/ownership update. Protected conversations can still receive their
 * ordinary message graph updates, but their assistant ownership cannot be reassigned. */
sealed interface ConversationUpdateResult {
    data class Updated(val conversationId: Uuid) : ConversationUpdateResult
    data class RetainedSecondUser(val conversationId: Uuid) : ConversationUpdateResult
    data class Missing(val conversationId: Uuid) : ConversationUpdateResult
}

data class ConversationBatchDeletionResult(
    val deleted: Int,
    val retained: List<Uuid>,
)

/** Central, repository-level policy: callers cannot bypass it through UI ordering. */
class ConversationDeletionPolicy(
    private val authorityService: SecondUserAuthorityService,
) {
    suspend fun canDelete(storedConversation: Conversation): Boolean =
        !authorityService.isDeletionProtected(storedConversation.id)

    suspend fun canReassignAssistant(storedConversation: Conversation): Boolean =
        !authorityService.isDeletionProtected(storedConversation.id)
}
