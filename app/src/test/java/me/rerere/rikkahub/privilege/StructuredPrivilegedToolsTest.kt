package me.rerere.rikkahub.privilege

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
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

class StructuredPrivilegedToolsTest {
    @Test
    fun `registration contains exactly thirteen matching definitions and startables`() {
        val registration = createStructuredPrivilegedTools(executor(RecordingBridge()))

        assertEquals(13, STRUCTURED_PRIVILEGED_TOOL_NAMES.size)
        assertEquals(STRUCTURED_PRIVILEGED_TOOL_NAMES, registration.definitions.map { it.name }.toSet())
        assertEquals(STRUCTURED_PRIVILEGED_TOOL_NAMES, registration.startables.keys)
    }

    @Test
    fun `every structured tool rejects model supplied command id before bridge execution`() = runBlocking {
        val bridge = RecordingBridge()
        val registration = createStructuredPrivilegedTools(executor(bridge))
        val minimalInputs = mapOf(
            "privileged_settings_get" to """{"namespace":"system","key":"screen_brightness"}""",
            "privileged_settings_put" to """{"namespace":"system","key":"screen_brightness","value":"100"}""",
            "privileged_settings_delete" to """{"namespace":"system","key":"screen_brightness"}""",
            "privileged_appop_get" to """{"package_name":"com.example.app","op":"CAMERA"}""",
            "privileged_appop_set" to """{"package_name":"com.example.app","op":"CAMERA","mode":"allow"}""",
            "privileged_appop_reset" to """{"package_name":"com.example.app","op":"CAMERA"}""",
            "privileged_permission_status" to """{"package_name":"com.example.app","permission":"android.permission.CAMERA"}""",
            "privileged_permission_grant" to """{"package_name":"com.example.app","permission":"android.permission.CAMERA"}""",
            "privileged_permission_revoke" to """{"package_name":"com.example.app","permission":"android.permission.CAMERA"}""",
            "privileged_package_inspect" to """{"package_name":"com.example.app"}""",
            "privileged_dumpsys" to """{"service":"battery"}""",
            "privileged_process_list" to "{}",
            "privileged_service_status" to """{"target":"shizuku"}""",
        )

        minimalInputs.forEach { (toolName, rawInput) ->
            val input = Json.parseToJsonElement(rawInput).jsonObject
            val withCommandId = JsonObject(input + ("commandId" to JsonPrimitive("model-owned")))
            val result = registration.startables.getValue(toolName)
                .start(withCommandId, executionContext())
                .awaitResult()
                .single() as UIMessagePart.Text

            assertEquals(
                "$toolName must reject unknown commandId",
                "INVALID_ARGUMENT",
                Json.parseToJsonElement(result.text).jsonObject["code"]!!.jsonPrimitive.content,
            )
        }

        assertTrue(bridge.inputs.isEmpty())
    }

    @Test
    fun `valid setting read routes through executor with internal current user`() = runBlocking {
        val bridge = RecordingBridge(outputs = ArrayDeque(listOf("120\n")))
        val registration = createStructuredPrivilegedTools(executor(bridge))
        val result = registration.startables.getValue("privileged_settings_get")
            .start(
                buildJsonObject {
                    put("namespace", "system")
                    put("key", "screen_brightness")
                },
                executionContext(),
            )
            .awaitResult()
            .single() as UIMessagePart.Text

        assertEquals("SETTING_READ", Json.parseToJsonElement(result.text).jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("--user", "0", "get", "system", "screen_brightness"),
            bridge.inputs.single().arguments,
        )
    }

    @Test
    fun `invalid enums types and false verification are rejected without unsafe commands`() = runBlocking {
        val bridge = RecordingBridge()
        val registration = createStructuredPrivilegedTools(executor(bridge))
        val invalidInputs = mapOf(
            "privileged_settings_get" to """{"namespace":"invalid","key":"screen_brightness"}""",
            "privileged_settings_put" to """{"namespace":"system","key":"screen_brightness","value":"1","verify":false}""",
            "privileged_appop_set" to """{"package_name":"com.example.app","op":"CAMERA","mode":"sometimes"}""",
            "privileged_process_list" to """{"max_processes":"500"}""",
            "privileged_dumpsys" to """{"service":"not_allowed"}""",
        )

        invalidInputs.forEach { (toolName, rawInput) ->
            val result = registration.startables.getValue(toolName)
                .start(Json.parseToJsonElement(rawInput), executionContext())
                .awaitResult().single() as UIMessagePart.Text
            assertEquals("INVALID_ARGUMENT", Json.parseToJsonElement(result.text)
                .jsonObject["code"]!!.jsonPrimitive.content)
        }
        assertTrue(bridge.inputs.isEmpty())
    }

