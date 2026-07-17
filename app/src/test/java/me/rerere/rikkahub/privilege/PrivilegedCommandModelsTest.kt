package me.rerere.rikkahub.privilege

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedCommandModelsTest {
    @Test
    fun `tool input accepts argv and shell modes with bounded defaults`() {
        val argv = PrivilegedCommandJson.decodeToolInput(
            Json.parseToJsonElement(
                """{"mode":"argv","executable":"/system/bin/pm","arguments":["list","packages"]}""",
            ),
        )
        val shell = PrivilegedCommandJson.decodeToolInput(
            Json.parseToJsonElement(
                """{"mode":"shell","command":"echo hello | tr a-z A-Z"}""",
            ),
        )

        assertEquals(PrivilegedCommandMode.ARGV, argv.mode)
        assertEquals(PrivilegedCommandLimits.DEFAULT_TIMEOUT_MS, argv.timeoutMs)
        assertEquals(PrivilegedCommandLimits.DEFAULT_OUTPUT_BYTES, argv.maxOutputBytes)
        assertEquals(PrivilegedCommandMode.SHELL, shell.mode)
    }

    @Test
    fun `tool input cannot provide command id or unknown fields`() {
        val error = assertThrows(SerializationException::class.java) {
            PrivilegedCommandJson.decodeToolInput(
                Json.parseToJsonElement(
                    """{"mode":"shell","command":"id","command_id":"attacker-selected"}""",
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("command_id"))
    }

    @Test
    fun `validation rejects invalid mode fields nul bytes and oversized limits`() {
        assertEquals(
            "INVALID_ARGUMENTS",
            PrivilegedCommandInput(
                mode = PrivilegedCommandMode.ARGV,
                executable = "",
            ).validate().code,
        )
        assertEquals(
            "INVALID_ARGUMENTS",
            PrivilegedCommandInput(
                mode = PrivilegedCommandMode.SHELL,
                command = "id\u0000whoami",
            ).validate().code,
        )
        assertEquals(
            "INVALID_ARGUMENTS",
            PrivilegedCommandInput(
                mode = PrivilegedCommandMode.SHELL,
                command = "id",
                timeoutMs = PrivilegedCommandLimits.MAX_TIMEOUT_MS + 1,
            ).validate().code,
        )
        assertEquals(
            "INVALID_ARGUMENTS",
            PrivilegedCommandInput(
                mode = PrivilegedCommandMode.SHELL,
                command = "id",
                maxOutputBytes = PrivilegedCommandLimits.MAX_COMBINED_OUTPUT_BYTES + 1,
            ).validate().code,
        )
    }

    @Test
    fun `request and result json round trip without losing command status`() {
        val request = PrivilegedCommandRequest(
            commandId = "1a8be8ba-829c-4fd5-932d-a548de1997ad",
            input = PrivilegedCommandInput(
                mode = PrivilegedCommandMode.SHELL,
                command = "printf hello",
            ),
        )
        val result = PrivilegedCommandResult(
            ok = false,
            code = "NON_ZERO_EXIT",
            message = "Command exited with code 2.",
            data = PrivilegedCommandResultData(
                commandId = request.commandId,
                exitCode = 2,
                stderr = "bad argument",
                privilege = "shell",
            ),
        )

        assertEquals(request, PrivilegedCommandJson.decodeRequest(PrivilegedCommandJson.encodeRequest(request)))
        assertEquals(result, PrivilegedCommandJson.decodeResult(PrivilegedCommandJson.encodeResult(result)))
    }
}
