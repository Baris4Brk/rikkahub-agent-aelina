package me.rerere.rikkahub.privilege

import me.rerere.rikkahub.data.ai.ToolCallOrigin
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class PrivilegedActionGuardTest {
    private val assistantId = Uuid.random()
    private val conversationId = Uuid.random()
    private val context = PrivilegedSessionContext(
        assistantId = assistantId,
        conversationId = conversationId,
        origin = ToolCallOrigin.LocalChat,
        privilegedConversationId = conversationId,
        identityName = "第二用户",
        isPrivileged = true,
        expandLocalTools = true,
        autoApproveTools = true,
        unrestrictedOverride = true,
    )

    @Test
    fun `unrestricted privileged session cannot cross the seven hard boundaries`() {
        val guard = DefaultPrivilegedActionGuard("me.rerere.rikkahub")
        val protectedActions = listOf(
            PrivilegedAction.CloseApplication,
            PrivilegedAction.ForceStopPackage("me.rerere.rikkahub"),
            PrivilegedAction.ModifySafetySettings,
            PrivilegedAction.ChangePrivilegedIdentity(assistantId),
            PrivilegedAction.ChangePrivilegedConversation(assistantId),
            PrivilegedAction.ChangeAssistantId(assistantId),
            PrivilegedAction.ChangeUnrestricted(assistantId),
            PrivilegedAction.DeleteConversation(conversationId),
        )

        protectedActions.forEach { action ->
            assertTrue("$action must be denied", guard.check(action, context) is PrivilegedActionDecision.Denied)
        }
    }
}
