package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthSensorsToolTest {
    private fun execute(tool: Tool, args: String) = runBlocking {
        val part = tool.execute(Json.parseToJsonElement(args)).single() as UIMessagePart.Text
        Json.parseToJsonElement(part.text).jsonObject
    }

    @Test
    fun `health sensor read returns structured samples and clamps duration`() {
        val backend = object : HealthSensorsBackend {
            var durationMs: Int? = null

            override fun availableSensors(): HealthSensorsResult =
                HealthSensorsResult.Available(emptyList())

            override suspend fun read(type: String, durationMs: Int): HealthSensorsResult {
                this.durationMs = durationMs
                return HealthSensorsResult.Reading(
                    type = "heart_rate",
                    values = listOf(72.5),
                    unit = "bpm",
                    sampleCount = 4,
                    timestampMs = 1234L,
                )
            }
        }

        val result = execute(
            readHealthSensorTool(backend),
            """{"type":"heart_rate","duration_ms":99999}""",
        )

        assertEquals(5_000, backend.durationMs)
        assertEquals("heart_rate", result["type"]?.jsonPrimitive?.content)
        assertEquals("72.5", result["values"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals("bpm", result["unit"]?.jsonPrimitive?.content)
        assertEquals("4", result["sample_count"]?.jsonPrimitive?.content)
    }

    @Test
    fun `health sensor reads require approval`() {
        assertTrue(ToolApprovalDefaults.requiresApproval("list_health_sensors"))
        assertTrue(ToolApprovalDefaults.requiresApproval("read_health_sensor"))
    }
}
