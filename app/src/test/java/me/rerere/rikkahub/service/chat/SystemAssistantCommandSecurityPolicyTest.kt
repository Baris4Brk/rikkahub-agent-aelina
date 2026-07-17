package me.rerere.rikkahub.service.chat

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SystemAssistantCommandSecurityPolicyTest {
    @Test
    fun `emergency stop rejects every model-capable command but still permits stop`() {
        val message = SendMessageCommand(
            content = RawUserContent(listOf(UIMessagePart.Text("ping"))),
        )

        assertEquals(
            EMERGENCY_STOP_COMMAND_REJECTION,
            emergencyStopCommandBlockReason(active = true, command = message),
        )
        assertEquals(
            EMERGENCY_STOP_COMMAND_REJECTION,
            emergencyStopCommandBlockReason(active = true, command = InterruptCommand(message)),
        )
        assertNull(emergencyStopCommandBlockReason(active = true, command = StopCommand()))
        assertNull(emergencyStopCommandBlockReason(active = false, command = message))
    }

    @Test
    fun `keyguard invocation rejects model-capable commands but still permits stop`() {
        val message = SendMessageCommand(
            content = RawUserContent(listOf(UIMessagePart.Text("ping"))),
        )

        assertEquals(
            SYSTEM_ASSISTANT_KEYGUARD_REJECTION,
            SystemAssistantCommandSecurityPolicy.commandBlockReason(
                origin = CommandOrigin.SYSTEM_ASSISTANT_KEYGUARD,
                command = message,
            ),
        )
        assertEquals(
            SYSTEM_ASSISTANT_KEYGUARD_REJECTION,
            SystemAssistantCommandSecurityPolicy.commandBlockReason(
                origin = CommandOrigin.SYSTEM_ASSISTANT_KEYGUARD,
                command = InterruptCommand(message),
            ),
        )
        assertNull(
            SystemAssistantCommandSecurityPolicy.commandBlockReason(
                origin = CommandOrigin.SYSTEM_ASSISTANT_KEYGUARD,
                command = StopCommand(),
            ),
        )
    }

    @Test
    fun `admission requires the selected target while execution honors the accepted snapshot`() {
        val conversationId = Uuid.random()
        val acceptedAssistant = Assistant(privilegedConversationId = conversationId)
        val replacementAssistant = Assistant(privilegedConversationId = Uuid.random())
        val conversation = Conversation.ofId(conversationId, acceptedAssistant.id)
        val command = SendMessageCommand(
            content = RawUserContent(listOf(UIMessagePart.Text("ping"))),
            assistantIdSnapshot = acceptedAssistant.id,
        )
        val acceptedSettings = Settings(
            systemAssistantTargetAssistantId = acceptedAssistant.id,
            assistants = listOf(acceptedAssistant, replacementAssistant),
        )
        val changedSettings = acceptedSettings.copy(
            systemAssistantTargetAssistantId = replacementAssistant.id,
        )

        assertTrue(
            SystemAssistantCommandSecurityPolicy.validateAdmissionTarget(
                command = command,
                conversationId = conversationId,
                settings = acceptedSettings,
                persistedConversation = conversation,
            ) is SystemAssistantTargetValidation.Valid,
        )
        assertEquals(
            SYSTEM_ASSISTANT_TARGET_CHANGED_REJECTION,
            (SystemAssistantCommandSecurityPolicy.validateAdmissionTarget(
                command = command,
                conversationId = conversationId,
                settings = changedSettings,
                persistedConversation = conversation,
            ) as SystemAssistantTargetValidation.Invalid).reason,
        )
        assertTrue(
            SystemAssistantCommandSecurityPolicy.validateAcceptedTarget(
                command = command,
                conversationId = conversationId,
                settings = changedSettings,
                persistedConversation = conversation,
            ) is SystemAssistantTargetValidation.Valid,
        )
    }

    @Test
    fun `accepted target fails closed when its assistant or privileged conversation changes`() {
        val conversationId = Uuid.random()
        val assistant = Assistant(privilegedConversationId = conversationId)
        val conversation = Conversation.ofId(conversationId, assistant.id)
        val command = SendMessageCommand(
            content = RawUserContent(listOf(UIMessagePart.Text("ping"))),
            assistantIdSnapshot = assistant.id,
        )
        val settings = Settings(
            systemAssistantTargetAssistantId = assistant.id,
            assistants = listOf(assistant),
        )

        val cases = listOf(
            settings.copy(assistants = emptyList()) to
                (conversation to SYSTEM_ASSISTANT_TARGET_ASSISTANT_MISSING_REJECTION),
            settings.copy(
                assistants = listOf(assistant.copy(privilegedConversationId = Uuid.random())),
            ) to (conversation to SYSTEM_ASSISTANT_TARGET_CONVERSATION_CHANGED_REJECTION),
            settings to (null to SYSTEM_ASSISTANT_TARGET_CONVERSATION_MISSING_REJECTION),
            settings to (
                Conversation.ofId(conversationId, Uuid.random()) to
                    SYSTEM_ASSISTANT_TARGET_CONVERSATION_MISMATCH_REJECTION
                ),
        )

        cases.forEach { (caseSettings, conversationAndReason) ->
            val (caseConversation, expectedReason) = conversationAndReason
            val validation = SystemAssistantCommandSecurityPolicy.validateAcceptedTarget(
                command = command,
                conversationId = conversationId,
                settings = caseSettings,
                persistedConversation = caseConversation,
            ) as SystemAssistantTargetValidation.Invalid
            assertEquals(expectedReason, validation.reason)
        }
    }

    @Test
    fun `legacy system assistant command without accepted target snapshot fails closed`() {
        val conversationId = Uuid.random()
        val assistant = Assistant(privilegedConversationId = conversationId)

        val validation = SystemAssistantCommandSecurityPolicy.validateAcceptedTarget(
            command = SendMessageCommand(
                content = RawUserContent(listOf(UIMessagePart.Text("legacy"))),
            ),
            conversationId = conversationId,
            settings = Settings(
                systemAssistantTargetAssistantId = assistant.id,
                assistants = listOf(assistant),
            ),
            persistedConversation = Conversation.ofId(conversationId, assistant.id),
        ) as SystemAssistantTargetValidation.Invalid

        assertEquals(SYSTEM_ASSISTANT_TARGET_SNAPSHOT_REQUIRED_REJECTION, validation.reason)
    }
}