    @Test
    fun `structured tools inject only into ready privileged local foreground chat`() {
        val privileged = privilegedContext(ToolCallOrigin.LocalChat)
        assertTrue(
            shouldInjectStructuredPrivilegedTools(
                privileged,
                ToolCallOrigin.LocalChat,
                isHeadless = false,
                privilegedBridgeEnabled = true,
                bridgeStatus = readyStatus(),
            ),
        )
        assertTrue(
            shouldInjectStructuredPrivilegedTools(
                privileged.copy(origin = ToolCallOrigin.SystemAssistant),
                ToolCallOrigin.SystemAssistant,
                isHeadless = false,
                privilegedBridgeEnabled = true,
                bridgeStatus = readyStatus(),
            ),
        )
        assertFalse(
            shouldInjectStructuredPrivilegedTools(
                privileged.copy(origin = ToolCallOrigin.SystemAssistantKeyguard),
                ToolCallOrigin.SystemAssistantKeyguard,
                isHeadless = false,
                privilegedBridgeEnabled = true,
                bridgeStatus = readyStatus(),
            ),
        )
        assertFalse(
            shouldInjectStructuredPrivilegedTools(
                privileged.copy(isPrivileged = false),
                ToolCallOrigin.LocalChat,
                isHeadless = false,
                privilegedBridgeEnabled = true,
                bridgeStatus = readyStatus(),
            ),
        )
        assertFalse(
            shouldInjectStructuredPrivilegedTools(
                privileged.copy(origin = ToolCallOrigin.Telegram),
                ToolCallOrigin.Telegram,
                isHeadless = false,
                privilegedBridgeEnabled = true,
                bridgeStatus = readyStatus(),
            ),
        )
        assertFalse(
            shouldInjectStructuredPrivilegedTools(
                privileged,
                ToolCallOrigin.LocalChat,
                isHeadless = true,
                privilegedBridgeEnabled = true,
                bridgeStatus = readyStatus(),
            ),
        )
        assertFalse(
            shouldInjectStructuredPrivilegedTools(
                privileged,
                ToolCallOrigin.LocalChat,
                isHeadless = false,
                privilegedBridgeEnabled = false,
                bridgeStatus = readyStatus(),
            ),
        )
        assertFalse(
            shouldInjectStructuredPrivilegedTools(
                privileged,
                ToolCallOrigin.LocalChat,
                isHeadless = false,
                privilegedBridgeEnabled = true,
                bridgeStatus = readyStatus().copy(userServiceAvailable = false),
            ),
        )
    }

    @Test
    fun `v2 registration contains exactly thirteen matching definitions and startables`() {
        val registration = createStructuredPrivilegedV2Tools(executor(RecordingBridge()))

        assertEquals(13, STRUCTURED_PRIVILEGED_V2_TOOL_NAMES.size)
        assertEquals(STRUCTURED_PRIVILEGED_V2_TOOL_NAMES, registration.definitions.map { it.name }.toSet())
        assertEquals(STRUCTURED_PRIVILEGED_V2_TOOL_NAMES, registration.startables.keys)
    }

    @Test
    fun `every v2 tool rejects model supplied command id before bridge execution`() = runBlocking {
        val bridge = RecordingBridge()
        val registration = createStructuredPrivilegedV2Tools(executor(bridge))
        val minimalInputs = mapOf(
            "privileged_package_enable" to """{"package_name":"com.example.app"}""",
            "privileged_package_disable" to """{"package_name":"com.example.app"}""",
            "privileged_package_suspend" to """{"package_name":"com.example.app"}""",
            "privileged_package_unsuspend" to """{"package_name":"com.example.app"}""",
            "privileged_package_uninstall" to """{"package_name":"com.example.app"}""",
            "privileged_resolve_intent" to """{"action":"android.intent.action.VIEW"}""",
            "privileged_query_activities" to """{"action":"android.intent.action.VIEW"}""",
            "privileged_start_activity" to """{"action":"android.intent.action.VIEW"}""",
            "privileged_send_broadcast" to """{"action":"com.example.ACTION"}""",
            "privileged_logcat_read" to "{}",
            "privileged_window_state" to "{}",
            "privileged_job_status" to "{}",
            "privileged_alarm_status" to "{}",
        )

        minimalInputs.forEach { (toolName, rawInput) ->
            val input = Json.parseToJsonElement(rawInput).jsonObject
            val withCommandId = JsonObject(input + ("commandId" to JsonPrimitive("model-owned")))
            val result = registration.startables.getValue(toolName)
                .start(withCommandId, executionContext())
                .awaitResult()
                .single() as UIMessagePart.Text

            assertEquals(
                "$toolName must reject unknown commandId",
                "INVALID_ARGUMENT",
                Json.parseToJsonElement(result.text).jsonObject["code"]!!.jsonPrimitive.content,
            )
        }
        assertTrue(bridge.inputs.isEmpty())
    }

