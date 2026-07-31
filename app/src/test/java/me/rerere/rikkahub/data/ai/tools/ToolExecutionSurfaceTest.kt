package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionSurfaceTest {
    @Test
    fun `publishes exact current turn tools once`() {
        val surface = ToolExecutionSurface()
        val tool = Tool(
            name = "sample",
            description = "sample",
            parameters = { InputSchema.Obj(buildJsonObject {}) },
            execute = { emptyList() },
        )

        assertTrue(surface.publish(listOf(tool)))
        assertFalse(surface.publish(emptyList()))
        assertEquals(listOf("sample"), surface.snapshot().map { it.name })
    }
}
