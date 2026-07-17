package me.rerere.rikkahub.assistant

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

const val DEFAULT_SYSTEM_ASSISTANT_OWNER_DISPLAY_NAME: String = "User"

/** Read-only settings seam for resolving the system assistant's fixed second-user target. */
fun interface SecondUserTargetSettingsReader {
    suspend fun read(): Settings
}

/** Read-only conversation seam. Deliberately exposes no create or update operation. */
fun interface SecondUserTargetConversationReader {
    suspend fun getById(id: Uuid): Conversation?
}

sealed interface SecondUserTargetResolution {
    data class Resolved(
        val assistantId: Uuid,
        val conversationId: Uuid,
        val displayName: String,
        val assistantName: String,
    ) : SecondUserTargetResolution

    data object TargetNotSelected : SecondUserTargetResolution

    data class AssistantNotFound(
        val assistantId: Uuid,
    ) : SecondUserTargetResolution

    data class PrivilegedConversationNotConfigured(
        val assistantId: Uuid,
    ) : SecondUserTargetResolution

    data class ConversationNotFound(
        val assistantId: Uuid,
        val conversationId: Uuid,
    ) : SecondUserTargetResolution

    data class ConversationAssistantMismatch(
        val assistantId: Uuid,
        val conversationId: Uuid,
        val actualAssistantId: Uuid,
    ) : SecondUserTargetResolution
}

/**
 * Atomically resolves the configured system-assistant target from one settings snapshot.
 *
 * Resolution is intentionally read-only: a missing or stale target is reported to the caller
 * and never repaired by silently creating or reassigning a conversation.
 */
class SecondUserTargetResolver(
    private val settingsReader: SecondUserTargetSettingsReader,
    private val conversationReader: SecondUserTargetConversationReader,
) {
    suspend fun resolve(): SecondUserTargetResolution {
        val settings = settingsReader.read()
        val assistantId = settings.systemAssistantTargetAssistantId
            ?: return SecondUserTargetResolution.TargetNotSelected
        val assistant = settings.assistants.firstOrNull { it.id == assistantId }
            ?: return SecondUserTargetResolution.AssistantNotFound(assistantId)
        val conversationId = assistant.privilegedConversationId
            ?: return SecondUserTargetResolution.PrivilegedConversationNotConfigured(assistantId)
        val conversation = conversationReader.getById(conversationId)
            ?: return SecondUserTargetResolution.ConversationNotFound(
                assistantId = assistantId,
                conversationId = conversationId,
            )
        if (conversation.assistantId != assistantId) {
            return SecondUserTargetResolution.ConversationAssistantMismatch(
                assistantId = assistantId,
                conversationId = conversationId,
                actualAssistantId = conversation.assistantId,
            )
        }
        return SecondUserTargetResolution.Resolved(
            assistantId = assistantId,
            conversationId = conversationId,
            displayName = settings.displaySetting.userNickname.trim()
                .ifEmpty { DEFAULT_SYSTEM_ASSISTANT_OWNER_DISPLAY_NAME },
            assistantName = assistant.name.trim(),
        )
    }
}
