package me.rerere.ai.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.util.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolReplayArgumentsTest {
    @Test
    fun `valid multilingual and escaped arguments round trip as canonical json`() {
        val source = """
            {
              "query": "中文\nsecond line",
              "quote": "say \"hello\"",
              "windows_path": "C:\\\\Users\\\\test"
            }
        """.trimIndent()

        val replay = ToolReplayArguments.from(source)

        assertTrue(replay.isValid)
        assertEquals(replay.json, json.parseToJsonElement(replay.serialized))
        assertEquals("中文\nsecond line", replay.json.jsonObject.getValue("query").jsonPrimitive.content)
        assertFalse(replay.serialized.contains("\n  "))
    }

    @Test
    fun `blank arguments remain a valid empty object for zero argument tools`() {
        val replay = ToolReplayArguments.from("   ")

        assertTrue(replay.isValid)
        assertEquals(JsonObject(emptyMap()), replay.json)
        assertEquals("{}", replay.serialized)
    }

    @Test
    fun `truncated arguments become a valid redacted replay sentinel`() {
        val raw = """{"path":"private/value","recursive":tru"""

        val replay = ToolReplayArguments.from(raw)
        val sentinel = replay.json.jsonObject

        assertFalse(replay.isValid)
        assertTrue(sentinel.getValue("invalid_tool_arguments").jsonPrimitive.boolean)
        assertEquals("malformed_or_incomplete_json", sentinel.getValue("reason").jsonPrimitive.content)
        assertEquals(replay.json, json.parseToJsonElement(replay.serialized))
        assertFalse(replay.serialized.contains("private/value"))
    }
}
