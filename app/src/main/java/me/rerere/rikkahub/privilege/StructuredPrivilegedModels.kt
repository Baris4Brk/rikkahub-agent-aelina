package me.rerere.rikkahub.privilege

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
enum class StructuredSettingNamespace(val wire: String) {
    SYSTEM("system"),
    SECURE("secure"),
    GLOBAL("global"),
}

@Serializable
enum class StructuredAppOpMode(val wire: String) {
    ALLOW("allow"),
    IGNORE("ignore"),
    DENY("deny"),
    DEFAULT("default"),
    FOREGROUND("foreground"),
}

sealed interface StructuredIntentExtraValue {
    data class Text(val value: String) : StructuredIntentExtraValue
    data class BooleanValue(val value: Boolean) : StructuredIntentExtraValue
    data class LongValue(val value: Long) : StructuredIntentExtraValue
    data class DoubleValue(val value: Double) : StructuredIntentExtraValue
}

data class StructuredIntentSpec(
    val action: String? = null,
    val component: String? = null,
    val packageName: String? = null,
    val dataUri: String? = null,
    val mimeType: String? = null,
    val categories: List<String> = emptyList(),
    val extras: Map<String, StructuredIntentExtraValue> = emptyMap(),
)

sealed interface StructuredPrivilegedOperation {
    data class SettingGet(
        val namespace: StructuredSettingNamespace,
        val key: String,
    ) : StructuredPrivilegedOperation

    data class SettingPut(
        val namespace: StructuredSettingNamespace,
        val key: String,
        val value: String,
        val verify: Boolean = true,
    ) : StructuredPrivilegedOperation

    data class SettingDelete(
        val namespace: StructuredSettingNamespace,
        val key: String,
        val verify: Boolean = true,
    ) : StructuredPrivilegedOperation

    data class AppOpGet(
        val packageName: String,
        val op: String,
    ) : StructuredPrivilegedOperation

    data class AppOpSet(
        val packageName: String,
        val op: String,
        val mode: StructuredAppOpMode,
        val verify: Boolean = true,
    ) : StructuredPrivilegedOperation

    data class AppOpReset(
        val packageName: String,
        val op: String,
        val verify: Boolean = true,
    ) : StructuredPrivilegedOperation

    data class PermissionStatus(
        val packageName: String,
        val permission: String,
    ) : StructuredPrivilegedOperation

    data class PermissionGrant(
        val packageName: String,
        val permission: String,
        val verify: Boolean = true,
    ) : StructuredPrivilegedOperation

    data class PermissionRevoke(
        val packageName: String,
        val permission: String,
        val verify: Boolean = true,
    ) : StructuredPrivilegedOperation

    data class PackageInspect(
        val packageName: String,
    ) : StructuredPrivilegedOperation

    data class Dumpsys(
        val service: String,
        val filter: String = "",
        val maxOutputBytes: Int = PrivilegedCommandLimits.DEFAULT_OUTPUT_BYTES,
    ) : StructuredPrivilegedOperation

    data class ProcessList(
        val maxProcesses: Int = 500,
    ) : StructuredPrivilegedOperation

    data class ServiceStatus(
        val target: String,
        val serviceName: String? = null,
    ) : StructuredPrivilegedOperation

    data class PackageEnable(
        val packageName: String,
    ) : StructuredPrivilegedOperation

    data class PackageDisable(
        val packageName: String,
    ) : StructuredPrivilegedOperation

    data class PackageSuspend(
        val packageName: String,
    ) : StructuredPrivilegedOperation

    data class PackageUnsuspend(
        val packageName: String,
    ) : StructuredPrivilegedOperation

    data class PackageUninstall(
        val packageName: String,
    ) : StructuredPrivilegedOperation

    data class ResolveIntent(
        val intent: StructuredIntentSpec,
    ) : StructuredPrivilegedOperation

    data class QueryActivities(
        val intent: StructuredIntentSpec,
        val maxResults: Int = 100,
    ) : StructuredPrivilegedOperation

    data class StartActivity(
        val intent: StructuredIntentSpec,
    ) : StructuredPrivilegedOperation

    data class SendBroadcast(
        val intent: StructuredIntentSpec,
    ) : StructuredPrivilegedOperation

    data class LogcatRead(
        val filter: String = "",
        val maxLines: Int = 200,
        val maxOutputBytes: Int = PrivilegedCommandLimits.DEFAULT_OUTPUT_BYTES,
    ) : StructuredPrivilegedOperation

    data class WindowState(
        val maxOutputBytes: Int = PrivilegedCommandLimits.DEFAULT_OUTPUT_BYTES,
    ) : StructuredPrivilegedOperation

    data class JobStatus(
        val packageName: String? = null,
        val filter: String = "",
        val maxOutputBytes: Int = PrivilegedCommandLimits.DEFAULT_OUTPUT_BYTES,
    ) : StructuredPrivilegedOperation

    data class AlarmStatus(
        val packageName: String? = null,
        val filter: String = "",
        val maxOutputBytes: Int = PrivilegedCommandLimits.DEFAULT_OUTPUT_BYTES,
    ) : StructuredPrivilegedOperation
}

@Serializable
data class StructuredPrivilegedResult(
    val ok: Boolean,
    val code: String,
    val message: String,
    val data: JsonObject = buildJsonObject { },
    val verified: Boolean? = null,
)

data class StructuredPackageMetadata(
    val packageName: String,
    val label: String? = null,
    val versionName: String? = null,
    val versionCode: Long = 0,
    val uid: Int = -1,
    val enabled: Boolean = false,
    val suspended: Boolean = false,
    val stopped: Boolean = false,
    val installSource: String? = null,
    val runtimePermissions: List<StructuredRuntimePermissionSummary> = emptyList(),
)

data class StructuredRuntimePermissionSummary(
    val permission: String,
    val granted: Boolean,
    val shellMayManage: Boolean,
)

data class StructuredPermissionMetadata(
    val packageName: String,
    val permission: String,
    val declared: Boolean,
    val granted: Boolean,
    val runtime: Boolean,
    val shellMayManage: Boolean,
)

interface PrivilegedPackageMetadataReader {
    val currentUserId: Int

    fun packageMetadata(packageName: String): StructuredPackageMetadata?

    fun permissionMetadata(
        packageName: String,
        permission: String,
    ): StructuredPermissionMetadata?
}

object EmptyPackageMetadataReader : PrivilegedPackageMetadataReader {
    override val currentUserId: Int = 0
    override fun packageMetadata(packageName: String): StructuredPackageMetadata? = null
    override fun permissionMetadata(
        packageName: String,
        permission: String,
    ): StructuredPermissionMetadata? = null
}

interface PrivilegedRuntimeStatusProvider {
    suspend fun status(target: String, serviceName: String?): StructuredPrivilegedResult
}

object EmptyRuntimeStatusProvider : PrivilegedRuntimeStatusProvider {
    override suspend fun status(
        target: String,
        serviceName: String?,
    ) = StructuredPrivilegedResult(
        ok = false,
        code = "NOT_SUPPORTED",
        message = "Runtime status is unavailable.",
    )
}
