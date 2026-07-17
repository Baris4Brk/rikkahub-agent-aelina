package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.privilege.PrivilegedCommandInput
import me.rerere.rikkahub.privilege.PrivilegedCommandResult
import me.rerere.rikkahub.privilege.normalizeExternalMutationFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalPrivilegeBridgeToolTest {
    private fun execute(tool: Tool, args: String = "{}") = runBlocking {
        val part = tool.execute(Json.parseToJsonElement(args)).single() as UIMessagePart.Text
        Json.parseToJsonElement(part.text).jsonObject
    }

    @Test
    fun `status exposes binder authorization and privilege without requesting permission`() {
        val bridge = FakeExternalPrivilegeBridge(
            status = ExternalPrivilegeBridgeStatus(
                installed = true,
                binderAvailable = true,
                permissionGranted = true,
                permissionPermanentlyDenied = false,
                apiVersion = 13,
                serverVersion = "13.6.0",
                serverUid = 2000,
                privilege = ExternalPrivilegeBridgePrivilege.Shell,
                userServiceAvailable = true,
            )
        )

        val result = execute(shizukuStatusTool(bridge))

        assertTrue(result["ok"]?.jsonPrimitive?.content?.toBoolean() == true)
        assertEquals("shell", result["data"]?.jsonObject?.get("privilege")?.jsonPrimitive?.content)
        assertEquals("13", result["data"]?.jsonObject?.get("api_version")?.jsonPrimitive?.content)
        assertEquals(0, bridge.permissionRequestCount)
    }

    @Test
    fun `list packages returns bounded structured package metadata`() {
        val bridge = FakeExternalPrivilegeBridge(
            packages = ExternalPrivilegePackageList(
                packages = listOf(
                    ExternalPrivilegePackage(
                        packageName = "com.example.notes",
                        label = "Notes",
                        systemApp = false,
                        enabled = true,
                        versionName = "2.1",
                        versionCode = 21,
                    )
                ),
                truncated = false,
            )
        )

        val result = execute(listPackagesTool(bridge))

        assertTrue(result["ok"]?.jsonPrimitive?.content?.toBoolean() == true)
        assertEquals("1", result["data"]?.jsonObject?.get("count")?.jsonPrimitive?.content)
        assertEquals(
            "com.example.notes",
            result["data"]?.jsonObject?.get("packages")?.jsonArray?.single()
                ?.jsonObject?.get("package_name")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `force stop validates package and forwards an approved ordinary app`() {
        val bridge = FakeExternalPrivilegeBridge()
        val policy = ProtectedPackagePolicy(setOf("android", "com.android.systemui"))

        val result = execute(
            forceStopAppTool(bridge, policy),
            """{"package_name":"com.example.notes"}""",
        )

        assertTrue(result["ok"]?.jsonPrimitive?.content?.toBoolean() == true)
        assertEquals(listOf("com.example.notes"), bridge.forceStoppedPackages)
    }

    @Test
    fun `mutations reject shell injection and protected packages before bridge call`() {
        val bridge = FakeExternalPrivilegeBridge()
        val policy = ProtectedPackagePolicy(setOf("android", "com.android.systemui"))

        val injected = execute(
            clearAppCacheTool(bridge, policy),
            """{"package_name":"com.example.notes; reboot"}""",
        )
        val protected = execute(
            forceStopAppTool(bridge, policy),
            """{"package_name":"com.android.systemui"}""",
        )

        assertEquals("INVALID_PACKAGE_NAME", injected["code"]?.jsonPrimitive?.content)
        assertEquals("PROTECTED_PACKAGE", protected["code"]?.jsonPrimitive?.content)
        assertTrue(bridge.forceStoppedPackages.isEmpty())
        assertTrue(bridge.cacheClearedPackages.isEmpty())
    }

    @Test
    fun `privileged mutations require per-call approval`() {
        assertTrue(ToolApprovalDefaults.requiresApproval("force_stop_app"))
        assertTrue(ToolApprovalDefaults.requiresApproval("clear_app_cache"))
        assertTrue(!ToolApprovalDefaults.allowsAlwaysAllow("force_stop_app"))
        assertTrue(!ToolApprovalDefaults.allowsAlwaysAllow("clear_app_cache"))
    }

    @Test
    fun `fixed mutation failures map package oem and unsupported errors`() {
        assertEquals(
            "PACKAGE_NOT_FOUND",
            normalizeExternalMutationFailure(false, "COMMAND_FAILED", "Unknown package: missing.app").code,
        )
        assertEquals(
            "OEM_REJECTED",
            normalizeExternalMutationFailure(false, "COMMAND_FAILED", "java.lang.SecurityException").code,
        )
        assertEquals(
            "NOT_SUPPORTED",
            normalizeExternalMutationFailure(false, "COMMAND_FAILED", "Unknown option --cache-only").code,
        )
    }
}

private class FakeExternalPrivilegeBridge(
    private val status: ExternalPrivilegeBridgeStatus = ExternalPrivilegeBridgeStatus(
        installed = true,
        binderAvailable = true,
        permissionGranted = true,
        permissionPermanentlyDenied = false,
        apiVersion = 13,
        serverVersion = "13.6.0",
        serverUid = 2000,
        privilege = ExternalPrivilegeBridgePrivilege.Shell,
        userServiceAvailable = true,
    ),
    private val packages: ExternalPrivilegePackageList = ExternalPrivilegePackageList(emptyList(), false),
) : ExternalPrivilegeBridge {
    var permissionRequestCount = 0
    val forceStoppedPackages = mutableListOf<String>()
    val cacheClearedPackages = mutableListOf<String>()

    override fun status(): ExternalPrivilegeBridgeStatus = status

    override fun requestPermission() {
        permissionRequestCount++
    }

    override suspend fun listPackages(): ExternalPrivilegePackageList = packages

    override suspend fun forceStopApp(packageName: String): ExternalPrivilegeActionResult {
        forceStoppedPackages += packageName
        return ExternalPrivilegeActionResult(true, "OK", "App force-stopped.")
    }

    override suspend fun clearAppCache(packageName: String): ExternalPrivilegeActionResult {
        cacheClearedPackages += packageName
        return ExternalPrivilegeActionResult(true, "OK", "App cache cleared.")
    }

    override suspend fun startCommand(input: PrivilegedCommandInput): ToolExecutionHandle =
        error("Generic command execution is not used by fixed-operation tests.")

    override suspend fun cancelAllCommands(): PrivilegedCommandResult =
        PrivilegedCommandResult(true, "OK", "No commands are running.")
}
