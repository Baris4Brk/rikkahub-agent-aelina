package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolNameSurfaceTest {
    @Test
    fun `a parent turn publishes one immutable tool surface for later child dispatch`() {
        val surface = ToolNameSurface()
        val available = linkedSetOf("search_web", "memory_tool")
        val known = linkedSetOf("search_web", "memory_tool", "workspace_shell")

        assertEquals(ToolNameSnapshot.EMPTY, surface.snapshot())
        assertTrue(surface.publish(available, known))

        available += "write_file"
        known.clear()
        assertEquals(
            ToolNameSnapshot(
                available = setOf("search_web", "memory_tool"),
                known = setOf("search_web", "memory_tool", "workspace_shell"),
            ),
            surface.snapshot(),
        )

        assertFalse(surface.publish(setOf("write_file"), setOf("write_file")))
        assertEquals(setOf("search_web", "memory_tool"), surface.snapshot().available)
    }
}
