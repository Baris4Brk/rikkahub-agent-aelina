package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase-17 stability — invocation context plumbing.
 *
 * The recursion-guard / authoring-id behavior is exercised end-to-end at the tool factory
 * level (subagentDispatchTool / workflowCreateTool). Here we just pin the contract that
 * the data class is non-breaking by default (EMPTY) so legacy call sites keep working.
 */
class ToolInvocationContextTest {

    @Test fun `EMPTY has no caller info and is not headless`() {
        assertNull(ToolInvocationContext.EMPTY.callerAssistantId)
        assertNull(ToolInvocationContext.EMPTY.callerConversationId)
        assertNull(ToolInvocationContext.EMPTY.callerModelId)
        assertFalse(ToolInvocationContext.EMPTY.isHeadless)
        assertEquals(ToolNameSnapshot.EMPTY, ToolInvocationContext.EMPTY.toolNameSurface.snapshot())
    }

    @Test fun `default constructor matches EMPTY`() {
        assertEquals(ToolInvocationContext(), ToolInvocationContext.EMPTY)
    }

    @Test fun `headless context preserves caller ids`() {
        val ctx = ToolInvocationContext(
            callerAssistantId = "asst-123",
            callerConversationId = "conv-456",
            callerModelId = "model-789",
            isHeadless = true,
            toolNameSurface = ToolNameSurface().also {
                it.publish(setOf("search_web"), setOf("search_web", "write_file"))
            },
        )
        assertEquals("asst-123", ctx.callerAssistantId)
        assertEquals("conv-456", ctx.callerConversationId)
        assertEquals("model-789", ctx.callerModelId)
        assertEquals(true, ctx.isHeadless)
        assertEquals(setOf("search_web"), ctx.toolNameSurface.snapshot().available)
    }
}
