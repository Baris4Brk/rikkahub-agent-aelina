package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderToolMaterializationTest {
    @Test
    fun `schema is resolved once while runtime closures stay live`() = runBlocking {
        var schemaCalls = 0
        var approvalCalls = 0
        var executionCalls = 0
        val schema = InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
            },
        )
        val original = Tool(
            name = "read_file",
            description = "Read a file",
            parameters = {
                schemaCalls += 1
                schema
            },
            needsApproval = {
                approvalCalls += 1
                false
            },
            execute = {
                executionCalls += 1
                listOf(UIMessagePart.Text("done"))
            },
        )

        val materialized = listOf(original).materializeProviderToolSchemas().single()

        assertEquals(1, schemaCalls)
        assertSame(schema, materialized.parameters())
        assertSame(schema, materialized.parameters())
        assertEquals(1, schemaCalls)
        assertFalse(materialized.needsApproval(JsonPrimitive("input")))
        assertEquals(1, approvalCalls)
        assertEquals(
            listOf(UIMessagePart.Text("done")),
            materialized.execute(JsonPrimitive("input")),
        )
        assertEquals(1, executionCalls)
    }
}
