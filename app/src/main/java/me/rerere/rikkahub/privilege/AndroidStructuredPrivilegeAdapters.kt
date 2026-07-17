package me.rerere.rikkahub.privilege

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.Process
import android.provider.Settings
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridge
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridgePrivilege
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.service.RikkaNotificationListenerService
import me.rerere.workspace.WorkspaceProcessManager

class AndroidPrivilegedPackageMetadataReader(
    context: Context,
) : PrivilegedPackageMetadataReader {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    override val currentUserId: Int
        get() = Process.myUid() / PER_USER_RANGE

    override fun packageMetadata(packageName: String): StructuredPackageMetadata? {
        val packageInfo = packageInfo(packageName) ?: return null
        val appInfo = packageInfo.applicationInfo ?: return null
        val permissions = packageInfo.requestedPermissions.orEmpty().mapNotNull { permission ->
            permissionMetadata(packageName, permission, packageInfo)
                ?.takeIf { it.runtime }
                ?.let {
                    StructuredRuntimePermissionSummary(
                        permission = permission,
                        granted = it.granted,
                        shellMayManage = it.shellMayManage,
                    )
                }
        }.sortedBy { it.permission }
        return StructuredPackageMetadata(
            packageName = packageName,
            label = runCatching { packageManager.getApplicationLabel(appInfo).toString() }.getOrNull(),
            versionName = packageInfo.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            },
            uid = appInfo.uid,
            enabled = appInfo.enabled,
            suspended = runCatching { packageManager.isPackageSuspended(packageName) }.getOrDefault(false),
            stopped = appInfo.flags and ApplicationInfo.FLAG_STOPPED != 0,
            installSource = installSource(packageName),
            runtimePermissions = permissions,
        )
    }

    override fun permissionMetadata(
        packageName: String,
        permission: String,
    ): StructuredPermissionMetadata? {
        val packageInfo = packageInfo(packageName) ?: return null
        return permissionMetadata(packageName, permission, packageInfo)
    }

    private fun permissionMetadata(
        packageName: String,
        permission: String,
        packageInfo: android.content.pm.PackageInfo,
    ): StructuredPermissionMetadata {
        val declared = permission in packageInfo.requestedPermissions.orEmpty()
        val permissionInfo = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPermissionInfo(permission, 0)
        }.getOrNull()
        val baseProtection = permissionInfo?.protectionLevel
            ?.and(PermissionInfo.PROTECTION_MASK_BASE)
        val runtime = baseProtection == PermissionInfo.PROTECTION_DANGEROUS
        val hardRestricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            permissionInfo?.flags?.and(PermissionInfo.FLAG_HARD_RESTRICTED) != 0
        return StructuredPermissionMetadata(
            packageName = packageName,
            permission = permission,
            declared = declared,
            granted = declared && packageManager.checkPermission(permission, packageName) ==
                PackageManager.PERMISSION_GRANTED,
            runtime = runtime,
            shellMayManage = declared && runtime && !hardRestricted,
        )
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageName: String) = runCatching {
        packageManager.getPackageInfo(
            packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.MATCH_DISABLED_COMPONENTS,
        )
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun installSource(packageName: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
                ?: packageManager.getInstallSourceInfo(packageName).initiatingPackageName
        } else {
            packageManager.getInstallerPackageName(packageName)
        }
    }.getOrNull()

    private companion object {
        private const val PER_USER_RANGE = 100_000
    }
}

