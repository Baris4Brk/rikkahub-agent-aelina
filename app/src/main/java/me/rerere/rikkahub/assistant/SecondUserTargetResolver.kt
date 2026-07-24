package me.rerere.rikkahub.assistant

import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

const val DEFAULT_SYSTEM_ASSISTANT_OWNER_DISPLAY_NAME: String = "User"

/** Read-only settings seam for resolving the system assistant's fixed second-user target. */
fun interface SecondUserTargetSettingsReader {
    suspend fun read(): Settings
}

/**
 * Read-only conversation-identity seam.
 *
 * The target resolver needs only ownership metadata. Returning a full Conversation here would
 * make every overlay invocation deserialize the entire privileged history just to compare one ID.
 */
fun interface SecondUserTargetConversationReader {
    suspend fun findAssistantId(id: Uuid): Uuid?
}

/** Optional presentation metadata. Identity checks deliberately remain on the narrow reader. */
fun interface SecondUserTargetConversationTitleReader {
    suspend fun findTitle(id: Uuid): String?
}

sealed interface SecondUserTargetResolution {
    data class Resolved(
        val assistantId: Uuid,
        val conversationId: Uuid,
        val displayName: String,
        val assistantName: String,
        val conversationTitle: String = "",
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
    private val conversationTitleReader: SecondUserTargetConversationTitleReader =
        SecondUserTargetConversationTitleReader { null },
) {
    suspend fun resolve(): SecondUserTargetResolution {
        val settings = settingsReader.read()
        return resolveAssistant(settings, settings.systemAssistantTargetAssistantId)
    }

    /** Resolves a caller-selected assistant without ever falling back to global chat state. */
    suspend fun resolveAssistant(assistantId: Uuid?): SecondUserTargetResolution =
        resolveAssistant(settingsReader.read(), assistantId)

    suspend fun resolveAssistant(
        settings: Settings,
        assistantId: Uuid?,
    ): SecondUserTargetResolution {
        assistantId ?: return SecondUserTargetResolution.TargetNotSelected
        val assistant = settings.assistants.firstOrNull { it.id == assistantId }
            ?: return SecondUserTargetResolution.AssistantNotFound(assistantId)
        val conversationId = assistant.privilegedConversationId
            ?: return SecondUserTargetResolution.PrivilegedConversationNotConfigured(assistantId)
        val actualAssistantId = conversationReader.findAssistantId(conversationId)
            ?: return SecondUserTargetResolution.ConversationNotFound(
                assistantId = assistantId,
                conversationId = conversationId,
            )
        if (actualAssistantId != assistantId) {
            return SecondUserTargetResolution.ConversationAssistantMismatch(
                assistantId = assistantId,
                conversationId = conversationId,
                actualAssistantId = actualAssistantId,
            )
        }
        return SecondUserTargetResolution.Resolved(
            assistantId = assistantId,
            conversationId = conversationId,
            displayName = settings.displaySetting.userNickname.trim()
                .ifEmpty { DEFAULT_SYSTEM_ASSISTANT_OWNER_DISPLAY_NAME },
            assistantName = assistant.name.trim(),
            conversationTitle = conversationTitleReader.findTitle(conversationId).orEmpty().trim(),
        )
    }
}
