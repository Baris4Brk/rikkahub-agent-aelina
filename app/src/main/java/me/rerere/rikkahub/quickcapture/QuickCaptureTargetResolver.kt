package me.rerere.rikkahub.quickcapture

import me.rerere.ai.provider.Modality
import me.rerere.rikkahub.assistant.SecondUserTargetResolution
import me.rerere.rikkahub.assistant.SecondUserTargetResolver
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import kotlin.uuid.Uuid

fun interface QuickCaptureSettingsReader {
    suspend fun read(): Settings
}

sealed interface QuickCaptureTargetResolution {
    data class Resolved(val target: QuickCaptureTarget) : QuickCaptureTargetResolution

    data class Unavailable(
        val reason: QuickCaptureTargetFailure,
        val detail: String? = null,
    ) : QuickCaptureTargetResolution
}

enum class QuickCaptureTargetFailure {
    TARGET_NOT_SELECTED,
    ASSISTANT_NOT_FOUND,
    PRIVILEGED_CONVERSATION_NOT_CONFIGURED,
    CONVERSATION_NOT_FOUND,
    CONVERSATION_ASSISTANT_MISMATCH,
    CHAT_MODEL_UNAVAILABLE,
    CHAT_PROVIDER_UNAVAILABLE,
    OCR_MODEL_UNAVAILABLE,
    OCR_PROVIDER_UNAVAILABLE,
}

/**
 * Resolves exactly one fixed second-user destination for a QuickCapture submission.
 * It intentionally never observes Settings.assistantId, so switching ordinary chats cannot
 * redirect a screen capture.
 */
class QuickCaptureTargetResolver(
    private val settingsReader: QuickCaptureSettingsReader,
    private val secondUserResolver: SecondUserTargetResolver,
) {
    suspend fun resolve(temporaryAssistantId: Uuid? = null): QuickCaptureTargetResolution {
        val settings = settingsReader.read()
        val quick = settings.quickCaptureSettings
        val (assistantId, source) = when {
            temporaryAssistantId != null -> temporaryAssistantId to QuickCaptureTargetSource.TEMPORARY
            quick.targetMode == QuickCaptureTargetMode.FIXED_ASSISTANT && quick.fixedAssistantId != null ->
                quick.fixedAssistantId to QuickCaptureTargetSource.FIXED
            else -> settings.systemAssistantTargetAssistantId to QuickCaptureTargetSource.SYSTEM_ASSISTANT
        }
        val resolved = secondUserResolver.resolveAssistant(settings, assistantId)
        val target = when (resolved) {
            is SecondUserTargetResolution.Resolved -> QuickCaptureTarget(
                assistantId = resolved.assistantId,
                assistantName = resolved.assistantName,
                conversationId = resolved.conversationId,
                conversationTitle = resolved.conversationTitle,
                ownerDisplayName = resolved.displayName,
                source = source,
            )
            else -> return QuickCaptureTargetResolution.Unavailable(resolved.toQuickCaptureFailure())
        }
        return validateVisualPath(settings, target)
    }

    /**
     * Rechecks a frozen batch/command destination. It deliberately does not re-run the target
     * priority chain: a changed system-assistant target must never redirect an existing capture.
     */
    suspend fun validateTargetSnapshot(target: QuickCaptureTarget): QuickCaptureTargetResolution {
        val settings = settingsReader.read()
        val resolved = secondUserResolver.resolveAssistant(settings, target.assistantId)
        val rebound = when (resolved) {
            is SecondUserTargetResolution.Resolved -> QuickCaptureTarget(
                assistantId = resolved.assistantId,
                assistantName = resolved.assistantName,
                conversationId = resolved.conversationId,
                conversationTitle = resolved.conversationTitle,
                ownerDisplayName = resolved.displayName,
                source = target.source,
            )
            else -> return QuickCaptureTargetResolution.Unavailable(resolved.toQuickCaptureFailure())
        }
        if (rebound.conversationId != target.conversationId) {
            return QuickCaptureTargetResolution.Unavailable(
                QuickCaptureTargetFailure.CONVERSATION_ASSISTANT_MISMATCH,
            )
        }
        return validateVisualPath(settings, rebound)
    }

    fun validateVisualPath(
        settings: Settings,
        target: QuickCaptureTarget,
    ): QuickCaptureTargetResolution {
        val assistant = settings.assistants.firstOrNull { it.id == target.assistantId }
            ?: return QuickCaptureTargetResolution.Unavailable(QuickCaptureTargetFailure.ASSISTANT_NOT_FOUND)
        val chatModel = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: return QuickCaptureTargetResolution.Unavailable(QuickCaptureTargetFailure.CHAT_MODEL_UNAVAILABLE)
        val chatProvider = chatModel.findProvider(settings.providers)
            ?: return QuickCaptureTargetResolution.Unavailable(QuickCaptureTargetFailure.CHAT_PROVIDER_UNAVAILABLE)
        if (!chatProvider.enabled) {
            return QuickCaptureTargetResolution.Unavailable(QuickCaptureTargetFailure.CHAT_PROVIDER_UNAVAILABLE)
        }
        if (chatModel.inputModalities.contains(Modality.IMAGE)) {
            return QuickCaptureTargetResolution.Resolved(target)
        }
        val ocrModel = settings.findModelById(settings.ocrModelId)
            ?: return QuickCaptureTargetResolution.Unavailable(QuickCaptureTargetFailure.OCR_MODEL_UNAVAILABLE)
        val ocrProvider = ocrModel.findProvider(settings.providers)
            ?: return QuickCaptureTargetResolution.Unavailable(QuickCaptureTargetFailure.OCR_PROVIDER_UNAVAILABLE)
        if (!ocrProvider.enabled || !ocrModel.inputModalities.contains(Modality.IMAGE)) {
            return QuickCaptureTargetResolution.Unavailable(QuickCaptureTargetFailure.OCR_PROVIDER_UNAVAILABLE)
        }
        return QuickCaptureTargetResolution.Resolved(target)
    }
}

private fun SecondUserTargetResolution.toQuickCaptureFailure(): QuickCaptureTargetFailure = when (this) {
    SecondUserTargetResolution.TargetNotSelected -> QuickCaptureTargetFailure.TARGET_NOT_SELECTED
    is SecondUserTargetResolution.AssistantNotFound -> QuickCaptureTargetFailure.ASSISTANT_NOT_FOUND
    is SecondUserTargetResolution.PrivilegedConversationNotConfigured ->
        QuickCaptureTargetFailure.PRIVILEGED_CONVERSATION_NOT_CONFIGURED
    is SecondUserTargetResolution.ConversationNotFound -> QuickCaptureTargetFailure.CONVERSATION_NOT_FOUND
    is SecondUserTargetResolution.ConversationAssistantMismatch ->
        QuickCaptureTargetFailure.CONVERSATION_ASSISTANT_MISMATCH
    is SecondUserTargetResolution.Resolved -> error("A resolved target has no failure mapping")
}
