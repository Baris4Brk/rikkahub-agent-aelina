package me.rerere.rikkahub.privilege

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.uuid.Uuid

class StructuredPrivilegedCommandExecutorTest {
    @Test
    fun `setting put reads old value writes and verifies actual value`() = runBlocking {
        val bridge = ScriptedBridge(
            outputs = ArrayDeque(listOf("120\n", "", "150\n")),
        )
        val executor = StructuredPrivilegedCommandExecutor(
            bridge = bridge,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            packageMetadataReader = EmptyPackageMetadataReader,
            runtimeStatusProvider = EmptyRuntimeStatusProvider,
            protectedPackages = emptySet(),
            isEmergencyStopActive = { false },
        )

        val result = executor.start(
            StructuredPrivilegedOperation.SettingPut(
                namespace = StructuredSettingNamespace.SYSTEM,
                key = "screen_brightness",
                value = "150",
            ),
        ).awaitResult().single() as UIMessagePart.Text
        val json = Json.parseToJsonElement(result.text).jsonObject

        assertEquals(
            listOf(
                listOf("--user", "0", "get", "system", "screen_brightness"),
                listOf("--user", "0", "put", "system", "screen_brightness", "150"),
                listOf("--user", "0", "get", "system", "screen_brightness"),
            ),
            bridge.inputs.map { it.arguments },
        )
        assertTrue(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("SETTING_UPDATED", json["code"]!!.jsonPrimitive.content)
        assertTrue(json["verified"]!!.jsonPrimitive.content.toBoolean())
        val data = json["data"]!!.jsonObject
        assertEquals("120", data["old_value"]!!.jsonPrimitive.content)
        assertEquals("150", data["requested_value"]!!.jsonPrimitive.content)
        assertEquals("150", data["actual_value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `setting delete reads old value deletes and verifies absence`() = runBlocking {
        val bridge = ScriptedBridge(ArrayDeque(listOf("old\n", "", "null\n")))

        val result = executor(bridge).start(
            StructuredPrivilegedOperation.SettingDelete(
                StructuredSettingNamespace.GLOBAL,
                "example_key",
            ),
        ).awaitResult().single() as UIMessagePart.Text
        val json = Json.parseToJsonElement(result.text).jsonObject

        assertEquals("SETTING_DELETED", json["code"]!!.jsonPrimitive.content)
        assertEquals("true", json["verified"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("get", "delete", "get"),
            bridge.inputs.map { it.arguments[2] },
        )
    }

    @Test
    fun `secure assistant settings cannot be put or deleted with any key casing`() = runBlocking {
        val protectedKeys = listOf(
            "assistant",
            "VOICE_INTERACTION_SERVICE",
            "voice_recognition_service",
            "MagicVoice_Stop_Status",
            "magic_voice_service_state",
            "invoke_hivoice_keypress_type",
            "power_invoke_hivoice_used",
            "hw_long_home_voice_assistant",
        )
        val bridge = ScriptedBridge(ArrayDeque())
        val executor = executor(bridge)

        protectedKeys.forEach { key ->
            val put = executor.start(
                StructuredPrivilegedOperation.SettingPut(
                    namespace = StructuredSettingNamespace.SECURE,
                    key = key,
                    value = "replacement",
                ),
            ).awaitResult().single() as UIMessagePart.Text
            val delete = executor.start(
                StructuredPrivilegedOperation.SettingDelete(
                    namespace = StructuredSettingNamespace.SECURE,
                    key = key,
                ),
            ).awaitResult().single() as UIMessagePart.Text

            assertEquals("SETTING_PROTECTED", code(put))
            assertEquals("SETTING_PROTECTED", code(delete))
        }
        assertTrue(bridge.inputs.isEmpty())
    }

    @Test
    fun `assistant setting names remain writable outside the secure namespace`() = runBlocking {
        val bridge = ScriptedBridge(
            ArrayDeque(
                listOf(
                    "old\n",
                    "",
                    "replacement\n",
                    "old\n",
                    "",
                    "null\n",
                ),
            ),
        )
        val executor = executor(bridge)

        val put = executor.start(
            StructuredPrivilegedOperation.SettingPut(
                namespace = StructuredSettingNamespace.SYSTEM,
                key = "assistant",
                value = "replacement",
            ),
        ).awaitResult().single() as UIMessagePart.Text
        val delete = executor.start(
            StructuredPrivilegedOperation.SettingDelete(
                namespace = StructuredSettingNamespace.GLOBAL,
                key = "voice_interaction_service",
            ),
        ).awaitResult().single() as UIMessagePart.Text

        assertEquals("SETTING_UPDATED", code(put))
        assertEquals("SETTING_DELETED", code(delete))
        assertEquals(6, bridge.inputs.size)
    }

    @Test
    fun `appop set reads old mode writes and verifies actual mode`() = runBlocking {
        val bridge = ScriptedBridge(
            outputs = ArrayDeque(
                listOf(
                    "RUN_IN_BACKGROUND: ignore; time=+1h\n",
                    "",
                    "RUN_IN_BACKGROUND: allow\n",
                ),
            ),
        )
        val executor = executor(
            bridge = bridge,
            packageMetadataReader = StaticPackageMetadataReader("com.example.target"),
            protectedPackages = setOf("com.example.target"),
        )

        val result = executor.start(
            StructuredPrivilegedOperation.AppOpSet(
                packageName = "com.example.target",
                op = "run_in_background",
                mode = StructuredAppOpMode.ALLOW,
            ),
        ).awaitResult().single() as UIMessagePart.Text
        val json = Json.parseToJsonElement(result.text).jsonObject

        assertEquals(
            listOf(
                listOf("appops", "get", "--user", "0", "com.example.target", "RUN_IN_BACKGROUND"),
                listOf("appops", "set", "--user", "0", "com.example.target", "RUN_IN_BACKGROUND", "allow"),
                listOf("appops", "get", "--user", "0", "com.example.target", "RUN_IN_BACKGROUND"),
            ),
            bridge.inputs.map { it.arguments },
        )
        assertEquals("APPOP_UPDATED", json["code"]!!.jsonPrimitive.content)
        assertTrue(json["verified"]!!.jsonPrimitive.content.toBoolean())
        val data = json["data"]!!.jsonObject
        assertEquals("ignore", data["old_mode"]!!.jsonPrimitive.content)
        assertEquals("allow", data["actual_mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `appop get and single-op reset parse and verify modes`() = runBlocking {
        val packageName = "com.example.target"
        val bridge = ScriptedBridge(
            ArrayDeque(
                listOf(
                    "CAMERA: allow\n",
                    "CAMERA: allow\n",
                    "",
                    "CAMERA: default\n",
                ),
            ),
        )
        val executor = executor(bridge, StaticPackageMetadataReader(packageName))

        val getResult = executor.start(
            StructuredPrivilegedOperation.AppOpGet(packageName, "camera"),
        ).awaitResult().single() as UIMessagePart.Text
        val resetResult = executor.start(
            StructuredPrivilegedOperation.AppOpReset(packageName, "camera"),
        ).awaitResult().single() as UIMessagePart.Text

        assertEquals("APPOP_READ", code(getResult))
        assertEquals("APPOP_RESET", code(resetResult))
        assertEquals("default", bridge.inputs[2].arguments.last())
    }

    @Test
    fun `appop get reports implicit Android state as default`() = runBlocking {
        val packageName = "com.example.target"
        val bridge = ScriptedBridge(ArrayDeque(listOf("No operations.\n")))

        val result = executor(bridge, StaticPackageMetadataReader(packageName)).start(
            StructuredPrivilegedOperation.AppOpGet(packageName, "CAMERA"),
        ).awaitResult().single() as UIMessagePart.Text

        assertEquals("APPOP_READ", code(result))
        assertEquals(
            "default",
            Json.parseToJsonElement(result.text).jsonObject["data"]!!.jsonObject["mode"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `appop reset reports unsupported only for command rejection`() = runBlocking {
        val packageName = "com.example.target"
        val bridge = ResultQueueBridge(
            ArrayDeque(
                listOf(
                    successCommand("CAMERA: allow\n"),
                    PrivilegedCommandResult(false, "NON_ZERO_EXIT", "default mode unsupported"),
                ),
            ),
        )

        val result = executor(bridge, StaticPackageMetadataReader(packageName)).start(
            StructuredPrivilegedOperation.AppOpReset(packageName, "CAMERA"),
        ).awaitResult().single() as UIMessagePart.Text

        assertEquals("APPOP_MODE_UNSUPPORTED", code(result))
    }

    @Test
    fun `appop reset preserves bridge unavailable instead of relabeling it unsupported`() = runBlocking {
        val packageName = "com.example.target"
        val bridge = ResultQueueBridge(
            ArrayDeque(
                listOf(
                    successCommand("CAMERA: allow\n"),
                    PrivilegedCommandResult(false, "BINDER_DIED", "Bridge disconnected."),
                ),
            ),
        )

        val result = executor(bridge, StaticPackageMetadataReader(packageName)).start(
            StructuredPrivilegedOperation.AppOpReset(packageName, "CAMERA"),
        ).awaitResult().single() as UIMessagePart.Text

        assertEquals("BRIDGE_UNAVAILABLE", code(result))
    }

    @Test
    fun `permission grant verifies package manager state and is allowed for protected package`() = runBlocking {
        val bridge = ScriptedBridge(outputs = ArrayDeque(listOf("")))
        val metadata = SequencedPermissionMetadataReader(
            packageName = "com.example.protected",
            permission = "android.permission.POST_NOTIFICATIONS",
            grants = ArrayDeque(listOf(false, true)),
        )
        val executor = executor(
            bridge = bridge,
            packageMetadataReader = metadata,
            protectedPackages = setOf("com.example.protected"),
        )

        val result = executor.start(
            StructuredPrivilegedOperation.PermissionGrant(
                packageName = "com.example.protected",
                permission = "android.permission.POST_NOTIFICATIONS",
            ),
        ).awaitResult().single() as UIMessagePart.Text
        val json = Json.parseToJsonElement(result.text).jsonObject

        assertEquals(
            listOf(
                "grant",
                "--user",
                "0",
                "com.example.protected",
                "android.permission.POST_NOTIFICATIONS",
            ),
            bridge.inputs.single().arguments,
        )
        assertEquals("PERMISSION_GRANTED", json["code"]!!.jsonPrimitive.content)
        assertTrue(json["verified"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(json["data"]!!.jsonObject["actual_granted"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `permission revoke verifies actual package manager state`() = runBlocking {
        val packageName = "com.example.target"
        val permission = "android.permission.CAMERA"
        val metadata = SequencedPermissionMetadataReader(
            packageName,
            permission,
            grants = ArrayDeque(listOf(true, false)),
        )
        val bridge = ScriptedBridge(ArrayDeque(listOf("")))

        val result = executor(bridge, metadata).start(
            StructuredPrivilegedOperation.PermissionRevoke(packageName, permission),
        ).awaitResult().single() as UIMessagePart.Text

        assertEquals("PERMISSION_REVOKED", code(result))
        assertEquals("false", Json.parseToJsonElement(result.text).jsonObject["data"]!!
            .jsonObject["actual_granted"]!!.jsonPrimitive.content)
        assertEquals("revoke", bridge.inputs.single().arguments.first())
    }

    @Test
    fun `signature permission is rejected without invoking the bridge`() = runBlocking {
        val packageName = "com.example.target"
        val permission = "com.example.SIGNATURE_PERMISSION"
        val reader = object : PrivilegedPackageMetadataReader {
            override val currentUserId: Int = 0
            override fun packageMetadata(packageName: String) = StructuredPackageMetadata(packageName)
            override fun permissionMetadata(packageName: String, permission: String) =
                StructuredPermissionMetadata(packageName, permission, true, false, false, false)
        }
        val bridge = ScriptedBridge(ArrayDeque())

        val result = executor(bridge, reader).start(
            StructuredPrivilegedOperation.PermissionGrant(packageName, permission),
        ).awaitResult().single() as UIMessagePart.Text

        assertEquals("NOT_SUPPORTED", code(result))
        assertTrue(bridge.inputs.isEmpty())
    }

    @Test
    fun `permission status reports declared false without invoking the bridge`() = runBlocking {
        val packageName = "com.example.target"
        val reader = object : PrivilegedPackageMetadataReader {
            override val currentUserId: Int = 0
            override fun packageMetadata(packageName: String) = StructuredPackageMetadata(packageName)
            override fun permissionMetadata(packageName: String, permission: String) =
                StructuredPermissionMetadata(packageName, permission, false, false, true, false)
        }
        val bridge = ScriptedBridge(ArrayDeque())

        val result = executor(bridge, reader).start(
            StructuredPrivilegedOperation.PermissionStatus(packageName, "android.permission.CAMERA"),
        ).awaitResult().single() as UIMessagePart.Text

        val json = Json.parseToJsonElement(result.text).jsonObject
        assertEquals("PERMISSION_STATUS", json["code"]!!.jsonPrimitive.content)
        assertEquals("false", json["data"]!!.jsonObject["declared"]!!.jsonPrimitive.content)
        assertTrue(bridge.inputs.isEmpty())
    }

    @Test
    fun `package inspect combines package metadata and bounded appops summary`() = runBlocking {
        val bridge = ScriptedBridge(
            outputs = ArrayDeque(listOf("RUN_IN_BACKGROUND: allow\nPOST_NOTIFICATION: ignore\n")),
        )
        val metadata = object : PrivilegedPackageMetadataReader {
            override val currentUserId: Int = 0
            override fun packageMetadata(packageName: String) = StructuredPackageMetadata(
                packageName = packageName,
                label = "Example",
                versionName = "1.2.3",
                versionCode = 12,
                uid = 10_123,
                enabled = true,
                suspended = false,
                stopped = false,
                installSource = "com.android.vending",
                runtimePermissions = listOf(
                    StructuredRuntimePermissionSummary(
                        permission = "android.permission.POST_NOTIFICATIONS",
                        granted = true,
                        shellMayManage = true,
                    ),
                ),
            )

            override fun permissionMetadata(
                packageName: String,
                permission: String,
            ): StructuredPermissionMetadata? = null
        }
        val executor = executor(bridge, metadata, protectedPackages = setOf("com.example.target"))

        val result = executor.start(
            StructuredPrivilegedOperation.PackageInspect("com.example.target"),
        ).awaitResult().single() as UIMessagePart.Text
        val json = Json.parseToJsonElement(result.text).jsonObject
        val data = json["data"]!!.jsonObject

        assertEquals("PACKAGE_INSPECTED", json["code"]!!.jsonPrimitive.content)
        assertEquals("Example", data["label"]!!.jsonPrimitive.content)
        assertTrue(data["protected"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("2", data["appops_count"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dumpsys accepts only a named service and filters output in process`() = runBlocking {
        val bridge = ScriptedBridge(
            outputs = ArrayDeque(listOf("unrelated\nMATCH first\nsecond MATCH\n")),
        )
        val executor = executor(bridge)

        val result = executor.start(
            StructuredPrivilegedOperation.Dumpsys(
                service = "battery",
                filter = "MATCH",
                maxOutputBytes = 131_072,
            ),
        ).awaitResult().single() as UIMessagePart.Text
        val json = Json.parseToJsonElement(result.text).jsonObject

        assertEquals("/system/bin/dumpsys", bridge.inputs.single().executable)
        assertEquals(listOf("battery"), bridge.inputs.single().arguments)
        assertEquals("DUMPSYS_READ", json["code"]!!.jsonPrimitive.content)
        assertEquals(
            "MATCH first\nsecond MATCH",
            json["data"]!!.jsonObject["output"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `process list parses bounded structured process records`() = runBlocking {
        val bridge = ScriptedBridge(
            outputs = ArrayDeque(
                listOf(
                    "PID UID STAT NAME ARGS\n" +
                        "123 1000 S system_server system_server\n" +
                        "456 u0_a123 S com.example.app com.example.app:worker\n",
                ),
            ),
        )
        val executor = executor(bridge)

        val result = executor.start(
            StructuredPrivilegedOperation.ProcessList(maxProcesses = 500),
        ).awaitResult().single() as UIMessagePart.Text
        val json = Json.parseToJsonElement(result.text).jsonObject
        val data = json["data"]!!.jsonObject

        assertEquals("PROCESS_LISTED", json["code"]!!.jsonPrimitive.content)
        assertEquals("2", data["count"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("-A", "-o", "PID,UID,STAT,NAME,ARGS"),
            bridge.inputs.single().arguments,
        )
    }

    @Test
    fun `service status delegates to the runtime status adapter`() = runBlocking {
        val bridge = ScriptedBridge(outputs = ArrayDeque())
        val provider = object : PrivilegedRuntimeStatusProvider {
            override suspend fun status(
                target: String,
                serviceName: String?,
            ) = StructuredPrivilegedResult(
                ok = true,
                code = "SERVICE_STATUS",
                message = "Observed.",
                data = kotlinx.serialization.json.buildJsonObject {
                    put("target", target)
                    put("state", "ready")
                },
            )
        }
        val executor = StructuredPrivilegedCommandExecutor(
            bridge = bridge,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            packageMetadataReader = EmptyPackageMetadataReader,
            runtimeStatusProvider = provider,
            protectedPackages = emptySet(),
            isEmergencyStopActive = { false },
        )

        val result = executor.start(
            StructuredPrivilegedOperation.ServiceStatus(target = "shizuku"),
        ).awaitResult().single() as UIMessagePart.Text
        val json = Json.parseToJsonElement(result.text).jsonObject

        assertEquals("SERVICE_STATUS", json["code"]!!.jsonPrimitive.content)
        assertEquals("ready", json["data"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertTrue(bridge.inputs.isEmpty())
    }

    @Test
    fun `setting verification mismatch is reported without claiming success`() = runBlocking {
        val bridge = ScriptedBridge(ArrayDeque(listOf("old\n", "", "different\n")))

        val result = executor(bridge).start(
            StructuredPrivilegedOperation.SettingPut(
                namespace = StructuredSettingNamespace.SECURE,
                key = "example_key",
                value = "requested",
            ),
        ).awaitResult().single() as UIMessagePart.Text
        val json = Json.parseToJsonElement(result.text).jsonObject

        assertEquals("false", json["ok"]!!.jsonPrimitive.content)
        assertEquals("SETTING_VERIFY_FAILED", json["code"]!!.jsonPrimitive.content)
        assertEquals("false", json["verified"]!!.jsonPrimitive.content)
        assertEquals("different", json["data"]!!.jsonObject["actual_value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `protected resources allow strengthening but reject weakening operations`() = runBlocking {
        val packageName = "com.example.protected"
        val reader = StaticPackageMetadataReader(packageName)
        val bridge = ScriptedBridge(ArrayDeque())
        val executor = executor(bridge, reader, setOf(packageName))

        val appOpReset = executor.start(
            StructuredPrivilegedOperation.AppOpReset(packageName, "CAMERA"),
        ).awaitResult().single() as UIMessagePart.Text
        val permissionRevoke = executor.start(
            StructuredPrivilegedOperation.PermissionRevoke(packageName, "android.permission.CAMERA"),
        ).awaitResult().single() as UIMessagePart.Text
        val protectedSetting = executor.start(
            StructuredPrivilegedOperation.SettingDelete(
                StructuredSettingNamespace.SECURE,
                "enabled_accessibility_services",
            ),
        ).awaitResult().single() as UIMessagePart.Text

        assertEquals("PROTECTED_RESOURCE", code(appOpReset))
        assertEquals("PROTECTED_RESOURCE", code(permissionRevoke))
        assertEquals("SETTING_PROTECTED", code(protectedSetting))
        assertTrue(bridge.inputs.isEmpty())
    }

    @Test
    fun `active voice service packages cannot be disabled suspended or uninstalled`() = runBlocking {
        val voicePackages = activeStructuredVoiceServicePackages(
            voiceInteractionService =
                "com.hihonor.magicvoice/com.hihonor.magicvoice.voiceui.service.MagicVoiceInteractionService",
            voiceRecognitionService =
                "me.rerere.rikkahub.assistantproxy/.ProxyRecognitionService",
        )
        val bridge = ScriptedBridge(ArrayDeque())
        val executor = executor(
            bridge = bridge,
            packageMetadataReader = StaticPackageMetadataReader(*voicePackages.toTypedArray()),
            criticalSystemPackages = voicePackages,
        )

        val operations = voicePackages.flatMap { packageName ->
            listOf(
                StructuredPrivilegedOperation.PackageDisable(packageName),
                StructuredPrivilegedOperation.PackageSuspend(packageName),
                StructuredPrivilegedOperation.PackageUninstall(packageName),
            )
        }
        operations.forEach { operation ->
            val result = executor.start(operation).awaitResult().single() as UIMessagePart.Text
            assertEquals("SYSTEM_PACKAGE_PROTECTED", code(result))
        }

        assertEquals(
            setOf("com.hihonor.magicvoice", "me.rerere.rikkahub.assistantproxy"),
            voicePackages,
        )
        assertTrue(bridge.inputs.isEmpty())
    }

    @Test
    fun `active voice package parser ignores malformed component settings`() {
        assertTrue(
            activeStructuredVoiceServicePackages(
                voiceInteractionService = "com.example.voice/.VoiceService extra",
                voiceRecognitionService = "not-a-component",
            ).isEmpty(),
        )
        assertEquals(
            setOf("com.example.voice"),
            activeStructuredVoiceServicePackages(
                voiceInteractionService = "  com.example.voice/.VoiceService  ",
                voiceRecognitionService = null,
            ),
        )
    }

    @Test
    fun `emergency stop between sequential commands prevents the next write`() = runBlocking {
        val emergencyStop = AtomicBoolean(false)
        val bridge = ScriptedBridge(
            outputs = ArrayDeque(listOf("old\n")),
            onStart = { call -> if (call == 1) emergencyStop.set(true) },
        )
        val executor = StructuredPrivilegedCommandExecutor(
            bridge = bridge,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            packageMetadataReader = EmptyPackageMetadataReader,
            runtimeStatusProvider = EmptyRuntimeStatusProvider,
            protectedPackages = emptySet(),
            isEmergencyStopActive = emergencyStop::get,
        )

        val result = executor.start(
            StructuredPrivilegedOperation.SettingPut(
                StructuredSettingNamespace.SYSTEM,
                "screen_brightness",
                "200",
            ),
        ).awaitResult().single() as UIMessagePart.Text

        assertEquals("EMERGENCY_STOP_ACTIVE", code(result))
        assertEquals(1, bridge.inputs.size)
    }

    @Test
    fun `composite handle cancels the currently running bridge command`() = runBlocking {
        val bridge = BlockingBridge()
        val handle = executor(bridge).start(
            StructuredPrivilegedOperation.SettingGet(
                StructuredSettingNamespace.SYSTEM,
                "screen_brightness",
            ),
        )
        val waiting = async { handle.awaitResult().single() as UIMessagePart.Text }
        withTimeout(2_000) { bridge.started.await() }

        assertEquals(CancelRequestResult.Requested, handle.requestCancel(ToolCancelReason.USER_INTERRUPTED))
        val result = withTimeout(2_000) { waiting.await() }

        assertEquals("COMMAND_CANCELLED", code(result))
        assertEquals(1, bridge.cancelRequests)
    }

    @Test
    fun `cancelling the waiting generation coroutine cancels the bridge command`() = runBlocking {
        val bridge = BlockingBridge()
        val handle = executor(bridge).start(
            StructuredPrivilegedOperation.SettingGet(
                StructuredSettingNamespace.SYSTEM,
                "screen_brightness",
            ),
        )
        val waiting = async { handle.awaitResult() }
        withTimeout(2_000) { bridge.started.await() }

        waiting.cancelAndJoin()

        assertEquals(1, bridge.cancelRequests)
    }

    @Test
    fun `process list falls back when structured ps format is unsupported`() = runBlocking {
        val bridge = ResultQueueBridge(
            ArrayDeque(
                listOf(
                    PrivilegedCommandResult(false, "NON_ZERO_EXIT", "Unsupported ps format."),
                    successCommand("USER PID NAME\nu0_a1 42 com.example.app\n"),
                ),
            ),
        )

        val result = executor(bridge).start(
            StructuredPrivilegedOperation.ProcessList(),
        ).awaitResult().single() as UIMessagePart.Text
        val json = Json.parseToJsonElement(result.text).jsonObject

        assertEquals("PROCESS_LISTED", json["code"]!!.jsonPrimitive.content)
        assertEquals("fallback", json["data"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertEquals(listOf("-A"), bridge.inputs.last().arguments)
    }

    @Test
    fun `binder service status distinguishes found from not found`() = runBlocking {
        val bridge = ScriptedBridge(
            ArrayDeque(
                listOf(
                    "Service battery: found\n",
                    "Service battery: not found\n",
                ),
            ),
        )
        val executor = executor(bridge)

        val found = executor.start(
            StructuredPrivilegedOperation.ServiceStatus("android_binder", "battery"),
        ).awaitResult().single() as UIMessagePart.Text
        val missing = executor.start(
            StructuredPrivilegedOperation.ServiceStatus("android_binder", "battery"),
        ).awaitResult().single() as UIMessagePart.Text

        assertEquals("true", Json.parseToJsonElement(found.text).jsonObject["data"]!!
            .jsonObject["found"]!!.jsonPrimitive.content)
        assertEquals("false", Json.parseToJsonElement(missing.text).jsonObject["data"]!!
            .jsonObject["found"]!!.jsonPrimitive.content)
    }

    private fun executor(
        bridge: ExternalPrivilegeBridge,
        packageMetadataReader: PrivilegedPackageMetadataReader = EmptyPackageMetadataReader,
        protectedPackages: Set<String> = emptySet(),
        criticalSystemPackages: Set<String> = emptySet(),
    ) = StructuredPrivilegedCommandExecutor(
        bridge = bridge,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        packageMetadataReader = packageMetadataReader,
        runtimeStatusProvider = EmptyRuntimeStatusProvider,
        protectedPackages = protectedPackages,
        criticalSystemPackages = criticalSystemPackages,
        isEmergencyStopActive = { false },
    )

    private fun code(part: UIMessagePart.Text): String =
        Json.parseToJsonElement(part.text).jsonObject["code"]!!.jsonPrimitive.content

    private fun successCommand(stdout: String) = PrivilegedCommandResult(
        ok = true,
        code = "OK",
        message = "Command completed.",
        data = PrivilegedCommandResultData(
            commandId = Uuid.random().toString(),
            exitCode = 0,
            stdout = stdout,
            privilege = "shell",
        ),
    )

    private class StaticPackageMetadataReader(
        private vararg val packages: String,
    ) : PrivilegedPackageMetadataReader {
        override val currentUserId: Int = 0
        override fun packageMetadata(packageName: String): StructuredPackageMetadata? =
            packageName.takeIf { it in packages }?.let(::StructuredPackageMetadata)

        override fun permissionMetadata(
            packageName: String,
            permission: String,
        ): StructuredPermissionMetadata? = packageName.takeIf { it in packages }?.let {
            StructuredPermissionMetadata(
                packageName = packageName,
                permission = permission,
                declared = true,
                granted = true,
                runtime = true,
                shellMayManage = true,
            )
        }
    }

    private class SequencedPermissionMetadataReader(
        private val packageName: String,
        private val permission: String,
        private val grants: ArrayDeque<Boolean>,
    ) : PrivilegedPackageMetadataReader {
        override val currentUserId: Int = 0
        override fun packageMetadata(packageName: String): StructuredPackageMetadata? =
            packageName.takeIf { it == this.packageName }?.let(::StructuredPackageMetadata)

        override fun permissionMetadata(
            packageName: String,
            permission: String,
        ): StructuredPermissionMetadata? = if (
            packageName == this.packageName && permission == this.permission
        ) {
            StructuredPermissionMetadata(
                packageName = packageName,
                permission = permission,
                declared = true,
                granted = grants.removeFirst(),
                runtime = true,
                shellMayManage = true,
            )
        } else {
            null
        }
    }

    private class ScriptedBridge(
        private val outputs: ArrayDeque<String>,
        private val onStart: (Int) -> Unit = { },
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
            onStart(inputs.size)
            val output = outputs.removeFirst()
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

        override suspend fun cancelAllCommands() =
            PrivilegedCommandResult(true, "OK", "No commands are running.")
        override suspend fun listPackages() = ExternalPrivilegePackageList(emptyList(), false)
        override suspend fun forceStopApp(packageName: String) =
            ExternalPrivilegeActionResult(true, "OK", "Stopped.")
        override suspend fun clearAppCache(packageName: String) =
            ExternalPrivilegeActionResult(true, "OK", "Cleared.")
        override fun requestPermission() = Unit
    }

    private class ResultQueueBridge(
        private val results: ArrayDeque<PrivilegedCommandResult>,
    ) : ExternalPrivilegeBridge {
        val inputs = mutableListOf<PrivilegedCommandInput>()
        override fun status() = readyBridgeStatus()
        override suspend fun startCommand(input: PrivilegedCommandInput): ToolExecutionHandle {
            inputs += input
            return ImmediateHandle(results.removeFirst())
        }
        override suspend fun cancelAllCommands() = PrivilegedCommandResult(true, "OK", "None running.")
        override suspend fun listPackages() = ExternalPrivilegePackageList(emptyList(), false)
        override suspend fun forceStopApp(packageName: String) = ExternalPrivilegeActionResult(true, "OK", "Stopped.")
        override suspend fun clearAppCache(packageName: String) = ExternalPrivilegeActionResult(true, "OK", "Cleared.")
        override fun requestPermission() = Unit
    }

    private class BlockingBridge : ExternalPrivilegeBridge {
        val started = CompletableDeferred<Unit>()
        var cancelRequests: Int = 0
        override fun status() = readyBridgeStatus()
        override suspend fun startCommand(input: PrivilegedCommandInput): ToolExecutionHandle = object : ToolExecutionHandle {
            private val result = CompletableDeferred<ToolResult>()
            override val executionId: String = Uuid.random().toString()
            init { started.complete(Unit) }
            override suspend fun awaitResult(): ToolResult = result.await()
            override fun requestCancel(reason: ToolCancelReason): CancelRequestResult {
                cancelRequests++
                result.complete(
                    listOf(
                        UIMessagePart.Text(
                            PrivilegedCommandJson.encodeResult(
                                PrivilegedCommandResult(false, "COMMAND_CANCELLED", reason.message),
                            ),
                        ),
                    ),
                )
                return CancelRequestResult.Requested
            }
            override suspend fun awaitTermination(gracePeriod: Duration) = ToolTerminationState.StoppedConfirmed
        }
        override suspend fun cancelAllCommands() = PrivilegedCommandResult(true, "OK", "Cancelled.")
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
        override suspend fun awaitTermination(gracePeriod: Duration) =
            ToolTerminationState.StoppedConfirmed
    }

    private companion object {
        fun readyBridgeStatus() = ExternalPrivilegeBridgeStatus(
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
    }
}