    @Test
    fun `v2 intent rejects non primitive extras and raw shell shaped fields`() = runBlocking {
        val bridge = RecordingBridge()
        val registration = createStructuredPrivilegedV2Tools(executor(bridge))
        val invalid = listOf(
            Json.parseToJsonElement(
                """{"action":"android.intent.action.VIEW","extras":{"nested":{"bad":true}}}""",
            ),
            Json.parseToJsonElement(
                """{"action":"android.intent.action.VIEW","shell":"am start -a android.intent.action.VIEW"}""",
            ),
            Json.parseToJsonElement(
                """{"action":"android.intent.action.VIEW","user_id":10}""",
            ),
        )

        invalid.forEach { input ->
            val result = registration.startables.getValue("privileged_start_activity")
                .start(input, executionContext())
                .awaitResult().single() as UIMessagePart.Text
            assertEquals(
                "INVALID_ARGUMENT",
                Json.parseToJsonElement(result.text).jsonObject["code"]!!.jsonPrimitive.content,
            )
        }
        assertTrue(bridge.inputs.isEmpty())
    }

    @Test
    fun `v2 tools inject only into ready privileged local foreground chat`() {
        val privileged = privilegedContext(ToolCallOrigin.LocalChat)
        assertTrue(
            shouldInjectStructuredPrivilegedV2Tools(
                privileged,
                ToolCallOrigin.LocalChat,
                isHeadless = false,
                privilegedBridgeEnabled = true,
                bridgeStatus = readyStatus(),
            ),
        )
        assertTrue(
            shouldInjectStructuredPrivilegedV2Tools(
                privileged.copy(origin = ToolCallOrigin.SystemAssistant),
                ToolCallOrigin.SystemAssistant,
                isHeadless = false,
                privilegedBridgeEnabled = true,
                bridgeStatus = readyStatus(),
            ),
        )
        assertFalse(
            shouldInjectStructuredPrivilegedV2Tools(
                privileged.copy(origin = ToolCallOrigin.SystemAssistantKeyguard),
                ToolCallOrigin.SystemAssistantKeyguard,
                isHeadless = false,
                privilegedBridgeEnabled = true,
                bridgeStatus = readyStatus(),
            ),
        )
        assertFalse(
            shouldInjectStructuredPrivilegedV2Tools(
                privileged.copy(isPrivileged = false),
                ToolCallOrigin.LocalChat,
                isHeadless = false,
                privilegedBridgeEnabled = true,
                bridgeStatus = readyStatus(),
            ),
        )
        assertFalse(
            shouldInjectStructuredPrivilegedV2Tools(
                privileged,
                ToolCallOrigin.Telegram,
                isHeadless = false,
                privilegedBridgeEnabled = true,
                bridgeStatus = readyStatus(),
            ),
        )
        assertFalse(
            shouldInjectStructuredPrivilegedV2Tools(
                privileged,
                ToolCallOrigin.LocalChat,
                isHeadless = true,
                privilegedBridgeEnabled = true,
                bridgeStatus = readyStatus(),
            ),
        )
        assertFalse(
            shouldInjectStructuredPrivilegedV2Tools(
                privileged,
                ToolCallOrigin.LocalChat,
                isHeadless = false,
                privilegedBridgeEnabled = true,
                bridgeStatus = readyStatus(),
                deviceLocked = true,
            ),
        )
    }

    @Test
    fun `v2 package mutation refuses critical system package before bridge execution`() = runBlocking {
        val bridge = RecordingBridge()
        val registration = createStructuredPrivilegedV2Tools(
            executor(
                bridge = bridge,
                criticalSystemPackages = setOf("com.android.systemui"),
            )
        )

        val result = registration.startables.getValue("privileged_package_disable")
            .start(
                Json.parseToJsonElement(
                    """{"package_name":"com.android.systemui"}""",
                ),
                executionContext(),
            )
            .awaitResult().single() as UIMessagePart.Text

        assertEquals(
            "SYSTEM_PACKAGE_PROTECTED",
            Json.parseToJsonElement(result.text).jsonObject["code"]!!.jsonPrimitive.content,
        )
        assertTrue(bridge.inputs.isEmpty())
    }

