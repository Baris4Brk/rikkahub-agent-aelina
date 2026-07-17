package me.rerere.rikkahub.privilege

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolResult
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeActionResult
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridge
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridgePrivilege
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridgeStatus
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegePackageList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.uuid.Uuid

class PrivilegedShellToolTest {
    private val assistantId = Uuid.random()
    private val conversationId = Uuid.random()
    private val privileged = PrivilegedSessionContext(
        assistantId = assistantId,
        conversationId = conversationId,
        origin = ToolCallOrigin.LocalChat,
        privilegedConversationId = conversationId,
        identityName = "Second user",
        isPrivileged = true,
        expandLocalTools = true,
        autoApproveTools = true,
        unrestrictedOverride = true,
    )

    @Test
    fun `shell is injected only for ready privileged local foreground session`() {
        val ready = readyStatus()
        assertTrue(shouldInjectPrivilegedShell(privileged, ToolCallOrigin.LocalChat, false, true, ready))
        assertTrue(
            shouldInjectPrivilegedShell(
                privileged.copy(origin = ToolCallOrigin.SystemAssistant),
                ToolCallOrigin.SystemAssistant,
                false,
                true,
                ready,
            ),
        )
        assertFalse(
            shouldInjectPrivilegedShell(
                privileged.copy(origin = ToolCallOrigin.SystemAssistantKeyguard),
                ToolCallOrigin.SystemAssistantKeyguard,
                false,
                true,
                ready,
            ),
        )
        assertFalse(shouldInjectPrivilegedShell(privileged, ToolCallOrigin.Telegram, false, true, ready))
        assertFalse(shouldInjectPrivilegedShell(privileged, ToolCallOrigin.LocalChat, true, true, ready))
        assertFalse(shouldInjectPrivilegedShell(privileged, ToolCallOrigin.LocalChat, false, false, ready))
        assertFalse(
            shouldInjectPrivilegedShell(
                privileged.copy(isPrivileged = false),
                ToolCallOrigin.LocalChat,
                false,
                true,
                ready,
            ),
        )
        assertFalse(
            shouldInjectPrivilegedShell(
                privileged,
                ToolCallOrigin.LocalChat,
                false,
                true,
                ready.copy(permissionGranted = false),
            ),
        )
    }

    @Test
    fun `tool accepts argv input and model cannot choose command id`() = runBlocking {
        val bridge = CapturingBridge()
        val registration = createExternalBridgeRunCommandTool(bridge)
        val valid = registration.definition.execute(
            Json.parseToJsonElement(
                """{"mode":"argv","executable":"/system/bin/id","arguments":[]}""",
            ),
        )
        val invalid = registration.definition.execute(
            Json.parseToJsonElement(
                """{"mode":"shell","command":"id","command_id":"attacker"}""",
            ),
        )

        assertEquals("/system/bin/id", bridge.lastInput?.executable)
        assertTrue((valid.single() as UIMessagePart.Text).text.contains("OK"))
        val invalidJson = Json.parseToJsonElement((invalid.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("INVALID_ARGUMENTS", invalidJson["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `approval defaults remain defensive even though privileged session auto approves`() {
        assertTrue(ToolApprovalDefaults.requiresApproval(PRIVILEGED_SHELL_TOOL_NAME))
        assertFalse(ToolApprovalDefaults.allowsAlwaysAllow(PRIVILEGED_SHELL_TOOL_NAME))
    }

    private fun readyStatus() = ExternalPrivilegeBridgeStatus(
        installed = true,
        binderAvailable = true,
        permissionGranted = true,
        permissionPermanentlyDenied = false,
        apiVersion = 13,
        serverVersion = "13.1.5",
        serverUid = 2_000,
        privilege = ExternalPrivilegeBridgePrivilege.Shell,
        userServiceAvailable = true,
    )

    private class CapturingBridge : ExternalPrivilegeBridge {
        var lastInput: PrivilegedCommandInput? = null

        override fun status() = ExternalPrivilegeBridgeStatus(
            true, true, true, false, 13, "13.1.5", 2_000,
            ExternalPrivilegeBridgePrivilege.Shell, true,
        )

        override suspend fun startCommand(input: PrivilegedCommandInput): ToolExecutionHandle {
            lastInput = input
            return ResultHandle(
                listOf(
                    UIMessagePart.Text(
                        PrivilegedCommandJson.encodeResult(
                            PrivilegedCommandResult(true, "OK", "Command completed."),
                        ),
                    ),
                ),
            )
        }

        override suspend fun cancelAllCommands() =
            PrivilegedCommandResult(true, "OK", "No commands are running.")

        override suspend fun listPackages() = ExternalPrivilegePackageList(emptyList(), false)
        override suspend fun forceStopApp(packageName: String) =
            ExternalPrivilegeActionResult(true, "OK", "Stopped.")
        override suspend fun clearAppCache(packageName: String) =
            ExternalPrivilegeActionResult(true, "OK", "Cleared.")
        override fun requestPermission() = Unit
    }

    private class ResultHandle(private val result: ToolResult) : ToolExecutionHandle {
        override val executionId: String = Uuid.random().toString()
        override suspend fun awaitResult(): ToolResult = result
        override fun requestCancel(reason: ToolCancelReason) = CancelRequestResult.NotFound
        override suspend fun awaitTermination(gracePeriod: Duration) =
            ToolTerminationState.StoppedConfirmed
    }
}
