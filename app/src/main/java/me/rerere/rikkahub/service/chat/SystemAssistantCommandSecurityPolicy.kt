package me.rerere.rikkahub.service.chat

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import kotlin.uuid.Uuid

internal const val SYSTEM_ASSISTANT_KEYGUARD_REJECTION =
    "Unlock the device and invoke the system assistant again."
internal const val SYSTEM_ASSISTANT_TARGET_SNAPSHOT_REQUIRED_REJECTION =
    "The system-assistant command has no accepted target snapshot. Invoke it again."
internal const val SYSTEM_ASSISTANT_TARGET_CHANGED_REJECTION =
    "The selected system-assistant target changed before the command was accepted. Invoke it again."
internal const val SYSTEM_ASSISTANT_TARGET_ASSISTANT_MISSING_REJECTION =
    "The accepted system-assistant target no longer exists."
internal const val SYSTEM_ASSISTANT_TARGET_CONVERSATION_CHANGED_REJECTION =
    "The accepted assistant is no longer bound to this second-user conversation."
internal const val SYSTEM_ASSISTANT_TARGET_CONVERSATION_MISSING_REJECTION =
    "The accepted second-user conversation no longer exists."
internal const val SYSTEM_ASSISTANT_TARGET_CONVERSATION_MISMATCH_REJECTION =
    "The accepted second-user conversation belongs to a different assistant."
internal const val EMERGENCY_STOP_COMMAND_REJECTION =
    "Emergency Stop is active. Resume agent execution before submitting."

/** Emergency Stop is a command-admission floor, not merely a later tool-execution check. */
internal fun emergencyStopCommandBlockReason(
    active: Boolean,
    command: ChatCommand,
): String? = if (active && command !is StopCommand) {
    EMERGENCY_STOP_COMMAND_REJECTION
} else {
    null
}

internal sealed interface SystemAssistantTargetValidation {
    data class Valid(
        val assistant: Assistant,
        val conversation: Conversation,
    ) : SystemAssistantTargetValidation

    data class Invalid(
        val reason: String,
    ) : SystemAssistantTargetValidation
}

/**
 * Hard command-level floor for the native system-assistant surface.
 *
 * A keyguard invocation never becomes trusted later in its lifetime. The stop command remains
 * available because stopping work must take priority over every invocation-surface restriction;
 * interrupt-and-replace commands are deliberately rejected because they can start a model run.
 */
internal object SystemAssistantCommandSecurityPolicy {
    fun commandBlockReason(
        origin: CommandOrigin,
        command: ChatCommand,
    ): String? = when {
        origin != CommandOrigin.SYSTEM_ASSISTANT_KEYGUARD -> null
        command is StopCommand -> null
        else -> SYSTEM_ASSISTANT_KEYGUARD_REJECTION
    }

    fun validateAdmissionTarget(
        command: ChatCommand,
        conversationId: Uuid,
        settings: Settings,
        persistedConversation: Conversation?,
    ): SystemAssistantTargetValidation = validateTarget(
        command = command,
        conversationId = conversationId,
        settings = settings,
        persistedConversation = persistedConversation,
        requireCurrentSelection = true,
    )

    fun validateAcceptedTarget(
        command: ChatCommand,
        conversationId: Uuid,
        settings: Settings,
        persistedConversation: Conversation?,
    ): SystemAssistantTargetValidation = validateTarget(
        command = command,
        conversationId = conversationId,
        settings = settings,
        persistedConversation = persistedConversation,
        requireCurrentSelection = false,
    )

    private fun validateTarget(
        command: ChatCommand,
        conversationId: Uuid,
        settings: Settings,
        persistedConversation: Conversation?,
        requireCurrentSelection: Boolean,
    ): SystemAssistantTargetValidation {
        val assistantId = command.assistantIdSnapshot()
            ?: return SystemAssistantTargetValidation.Invalid(
                SYSTEM_ASSISTANT_TARGET_SNAPSHOT_REQUIRED_REJECTION,
            )
        if (requireCurrentSelection && settings.systemAssistantTargetAssistantId != assistantId) {
            return SystemAssistantTargetValidation.Invalid(
                SYSTEM_ASSISTANT_TARGET_CHANGED_REJECTION,
            )
        }
        val assistant = settings.assistants.firstOrNull { it.id == assistantId }
            ?: return SystemAssistantTargetValidation.Invalid(
                SYSTEM_ASSISTANT_TARGET_ASSISTANT_MISSING_REJECTION,
            )
        if (assistant.privilegedConversationId != conversationId) {
            return SystemAssistantTargetValidation.Invalid(
                SYSTEM_ASSISTANT_TARGET_CONVERSATION_CHANGED_REJECTION,
            )
        }
        val conversation = persistedConversation
            ?.takeIf { it.id == conversationId }
            ?: return SystemAssistantTargetValidation.Invalid(
                SYSTEM_ASSISTANT_TARGET_CONVERSATION_MISSING_REJECTION,
            )
        if (conversation.assistantId != assistantId) {
            return SystemAssistantTargetValidation.Invalid(
                SYSTEM_ASSISTANT_TARGET_CONVERSATION_MISMATCH_REJECTION,
            )
        }
        return SystemAssistantTargetValidation.Valid(assistant, conversation)
    }

    private fun ChatCommand.assistantIdSnapshot(): Uuid? = when (this) {
        is SendMessageCommand -> assistantIdSnapshot
        else -> null
    }
}
