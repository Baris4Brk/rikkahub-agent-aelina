package me.rerere.rikkahub.assistant

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SystemAssistantFallbackPolicyTest {
    @Test
    fun `only assist and the explicit second-user shortcut may open the fallback surface`() {
        assertTrue(isSystemAssistantFallbackAction(Intent.ACTION_ASSIST))
        assertTrue(isSystemAssistantFallbackAction(SYSTEM_ASSISTANT_SHORTCUT_ACTION))

        assertFalse(isSystemAssistantFallbackAction(null))
        assertFalse(isSystemAssistantFallbackAction(Intent.ACTION_VIEW))
        assertFalse(isSystemAssistantFallbackAction("com.example.UNTRUSTED"))
    }

    @Test
    fun `unlocked owner routes to the exact resolved second-user conversation`() {
        val assistantId = Uuid.random()
        val conversationId = Uuid.random()
        val target = SecondUserTargetResolution.Resolved(
            assistantId = assistantId,
            assistantName = "啥子七",
            conversationId = conversationId,
            displayName = "七姐",
        )

        assertEquals(
            SystemAssistantFallbackDestination.Conversation(conversationId),
            decideSystemAssistantFallbackDestination(
                ownerUser = true,
                deviceLocked = false,
                targetResolution = target,
            ),
        )
    }

    @Test
    fun `keyguard and non-owner launches are permanently dismissed`() {
        val target = SecondUserTargetResolution.Resolved(
            assistantId = Uuid.random(),
            conversationId = Uuid.random(),
            displayName = "七姐",
            assistantName = "啥子七",
        )

        assertEquals(
            SystemAssistantFallbackDestination.Dismiss,
            decideSystemAssistantFallbackDestination(
                ownerUser = true,
                deviceLocked = true,
                targetResolution = target,
            ),
        )
        assertEquals(
            SystemAssistantFallbackDestination.Dismiss,
            decideSystemAssistantFallbackDestination(
                ownerUser = false,
                deviceLocked = false,
                targetResolution = target,
            ),
        )
    }

    @Test
    fun `every unresolved target opens configuration instead of an arbitrary chat`() {
        val assistantId = Uuid.random()
        val conversationId = Uuid.random()
        val failures = listOf<SecondUserTargetResolution?>(
            null,
            SecondUserTargetResolution.TargetNotSelected,
            SecondUserTargetResolution.AssistantNotFound(assistantId),
            SecondUserTargetResolution.PrivilegedConversationNotConfigured(assistantId),
            SecondUserTargetResolution.ConversationNotFound(assistantId, conversationId),
            SecondUserTargetResolution.ConversationAssistantMismatch(
                assistantId = assistantId,
                conversationId = conversationId,
                actualAssistantId = Uuid.random(),
            ),
        )

        failures.forEach { failure ->
            assertEquals(
                SystemAssistantFallbackDestination.Configuration,
                decideSystemAssistantFallbackDestination(
                    ownerUser = true,
                    deviceLocked = false,
                    targetResolution = failure,
                ),
            )
        }
    }
}
