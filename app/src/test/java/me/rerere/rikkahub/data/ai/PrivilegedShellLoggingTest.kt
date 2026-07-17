package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import me.rerere.rikkahub.privilege.PRIVILEGED_SHELL_TOOL_NAME
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_TOOL_NAMES
import me.rerere.rikkahub.privilege.STRUCTURED_PRIVILEGED_V2_TOOL_NAMES
import me.rerere.rikkahub.data.ai.tools.local.VERIFIED_ACCESSIBILITY_TOOL_NAMES
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedShellLoggingTest {
    @Test
    fun `privileged command log and loop signature never contain payload`() {
        val secretCommand = "echo super-secret-token | tr a-z A-Z"
        val input = """{"mode":"shell","command":"$secretCommand","stdin":"hidden-input"}"""
        val args = Json.parseToJsonElement(input)

        val summary = toolExecutionLogSummary(PRIVILEGED_SHELL_TOOL_NAME, args)
        val signature = toolLoopSignature(PRIVILEGED_SHELL_TOOL_NAME, input)

        assertTrue(summary.contains("payloadRedacted=true"))
        assertFalse(summary.contains(secretCommand))
        assertFalse(summary.contains("hidden-input"))
        assertFalse(signature.contains(secretCommand))
        assertNotEquals(
            signature,
            toolLoopSignature(PRIVILEGED_SHELL_TOOL_NAME, input.replace("echo", "printf")),
        )
    }

    @Test
    fun `structured privileged logs and loop signatures redact all arguments`() {
        val input = """{"namespace":"secure","key":"secret_key","value":"secret_value"}"""
        val args = Json.parseToJsonElement(input)

        STRUCTURED_PRIVILEGED_TOOL_NAMES.forEach { toolName ->
            val summary = toolExecutionLogSummary(toolName, args)
            val signature = toolLoopSignature(toolName, input)

            assertTrue(summary.contains("payloadRedacted=true"))
            assertFalse(summary.contains("secret_key"))
            assertFalse(summary.contains("secret_value"))
            assertFalse(signature.contains("secret_key"))
            assertFalse(signature.contains("secret_value"))
            assertTrue(isSensitivePrivilegedTool(toolName))
        }
    }

    @Test
    fun `v2 and verified accessibility payloads are hashed and fully redacted`() {
        val input = """{"text":"825104","extras":{"token":"private"}}"""
        val args = Json.parseToJsonElement(input)

        (STRUCTURED_PRIVILEGED_V2_TOOL_NAMES + VERIFIED_ACCESSIBILITY_TOOL_NAMES).forEach { toolName ->
            val summary = toolExecutionLogSummary(toolName, args)
            val signature = toolLoopSignature(toolName, input)

            assertTrue(summary.contains("payloadRedacted=true"))
            assertFalse(summary.contains("825104"))
            assertFalse(summary.contains("private"))
            assertFalse(signature.contains("825104"))
            assertFalse(signature.contains("private"))
            assertTrue(isSensitivePrivilegedTool(toolName))
        }
    }
}
