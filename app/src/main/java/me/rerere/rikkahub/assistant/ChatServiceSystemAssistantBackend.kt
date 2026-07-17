package me.rerere.rikkahub.assistant

import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.ChatService

internal data class SystemAssistantOwnerMessagePayload(
    val parts: List<UIMessagePart>,
    val annotations: List<UIMessageAnnotation>,
)

internal fun SystemAssistantChatSubmission.toOwnerMessagePayload() =
    SystemAssistantOwnerMessagePayload(
        parts = listOf(UIMessagePart.Text(text)),
        annotations = emptyList(),
    )

/**
 * Narrow adapter from the native system-assistant controller to the existing chat runtime.
 *
 * Keeping this translation in one place prevents the VoiceInteraction classes from learning
 * about Runtime channels, jobs, message persistence, or tool construction.
 */
class ChatServiceSystemAssistantBackend(
    private val chatService: ChatService,
) : SystemAssistantChatBackend {
    override fun flows(conversationId: kotlin.uuid.Uuid): SystemAssistantChatFlows =
        SystemAssistantChatFlows(
            conversation = chatService.getConversationFlow(conversationId),
            runtime = chatService.getRuntimeStateFlow(conversationId),
            queue = chatService.getQueueStatusFlow(conversationId),
        )

    override suspend fun submit(
        submission: SystemAssistantChatSubmission,
    ): SystemAssistantChatSubmissionReceipt {
        val ownerMessage = submission.toOwnerMessagePayload()
        val tracked = chatService.submitUserMessageTracked(
            commandId = submission.commandId,
            conversationId = submission.conversationId,
            content = ownerMessage.parts,
            answer = true,
            origin = submission.origin,
            dedupeKey = submission.dedupeKey,
            assistantIdSnapshot = submission.assistantId,
            annotations = ownerMessage.annotations,
        )
        return SystemAssistantChatSubmissionReceipt(
            result = tracked.submission,
            outcome = tracked.outcome,
        )
    }
}
