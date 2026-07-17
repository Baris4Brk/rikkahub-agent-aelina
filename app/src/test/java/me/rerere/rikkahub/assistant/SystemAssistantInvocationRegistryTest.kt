package me.rerere.rikkahub.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import kotlin.uuid.Uuid

class SystemAssistantInvocationRegistryTest {
    @Test
    fun `provider statement changes when activity overlay closes during the same command`() {
        val conversationId = Uuid.random()
        val commandId = Uuid.random()
        val overlay = SystemAssistantInvocationRegistry.register(
            invokedFromKeyguard = false,
            hostKind = SystemAssistantHostKind.ACTIVITY_OVERLAY,
        )
        overlay.bindConversation(conversationId)
        val acceptedRun = checkNotNull(
            SystemAssistantInvocationRegistry.acquireAcceptedRun(
                conversationId = conversationId,
                commandId = commandId,
                hostKind = SystemAssistantHostKind.ACTIVITY_OVERLAY,
            )
        )

        val visible = SystemAssistantInvocationRegistry.currentContext(
            ToolCallOrigin.SystemAssistant,
            conversationId,
            commandId,
        ).toProviderAddendum()
        overlay.close()
        val continuing = SystemAssistantInvocationRegistry.currentContext(
            ToolCallOrigin.SystemAssistant,
            conversationId,
            commandId,
        ).toProviderAddendum()

        assertTrue(visible.contains("AI-key overlay is visible"))
        assertTrue(continuing.contains("overlay is closed"))
        acceptedRun.close()
    }

    @Test
    fun `visible invocation context distinguishes activity overlay from voice session`() {
        val conversationId = Uuid.random()
        val activityOverlay = SystemAssistantInvocationRegistry.register(
            invokedFromKeyguard = false,
            hostKind = SystemAssistantHostKind.ACTIVITY_OVERLAY,
        )
        try {
            activityOverlay.bindConversation(conversationId)

            val activityContext = SystemAssistantInvocationRegistry.currentContext(
                origin = ToolCallOrigin.SystemAssistant,
                conversationId = conversationId,
                commandId = null,
            )

            assertEquals(SystemAssistantHostKind.ACTIVITY_OVERLAY, activityContext.hostKind)
            assertEquals(InvocationSurfacePresence.OVERLAY_VISIBLE, activityContext.presence)
            assertTrue(activityContext.unlockedOwner)
        } finally {
            activityOverlay.close()
        }

        val voiceSession = SystemAssistantInvocationRegistry.register(
            invokedFromKeyguard = false,
            hostKind = SystemAssistantHostKind.VOICE_SESSION,
        )
        try {
            voiceSession.bindConversation(conversationId)

            val voiceContext = SystemAssistantInvocationRegistry.currentContext(
                origin = ToolCallOrigin.SystemAssistant,
                conversationId = conversationId,
                commandId = null,
            )

            assertEquals(SystemAssistantHostKind.VOICE_SESSION, voiceContext.hostKind)
            assertEquals(InvocationSurfacePresence.VOICE_SESSION_VISIBLE, voiceContext.presence)
            assertTrue(voiceContext.unlockedOwner)
        } finally {
            voiceSession.close()
        }
    }

    @Test
    fun `accepted run retains its activity host after overlay closes`() {
        val conversationId = Uuid.random()
        val commandId = Uuid.random()
        val activityOverlay = SystemAssistantInvocationRegistry.register(
            invokedFromKeyguard = false,
            hostKind = SystemAssistantHostKind.ACTIVITY_OVERLAY,
        )
        activityOverlay.bindConversation(conversationId)
        val acceptedRun = checkNotNull(
            SystemAssistantInvocationRegistry.acquireAcceptedRun(
                conversationId = conversationId,
                commandId = commandId,
                hostKind = SystemAssistantHostKind.ACTIVITY_OVERLAY,
            )
        )
        activityOverlay.close()

        val voiceSession = SystemAssistantInvocationRegistry.register(
            invokedFromKeyguard = false,
            hostKind = SystemAssistantHostKind.VOICE_SESSION,
        )
        try {
            voiceSession.bindConversation(conversationId)

            val context = SystemAssistantInvocationRegistry.currentContext(
                origin = ToolCallOrigin.SystemAssistant,
                conversationId = conversationId,
                commandId = commandId,
            )

            assertEquals(SystemAssistantHostKind.ACTIVITY_OVERLAY, context.hostKind)
            assertEquals(
                InvocationSurfacePresence.RUNNING_AFTER_OVERLAY_CLOSED,
                context.presence,
            )
            assertTrue(context.unlockedOwner)
        } finally {
            acceptedRun.close()
            voiceSession.close()
        }
    }

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