    private fun executor(
        bridge: ExternalPrivilegeBridge,
        criticalSystemPackages: Set<String> = emptySet(),
    ) = StructuredPrivilegedCommandExecutor(
        bridge = bridge,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        packageMetadataReader = object : PrivilegedPackageMetadataReader {
            override val currentUserId: Int = 0
            override fun packageMetadata(packageName: String) = StructuredPackageMetadata(packageName)
            override fun permissionMetadata(packageName: String, permission: String) =
                StructuredPermissionMetadata(
                    packageName = packageName,
                    permission = permission,
                    declared = true,
                    granted = false,
                    runtime = true,
                    shellMayManage = true,
                )
        },
        runtimeStatusProvider = EmptyRuntimeStatusProvider,
        protectedPackages = emptySet(),
        criticalSystemPackages = criticalSystemPackages,
        isEmergencyStopActive = { false },
    )

    private fun executionContext() = ToolExecutionContext(
        runId = Uuid.random(),
        conversationId = Uuid.random(),
        assistantId = Uuid.random().toString(),
        callOrigin = ToolCallOrigin.LocalChat,
    )

    private fun privilegedContext(origin: ToolCallOrigin): PrivilegedSessionContext {
        val assistantId = Uuid.random()
        val conversationId = Uuid.random()
        return PrivilegedSessionContext(
            assistantId = assistantId,
            conversationId = conversationId,
            origin = origin,
            privilegedConversationId = conversationId,
            identityName = "Second user",
            isPrivileged = true,
            expandLocalTools = true,
            autoApproveTools = true,
            unrestrictedOverride = origin == ToolCallOrigin.LocalChat,
        )
    }

    private fun readyStatus() = ExternalPrivilegeBridgeStatus(
        installed = true,
        binderAvailable = true,
        permissionGranted = true,
        permissionPermanentlyDenied = false,
        apiVersion = 13,
        serverVersion = "test",
        serverUid = 2_000,
        privilege = ExternalPrivilegeBridgePrivilege.Shell,
        userServiceAvailable = true,
    )

    private class RecordingBridge(
        private val outputs: ArrayDeque<String> = ArrayDeque(),
    ) : ExternalPrivilegeBridge {
        val inputs = mutableListOf<PrivilegedCommandInput>()

        override fun status() = ExternalPrivilegeBridgeStatus(
            installed = true,
            binderAvailable = true,
            permissionGranted = true,
            permissionPermanentlyDenied = false,
            apiVersion = 13,
            serverVersion = "test",
            serverUid = 2_000,
            privilege = ExternalPrivilegeBridgePrivilege.Shell,
            userServiceAvailable = true,
        )
        override suspend fun startCommand(input: PrivilegedCommandInput): ToolExecutionHandle {
            inputs += input
            val output = outputs.removeFirstOrNull().orEmpty()
            return ImmediateHandle(
                PrivilegedCommandResult(
                    ok = true,
                    code = "OK",
                    message = "Command completed.",
                    data = PrivilegedCommandResultData(
                        commandId = Uuid.random().toString(),
                        exitCode = 0,
                        stdout = output,
                        privilege = "shell",
                    ),
                ),
            )
        }

        override suspend fun cancelAllCommands() = PrivilegedCommandResult(true, "OK", "None running.")
        override suspend fun listPackages() = ExternalPrivilegePackageList(emptyList(), false)
        override suspend fun forceStopApp(packageName: String) = ExternalPrivilegeActionResult(true, "OK", "Stopped.")
        override suspend fun clearAppCache(packageName: String) = ExternalPrivilegeActionResult(true, "OK", "Cleared.")
        override fun requestPermission() = Unit
    }

    private class ImmediateHandle(
        private val commandResult: PrivilegedCommandResult,
    ) : ToolExecutionHandle {
        override val executionId: String = commandResult.data?.commandId ?: Uuid.random().toString()
        override suspend fun awaitResult(): ToolResult = listOf(
            UIMessagePart.Text(PrivilegedCommandJson.encodeResult(commandResult)),
        )
        override fun requestCancel(reason: ToolCancelReason) = CancelRequestResult.NotFound
        override suspend fun awaitTermination(gracePeriod: Duration) = ToolTerminationState.StoppedConfirmed
    }
}
