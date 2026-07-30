package me.rerere.rikkahub.service.chat

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.assistant.SecondUserAdmissionSnapshot
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.quickcapture.QuickCaptureInvocationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class QuickCaptureCommandSecurityPolicyTest {
    @After
    fun clearAuthority() {
        SecondUserAuthorityRegistry.install(null)
    }

    @Test
    fun `admission needs the exact visible overlay lease and execution rechecks target binding`() {
        val conversationId = Uuid.random()
        val assistant = Assistant(privilegedConversationId = conversationId)
        val conversation = Conversation.ofId(conversationId, assistant.id)
        val commandId = Uuid.random()
        val sessionId = Uuid.random()
        val command = SendMessageCommand(
            content = RawUserContent(listOf(UIMessagePart.Text("inspect"))),
            assistantIdSnapshot = assistant.id,
            quickCaptureSessionId = sessionId,
        )
        val settings = Settings(assistants = listOf(assistant))
        SecondUserAuthorityRegistry.install(
            SecondUserAdmissionSnapshot.create(
                assistantId = assistant.id,
                conversationId = conversationId,
                authorityEpoch = 1L,
                origin = me.rerere.rikkahub.data.ai.ToolCallOrigin.QuickCapture,
            ),
        )

        assertEquals(
            QUICK_CAPTURE_TOKEN_REQUIRED_REJECTION,
            (QuickCaptureCommandSecurityPolicy.validateAdmission(
                commandId = commandId,
                command = command,
                conversationId = conversationId,
                settings = settings,
                persistedConversation = conversation,
            ) as QuickCaptureTargetValidation.Invalid).reason,
        )

        val overlay = QuickCaptureInvocationRegistry.registerOverlay()
        try {
            overlay.bindConversation(conversationId)
            val lease = overlay.acquireAcceptedRun(
                conversationId = conversationId,
                assistantId = assistant.id,
                commandId = commandId,
                captureSessionId = sessionId,
            )
            assertTrue(lease != null)
            assertTrue(
                QuickCaptureCommandSecurityPolicy.validateAdmission(
                    commandId = commandId,
                    command = command,
                    conversationId = conversationId,
                    settings = settings,
                    persistedConversation = conversation,
                ) is QuickCaptureTargetValidation.Valid,
            )

            val rebound = settings.copy(
                assistants = listOf(assistant.copy(privilegedConversationId = Uuid.random())),
            )
            // The old assistant mirror is no longer a live authority source.
            assertTrue(
                QuickCaptureCommandSecurityPolicy.validateAccepted(
                    command = command,
                    conversationId = conversationId,
                    settings = rebound,
                    persistedConversation = conversation,
                ) is QuickCaptureTargetValidation.Valid,
            )
            SecondUserAuthorityRegistry.install(
                SecondUserAdmissionSnapshot.create(
                    assistantId = assistant.id,
                    conversationId = Uuid.random(),
                    authorityEpoch = 2L,
                    origin = me.rerere.rikkahub.data.ai.ToolCallOrigin.QuickCapture,
                ),
            )
            assertEquals(
                QUICK_CAPTURE_TARGET_CONVERSATION_CHANGED_REJECTION,
                (QuickCaptureCommandSecurityPolicy.validateAccepted(
                    command = command,
                    conversationId = conversationId,
                    settings = rebound,
                    persistedConversation = conversation,
                ) as QuickCaptureTargetValidation.Invalid).reason,
            )
            lease!!.close()
        } finally {
            overlay.close()
        }
    }
}
