package me.rerere.rikkahub.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SystemAssistantInvocationRegistryTest {
    @Test
    fun `accepted run survives overlay close only until its outcome releases the lease`() {
        val conversationId = Uuid.random()
        val commandId = Uuid.random()
        val overlay = SystemAssistantInvocationRegistry.register(invokedFromKeyguard = false)
        overlay.bindConversation(conversationId)
        val acceptedRun = checkNotNull(
            SystemAssistantInvocationRegistry.acquireAcceptedRun(conversationId, commandId)
        )

        overlay.close()
        assertTrue(
            SystemAssistantInvocationRegistry.hasAuthorizedUnlockedInvocation(
                conversationId,
                commandId,
            )
        )
        assertFalse(
            SystemAssistantInvocationRegistry.hasAuthorizedUnlockedInvocation(
                conversationId,
                Uuid.random(),
            )
        )

        acceptedRun.close()
        acceptedRun.close()
        assertFalse(
            SystemAssistantInvocationRegistry.hasAuthorizedUnlockedInvocation(
                conversationId,
                commandId,
            )
        )
    }

    @Test
    fun `keyguard non-owner and wrong-conversation overlays cannot create run leases`() {
        val boundConversation = Uuid.random()
        val wrongConversation = Uuid.random()
        val keyguard = SystemAssistantInvocationRegistry.register(invokedFromKeyguard = true)
        val nonOwner = SystemAssistantInvocationRegistry.register(
            invokedFromKeyguard = false,
            ownerUser = false,
        )
        try {
            keyguard.bindConversation(boundConversation)
            nonOwner.bindConversation(boundConversation)

            assertNull(
                SystemAssistantInvocationRegistry.acquireAcceptedRun(
                    boundConversation,
                    Uuid.random(),
                )
            )
            assertNull(
                SystemAssistantInvocationRegistry.acquireAcceptedRun(
                    wrongConversation,
                    Uuid.random(),
                )
            )
        } finally {
            keyguard.close()
            nonOwner.close()
        }
    }

    @Test
    fun `unbound token never authorizes a conversation`() {
        val conversationId = Uuid.random()
        val token = SystemAssistantInvocationRegistry.register(invokedFromKeyguard = false)
        try {
            assertTrue(SystemAssistantInvocationRegistry.hasActiveInvocation())
            assertTrue(SystemAssistantInvocationRegistry.hasActiveUnlockedInvocation())
            assertFalse(
                SystemAssistantInvocationRegistry.hasActiveUnlockedInvocation(conversationId)
            )
        } finally {
            token.close()
        }
    }

    @Test
    fun `closing one of multiple tokens does not close the other`() {
        val conversationId = Uuid.random()
        val first = SystemAssistantInvocationRegistry.register(invokedFromKeyguard = false)
        val second = SystemAssistantInvocationRegistry.register(invokedFromKeyguard = false)
        try {
            first.bindConversation(conversationId)
            second.bindConversation(conversationId)

            first.close()
            first.close()

            assertTrue(
                SystemAssistantInvocationRegistry.hasActiveUnlockedInvocation(conversationId)
            )
        } finally {
            first.close()
            second.close()
        }

        assertFalse(SystemAssistantInvocationRegistry.hasActiveInvocation())
    }

    @Test
    fun `tokens authorize only their bound conversation`() {
        val firstConversation = Uuid.random()
        val secondConversation = Uuid.random()
        val first = SystemAssistantInvocationRegistry.register(invokedFromKeyguard = false)
        val second = SystemAssistantInvocationRegistry.register(invokedFromKeyguard = false)
        try {
            first.bindConversation(firstConversation)
            second.bindConversation(secondConversation)

            assertTrue(
                SystemAssistantInvocationRegistry.hasActiveUnlockedInvocation(firstConversation)
            )
            assertTrue(
                SystemAssistantInvocationRegistry.hasActiveUnlockedInvocation(secondConversation)
            )

            first.close()

            assertFalse(
                SystemAssistantInvocationRegistry.hasActiveUnlockedInvocation(firstConversation)
            )
            assertTrue(
                SystemAssistantInvocationRegistry.hasActiveUnlockedInvocation(secondConversation)
            )
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `keyguard and non-owner tokens never authorize unlocked gate checks`() {
        val conversationId = Uuid.random()
        val keyguard = SystemAssistantInvocationRegistry.register(invokedFromKeyguard = true)
        val nonOwner = SystemAssistantInvocationRegistry.register(
            invokedFromKeyguard = false,
            ownerUser = false,
        )
        try {
            keyguard.bindConversation(conversationId)
            nonOwner.bindConversation(conversationId)

            assertTrue(SystemAssistantInvocationRegistry.hasActiveKeyguardInvocation())
            assertFalse(
                SystemAssistantInvocationRegistry.hasActiveUnlockedInvocation(conversationId)
            )
        } finally {
            keyguard.close()
            nonOwner.close()
        }
    }
}