class AndroidPrivilegedRuntimeStatusProvider(
    context: Context,
    private val bridge: ExternalPrivilegeBridge,
    private val workspaceProcessManager: WorkspaceProcessManager,
) : PrivilegedRuntimeStatusProvider {
    private val appContext = context.applicationContext

    override suspend fun status(
        target: String,
        serviceName: String?,
    ): StructuredPrivilegedResult = when (target) {
        "accessibility" -> enabledComponentStatus(
            target = target,
            settingKey = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            component = ComponentName(appContext, RikkaAccessibilityService::class.java),
        )
        "notification_listener" -> enabledComponentStatus(
            target = target,
            settingKey = "enabled_notification_listeners",
            component = ComponentName(appContext, RikkaNotificationListenerService::class.java),
        )
        "shizuku" -> shizukuStatus()
        "workspace_process_service" -> workspaceStatus()
        "rikkahub_foreground" -> foregroundStatus()
        else -> StructuredPrivilegedResult(
            ok = false,
            code = "NOT_SUPPORTED",
            message = "Runtime status target is not implemented by this adapter.",
        )
    }

    private fun enabledComponentStatus(
        target: String,
        settingKey: String,
        component: ComponentName,
    ): StructuredPrivilegedResult {
        val enabled = Settings.Secure.getString(appContext.contentResolver, settingKey)
            .orEmpty()
            .split(':')
            .any { it.equals(component.flattenToString(), ignoreCase = true) }
        return StructuredPrivilegedResult(
            ok = true,
            code = "SERVICE_STATUS",
            message = "Service authorization observed.",
            data = buildJsonObject {
                put("target", target)
                put("state", if (enabled) "READY" else "SERVICE_OFFLINE")
                put("enabled", enabled)
                put("component", component.flattenToString())
            },
        )
    }

    private fun shizukuStatus(): StructuredPrivilegedResult {
        val status = bridge.status()
        val ready = status.binderAvailable && status.permissionGranted && status.userServiceAvailable
        return StructuredPrivilegedResult(
            ok = true,
            code = "SERVICE_STATUS",
            message = "Shizuku status observed.",
            data = buildJsonObject {
                put("target", "shizuku")
                put("state", if (ready) "READY" else "SERVICE_OFFLINE")
                put("installed", status.installed)
                put("binder_available", status.binderAvailable)
                put("permission_granted", status.permissionGranted)
                put("user_service_available", status.userServiceAvailable)
                put("privilege", when (status.privilege) {
                    ExternalPrivilegeBridgePrivilege.Root -> "root"
                    ExternalPrivilegeBridgePrivilege.Shell -> "shell"
                    ExternalPrivilegeBridgePrivilege.None -> "unavailable"
                })
            },
        )
    }

    private suspend fun workspaceStatus(): StructuredPrivilegedResult {
        val processes = workspaceProcessManager.list(includeStopped = false)
        val alive = processes.count { it.alive }
        return StructuredPrivilegedResult(
            ok = true,
            code = "SERVICE_STATUS",
            message = "Workspace process service status observed.",
            data = buildJsonObject {
                put("target", "workspace_process_service")
                put("state", if (alive > 0) "READY" else "SERVICE_OFFLINE")
                put("managed_count", processes.size)
                put("alive_count", alive)
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun foregroundStatus(): StructuredPrivilegedResult {
        val activityManager = appContext.getSystemService(ActivityManager::class.java)
        val services = runCatching { activityManager?.getRunningServices(100).orEmpty() }
            .getOrDefault(emptyList())
            .filter { it.service.packageName == appContext.packageName }
        return StructuredPrivilegedResult(
            ok = true,
            code = "SERVICE_STATUS",
            message = "RikkaHub foreground services observed.",
            data = buildJsonObject {
                put("target", "rikkahub_foreground")
                put("state", if (services.any { it.foreground }) "READY" else "SERVICE_OFFLINE")
                put("running_count", services.size)
                put("foreground_count", services.count { it.foreground })
                put("services", buildJsonArray {
                    services.forEach { service ->
                        addJsonObject {
                            put("component", service.service.flattenToString())
                            put("foreground", service.foreground)
                            put("pid", service.pid)
                        }
                    }
                })
            },
        )
    }
}

fun defaultStructuredProtectedPackages(context: Context): Set<String> = setOf(
    context.packageName,
    "moe.shizuku.privileged.api",
    "rikka.sui",
    "dev.patrickgold.florisboard",
)

fun defaultStructuredCriticalSystemPackages(context: Context): Set<String> = buildSet {
    add("android")
    add("com.android.systemui")
    add("com.android.settings")
    add("com.android.packageinstaller")
    add("com.google.android.packageinstaller")
    add("com.android.permissioncontroller")
    add("com.google.android.permissioncontroller")
    addAll(
        activeStructuredVoiceServicePackages(
            voiceInteractionService = secureSetting(context, "voice_interaction_service"),
            voiceRecognitionService = secureSetting(context, "voice_recognition_service"),
        ),
    )

    val packageManager = context.packageManager
    runCatching {
        packageManager.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        ).mapTo(this) { it.activityInfo.packageName }
    }
    runCatching {
        packageManager.resolveActivity(
            android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName?.let(::add)
    }
    runCatching {
        packageManager.resolveActivity(
            android.content.Intent(
                android.content.Intent.ACTION_DIAL,
                android.net.Uri.parse("tel:10086"),
            ),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName?.let(::add)
    }
}

internal fun activeStructuredVoiceServicePackages(
    voiceInteractionService: String?,
    voiceRecognitionService: String?,
): Set<String> = sequenceOf(voiceInteractionService, voiceRecognitionService)
    .mapNotNull(::flattenedComponentPackage)
    .toSet()

private fun secureSetting(context: Context, key: String): String? = runCatching {
    Settings.Secure.getString(context.contentResolver, key)
}.getOrNull()

private fun flattenedComponentPackage(raw: String?): String? {
    val component = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val separator = component.indexOf('/')
    if (separator <= 0 || separator != component.lastIndexOf('/')) return null
    val packageName = component.substring(0, separator)
    val className = component.substring(separator + 1)
    if (!ANDROID_COMPONENT_PACKAGE.matches(packageName)) return null
    if (!ANDROID_COMPONENT_CLASS.matches(className)) return null
    return packageName.lowercase()
}

private val ANDROID_COMPONENT_PACKAGE = Regex(
    "^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*$",
)
private val ANDROID_COMPONENT_CLASS = Regex(
    "^(?:\\.[A-Za-z_\$][A-Za-z0-9_.\$]*|[A-Za-z_\$][A-Za-z0-9_.\$]*)$",
)
