package me.rerere.rikkahub.quickcapture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class QuickCaptureInvocationRegistryTest {
    @Test
    fun `visible overlay alone cannot authorize a tool run and accepted lease is exact`() {
        val conversationId = Uuid.random()
        val assistantId = Uuid.random()
        val commandId = Uuid.random()
        val captureSessionId = Uuid.random()
        val overlay = QuickCaptureInvocationRegistry.registerOverlay()
        try {
            overlay.bindConversation(conversationId)
            assertFalse(QuickCaptureInvocationRegistry.hasAuthorizedRun(conversationId, commandId))

            val lease = overlay.acquireAcceptedRun(
                conversationId,
                assistantId,
                commandId,
                captureSessionId,
            )
            assertNotNull(lease)
            lease!!.use {
                assertTrue(QuickCaptureInvocationRegistry.hasAuthorizedRun(conversationId, commandId))
                assertTrue(
                    QuickCaptureInvocationRegistry.hasAcceptedRun(
                        conversationId,
                        assistantId,
                        commandId,
                        captureSessionId,
                    ),
                )
                assertFalse(QuickCaptureInvocationRegistry.hasAuthorizedRun(conversationId, Uuid.random()))
            }
            assertFalse(QuickCaptureInvocationRegistry.hasAuthorizedRun(conversationId, commandId))
        } finally {
            overlay.close()
        }
    }

    @Test
    fun `only the exact bound overlay can mint a non replayable lease`() {
        val conversationId = Uuid.random()
        val assistantId = Uuid.random()
        val commandId = Uuid.random()
        val captureSessionId = Uuid.random()
        val bound = QuickCaptureInvocationRegistry.registerOverlay()
        val unrelated = QuickCaptureInvocationRegistry.registerOverlay()
        try {
            bound.bindConversation(conversationId)

            assertFalse(
                unrelated.acquireAcceptedRun(
                    conversationId,
                    assistantId,
                    commandId,
                    captureSessionId,
                ) != null,
            )

            val lease = bound.acquireAcceptedRun(
                conversationId,
                assistantId,
                commandId,
                captureSessionId,
            )
            assertNotNull(lease)
            assertFalse(
                bound.acquireAcceptedRun(
                    conversationId,
                    assistantId,
                    commandId,
                    captureSessionId,
                ) != null,
            )
            lease!!.close()
            assertFalse(
                bound.acquireAcceptedRun(
                    conversationId,
                    assistantId,
                    Uuid.random(),
                    captureSessionId,
                ) != null,
            )
        } finally {
            unrelated.close()
            bound.close()
        }
    }
}
