package me.rerere.rikkahub.service.chat

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.quickcapture.QuickCaptureInvocationRegistry
import kotlin.uuid.Uuid

internal const val QUICK_CAPTURE_TOKEN_REQUIRED_REJECTION =
    "The QuickCapture command was not accepted from a visible trusted overlay. Capture again."
internal const val QUICK_CAPTURE_TARGET_SNAPSHOT_REQUIRED_REJECTION =
    "The QuickCapture command has no accepted target snapshot. Capture again."
internal const val QUICK_CAPTURE_TARGET_ASSISTANT_MISSING_REJECTION =
    "The accepted QuickCapture assistant no longer exists."
internal const val QUICK_CAPTURE_TARGET_CONVERSATION_CHANGED_REJECTION =
    "The accepted QuickCapture assistant is no longer bound to this second-user conversation."
internal const val QUICK_CAPTURE_TARGET_CONVERSATION_MISSING_REJECTION =
    "The accepted QuickCapture second-user conversation no longer exists."
internal const val QUICK_CAPTURE_TARGET_CONVERSATION_MISMATCH_REJECTION =
    "The accepted QuickCapture conversation belongs to a different assistant."

internal sealed interface QuickCaptureTargetValidation {
    data class Valid(val assistant: Assistant, val conversation: Conversation) : QuickCaptureTargetValidation
    data class Invalid(val reason: String) : QuickCaptureTargetValidation
}

/** Admission consumes only a visible overlay proof; execution rechecks durable target ownership. */
internal object QuickCaptureCommandSecurityPolicy {
    fun validateAdmission(
        commandId: Uuid,
        command: ChatCommand,
        conversationId: Uuid,
        settings: Settings,
        persistedConversation: Conversation?,
    ): QuickCaptureTargetValidation {
        val send = command as? SendMessageCommand
            ?: return QuickCaptureTargetValidation.Invalid(QUICK_CAPTURE_TARGET_SNAPSHOT_REQUIRED_REJECTION)
        val assistantId = send.assistantIdSnapshot
            ?: return QuickCaptureTargetValidation.Invalid(QUICK_CAPTURE_TARGET_SNAPSHOT_REQUIRED_REJECTION)
        val captureSessionId = send.quickCaptureSessionId
            ?: return QuickCaptureTargetValidation.Invalid(QUICK_CAPTURE_TARGET_SNAPSHOT_REQUIRED_REJECTION)
        if (!QuickCaptureInvocationRegistry.hasAcceptedRun(
                conversationId = conversationId,
                assistantId = assistantId,
                commandId = commandId,
                captureSessionId = captureSessionId,
            )
        ) {
            return QuickCaptureTargetValidation.Invalid(QUICK_CAPTURE_TOKEN_REQUIRED_REJECTION)
        }
        return validateBoundTarget(assistantId, conversationId, settings, persistedConversation)
    }

    fun validateAccepted(
        command: ChatCommand,
        conversationId: Uuid,
        settings: Settings,
        persistedConversation: Conversation?,
    ): QuickCaptureTargetValidation {
        val assistantId = (command as? SendMessageCommand)?.assistantIdSnapshot
            ?: return QuickCaptureTargetValidation.Invalid(QUICK_CAPTURE_TARGET_SNAPSHOT_REQUIRED_REJECTION)
        return validateBoundTarget(assistantId, conversationId, settings, persistedConversation)
    }

    private fun validateBoundTarget(
        assistantId: Uuid,
        conversationId: Uuid,
        settings: Settings,
        persistedConversation: Conversation?,
    ): QuickCaptureTargetValidation {
        val assistant = settings.assistants.firstOrNull { it.id == assistantId }
            ?: return QuickCaptureTargetValidation.Invalid(QUICK_CAPTURE_TARGET_ASSISTANT_MISSING_REJECTION)
        if (assistant.privilegedConversationId != conversationId) {
            return QuickCaptureTargetValidation.Invalid(QUICK_CAPTURE_TARGET_CONVERSATION_CHANGED_REJECTION)
        }
        val conversation = persistedConversation?.takeIf { it.id == conversationId }
            ?: return QuickCaptureTargetValidation.Invalid(QUICK_CAPTURE_TARGET_CONVERSATION_MISSING_REJECTION)
        if (conversation.assistantId != assistantId) {
            return QuickCaptureTargetValidation.Invalid(QUICK_CAPTURE_TARGET_CONVERSATION_MISMATCH_REJECTION)
        }
        return QuickCaptureTargetValidation.Valid(assistant, conversation)
    }
}
