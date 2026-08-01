package me.rerere.rikkahub.owner

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.pet.PetOverlaySelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class OwnerControlReportRegressionTest {
    @Test
    fun `prompt wire validator accepts documented complete definition`() {
        val definition = buildJsonObject {
            put("name", "test")
            put("enabled", false)
            put("priority", 0)
            put("position", "after_system_prompt")
            put("content", "test content")
            put("injectDepth", 4)
            put("role", "user")
        }
        assertNull(validateModeInjectionWire(definition))
    }

    @Test
    fun `prompt wire validator reports the malformed field`() {
        val issue = validateModeInjectionWire(buildJsonObject {
            put("name", "test")
            put("enabled", JsonPrimitive("false"))
        })
        assertEquals("PROMPT_ENABLED_INVALID", issue?.code)
        assertTrue(issue?.message.orEmpty().contains("JSON boolean"))
    }

    @Test
    fun `pet list selection exposes normalized read only visual state`() {
        val data = ownerPetSelectionData(PetOverlaySelection(
            ownerAssistantId = Uuid.random(),
            privilegedConversationId = Uuid.random(),
            scale = 3.5f,
            animationFps = 20,
            normalizedX = 0.25f,
            normalizedY = 0.75f,
            idlePoolEnabled = true,
        ))
        assertEquals(3.0f, data["scale"]!!.jsonPrimitive.float)
        assertEquals(12, data["fps"]!!.jsonPrimitive.int)
        assertEquals(true, data["idle_pool_enabled"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `mcp owner status classifies errors without returning raw text`() {
        val data = ownerMcpStatusData(McpStatus.Error("HTTP 401 Unauthorized secret-body"))
        assertEquals("ERROR", data["status"]!!.jsonPrimitive.content)
        assertEquals("auth_required", data["error_kind"]!!.jsonPrimitive.content)
        assertTrue(data.toString().contains("secret-body").not())
    }
}
