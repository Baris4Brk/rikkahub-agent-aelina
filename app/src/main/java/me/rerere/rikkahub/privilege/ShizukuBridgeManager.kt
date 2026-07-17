package me.rerere.rikkahub.privilege

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeActionResult
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridge
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridgePrivilege
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridgeStatus
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegePackage
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegePackageList
import me.rerere.rikkahub.data.ai.tools.local.ProtectedPackagePolicy
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid
import rikka.shizuku.Shizuku

/** App-process adapter around Shizuku/Sui Binder lifecycle and the typed UserService. */
class ShizukuBridgeManager(context: Context) : ExternalPrivilegeBridge {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val serviceMutex = Mutex()
    private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var userService: IExternalPrivilegeBridgeService? = null

    @Volatile
    private var userServiceDeathRecipient: IBinder.DeathRecipient? = null

    private var pendingConnection: CompletableDeferred<IExternalPrivilegeBridgeService>? = null

    private val _status = MutableStateFlow(computeStatus())
    val statusFlow: StateFlow<ExternalPrivilegeBridgeStatus> = _status.asStateFlow()
    private val activeCommands = AtomicInteger(0)

    /** Process-local count used only for runtime diagnostics; command payloads are never retained. */
    val activeCommandCount: Int get() = activeCommands.get().coerceAtLeast(0)

    /** Whether the lazily-bound UserService Binder is connected right now. */
    val isUserServiceConnected: Boolean
        get() = userService?.asBinder()?.isBinderAlive == true

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshStatus() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        clearUserService()
        pendingConnection?.completeExceptionally(IllegalStateException("Shizuku Binder died."))
        pendingConnection = null
        refreshStatus()
    }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == PERMISSION_REQUEST_CODE) refreshStatus()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val connected = IExternalPrivilegeBridgeService.Stub.asInterface(binder)
            if (connected == null) {
                clearUserService()
                pendingConnection?.completeExceptionally(
                    IllegalStateException("Shizuku UserService returned a null Binder."),
                )
                pendingConnection = null
                refreshStatus()
                return
            }
            clearUserService()
            userService = connected
            val deathRecipient = IBinder.DeathRecipient {
                if (userService?.asBinder() == connected.asBinder()) {
                    clearUserService()
                    refreshStatus()
                }
            }
            userServiceDeathRecipient = deathRecipient
            runCatching { connected.asBinder().linkToDeath(deathRecipient, 0) }
            pendingConnection?.complete(connected)
            pendingConnection = null
            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            clearUserService()
            refreshStatus()
        }
    }

    init {
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    override fun status(): ExternalPrivilegeBridgeStatus = computeStatus()

    override fun requestPermission() {
        val current = computeStatus()
        if (!current.binderAvailable || current.permissionGranted) return
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
        refreshStatus()
    }

    internal fun protectedPackagePolicy(): ProtectedPackagePolicy =
        ProtectedPackagePolicy(protectedPackages())

    override suspend fun listPackages(): ExternalPrivilegePackageList {
        val service = connectService().getOrElse { error ->
            return ExternalPrivilegePackageList(
                packages = emptyList(),
                truncated = false,
                ok = false,
                code = "SHIZUKU_UNAVAILABLE",
                message = error.message ?: "Shizuku UserService is unavailable.",
            )
        }
        val response = parseServiceResponse(
            runCatching { service.listPackages(currentUserId()) }.getOrElse { error ->
                return ExternalPrivilegePackageList(
                    emptyList(),
                    false,
                    false,
                    "BINDER_CALL_FAILED",
                    error.message ?: "Binder call failed.",
                )
            }
        )
        if (!response.ok) {
            return ExternalPrivilegePackageList(emptyList(), false, false, response.code, response.message)
        }
        val allNames = response.output.lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
        val selected = allNames.take(MAX_PACKAGES)
        return ExternalPrivilegePackageList(
            packages = selected.map(::packageRecord),
            truncated = allNames.size > selected.size,
        )
    }

    override suspend fun forceStopApp(packageName: String): ExternalPrivilegeActionResult =
        mutate(packageName) { service, protected ->
            service.forceStopApp(packageName, currentUserId(), protected)
        }

    override suspend fun clearAppCache(packageName: String): ExternalPrivilegeActionResult =
        mutate(packageName) { service, protected ->
            service.clearAppCache(packageName, currentUserId(), protected)
        }

    override suspend fun startCommand(input: PrivilegedCommandInput): ToolExecutionHandle {
        val commandId = Uuid.random().toString()
        val normalized = input.normalized()
        val validation = normalized.validate()
        if (!validation.valid) {
            return completedCommandHandle(
                commandId,
                commandResult(commandId, validation.code, validation.message),
            )
        }
        PrivilegedOperationPolicy(appContext.packageName)
            .check(normalized, computeStatus().privilege)
            ?.let { blocked ->
                return completedCommandHandle(
                    commandId,
                    commandResult(commandId, blocked.code, blocked.message),
                )
            }

        val service = connectService().getOrElse { error ->
            return completedCommandHandle(
                commandId,
                commandResult(
                    commandId,
                    "SHIZUKU_UNAVAILABLE",
                    error.message ?: "Shizuku UserService is unavailable.",
                ),
            )
        }
        activeCommands.incrementAndGet()
        val result = commandScope.async {
            try {
                PrivilegedCommandJson.decodeResult(
                    service.runCommand(
                        PrivilegedCommandJson.encodeRequest(
                            PrivilegedCommandRequest(commandId, normalized),
                        ),
                    ),
                )
            } catch (error: Exception) {
                if (!service.asBinder().isBinderAlive) clearUserService()
                commandResult(
                    commandId,
                    "BINDER_DIED",
                    error.message ?: "The privileged command Binder became unavailable.",
                )
            }
        }
        result.invokeOnCompletion { activeCommands.updateAndGet { count -> (count - 1).coerceAtLeast(0) } }
        return ShizukuCommandExecutionHandle(
            executionId = commandId,
            result = result,
            cancellationScope = commandScope,
            cancelRemote = { id -> cancelCommand(service, id) },
        )
    }

    override suspend fun cancelAllCommands(): PrivilegedCommandResult {
        val service = connectService().getOrElse { error ->
            return PrivilegedCommandResult(
                ok = false,
                code = "SHIZUKU_UNAVAILABLE",
                message = error.message ?: "Shizuku UserService is unavailable.",
            )
        }
        return runCatching {
            PrivilegedCommandJson.decodeResult(service.cancelAllCommands())
        }.getOrElse { error ->
            if (!service.asBinder().isBinderAlive) clearUserService()
            PrivilegedCommandResult(
                ok = false,
                code = "BINDER_DIED",
                message = error.message ?: "The privileged Binder became unavailable.",
            )
        }
    }

    private suspend fun cancelCommand(
        service: IExternalPrivilegeBridgeService,
        commandId: String,
    ): PrivilegedCommandResult {
        if (!service.asBinder().isBinderAlive) {
            clearUserService()
            return commandResult(
                commandId,
                "BINDER_DIED",
                "The command Binder died; termination could not be confirmed.",
            )
        }
        return runCatching {
            PrivilegedCommandJson.decodeResult(service.cancelCommand(commandId))
        }.getOrElse { error ->
            if (!service.asBinder().isBinderAlive) clearUserService()
            commandResult(
                commandId,
                "BINDER_DIED",
                error.message ?: "The command Binder became unavailable.",
            )
        }
    }

    private fun completedCommandHandle(
        commandId: String,
        result: PrivilegedCommandResult,
    ): ToolExecutionHandle = ShizukuCommandExecutionHandle(
        executionId = commandId,
        result = CompletableDeferred(result),
        cancellationScope = commandScope,
        cancelRemote = { result },
    )

    private fun commandResult(
        commandId: String,
        code: String,
        message: String,
    ) = PrivilegedCommandResult(
        ok = false,
        code = code,
        message = message,
        data = PrivilegedCommandResultData(
            commandId = commandId,
            privilege = when (computeStatus().privilege) {
                ExternalPrivilegeBridgePrivilege.None -> "unavailable"
                ExternalPrivilegeBridgePrivilege.Shell -> "shell"
                ExternalPrivilegeBridgePrivilege.Root -> "root"
            },
        ),
    )

    private suspend fun mutate(
        packageName: String,
        call: (IExternalPrivilegeBridgeService, Array<String>) -> String,
    ): ExternalPrivilegeActionResult {
        val protected = protectedPackages()
        ProtectedPackagePolicy(protected).validateMutationTarget(packageName)?.let { return it }
        val service = connectService().getOrElse { error ->
            return ExternalPrivilegeActionResult(
                false,
                "SHIZUKU_UNAVAILABLE",
                error.message ?: "Shizuku UserService is unavailable.",
            )
        }
        return runCatching {
            parseServiceResponse(call(service, protected.toTypedArray())).toMutationActionResult()
        }
            .getOrElse {
                error -> ExternalPrivilegeActionResult(
                    false,
                    "BINDER_CALL_FAILED",
                    error.message ?: "Binder call failed.",
                )
            }
    }

    private suspend fun connectService(): Result<IExternalPrivilegeBridgeService> = runCatching {
        val state = computeStatus()
        check(state.binderAvailable) { "Shizuku is not running." }
        check(state.permissionGranted) { "Shizuku permission is required." }
        check(state.userServiceAvailable) { "This Shizuku version does not support UserService." }

        userService?.takeIf { it.asBinder().isBinderAlive }?.let { return@runCatching it }
        serviceMutex.withLock {
            userService?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
            val deferred = CompletableDeferred<IExternalPrivilegeBridgeService>()
            pendingConnection = deferred
            val args = Shizuku.UserServiceArgs(
                ComponentName(appContext, ExternalPrivilegeUserService::class.java)
            )
                .processNameSuffix("external_privilege")
                .daemon(false)
                .debuggable(BuildConfig.DEBUG)
                .version(USER_SERVICE_VERSION)
                .tag(USER_SERVICE_TAG)
            Shizuku.bindUserService(args, connection)
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        }
    }

    private fun computeStatus(): ExternalPrivilegeBridgeStatus {
        val binderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val installed = binderAvailable || isPackageInstalled(SHIZUKU_PACKAGE) || isPackageInstalled(SUI_PACKAGE)
        val apiVersion = if (binderAvailable) runCatching { Shizuku.getVersion() }.getOrNull() else null
        val serverUid = if (binderAvailable) runCatching { Shizuku.getUid() }.getOrNull() else null
        val permissionGranted = binderAvailable && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val permanentlyDenied = binderAvailable && !permissionGranted && runCatching {
            !Shizuku.shouldShowRequestPermissionRationale()
        }.getOrDefault(false)
        return ExternalPrivilegeBridgeStatus(
            installed = installed,
            binderAvailable = binderAvailable,
            permissionGranted = permissionGranted,
            permissionPermanentlyDenied = permanentlyDenied,
            apiVersion = apiVersion,
            serverVersion = packageVersionName(SHIZUKU_PACKAGE) ?: packageVersionName(SUI_PACKAGE),
            serverUid = serverUid,
            privilege = when (serverUid) {
                0 -> ExternalPrivilegeBridgePrivilege.Root
                2000 -> ExternalPrivilegeBridgePrivilege.Shell
                else -> ExternalPrivilegeBridgePrivilege.None
            },
            userServiceAvailable = binderAvailable && permissionGranted &&
                runCatching { !Shizuku.isPreV11() }.getOrDefault(false),
        )
    }

    private fun refreshStatus() {
        _status.value = computeStatus()
    }

    private fun clearUserService() {
        val current = userService
        val deathRecipient = userServiceDeathRecipient
        if (current != null && deathRecipient != null) {
            runCatching { current.asBinder().unlinkToDeath(deathRecipient, 0) }
        }
        userServiceDeathRecipient = null
        userService = null
    }

    private fun protectedPackages(): Set<String> = buildSet {
        addAll(STATIC_PROTECTED_PACKAGES)
        add(appContext.packageName)
        add(SHIZUKU_PACKAGE)

        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching {
            packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .mapTo(this) { it.activityInfo.packageName }
        }
        runCatching {
            appContext.getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.mapTo(this) { it.packageName }
        }
        runCatching {
            appContext.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
                ?.let(::add)
        }
        runCatching {
            appContext.getSystemService(DevicePolicyManager::class.java)?.getActiveAdmins()
                ?.forEach { add(it.packageName) }
        }
        runCatching {
            packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let(::add)
        }
    }

    /** Android's public SDK does not expose UserHandle#getIdentifier; derive the user id from uid. */
    private fun currentUserId(): Int = Process.myUid() / PER_USER_RANGE

    private fun packageRecord(packageName: String): ExternalPrivilegePackage {
        val appInfo = runCatching {
            packageManager.getApplicationInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrNull()
        val packageInfo = runCatching {
            packageManager.getPackageInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS)
        }.getOrNull()
        return ExternalPrivilegePackage(
            packageName = packageName,
            label = appInfo?.let { runCatching { packageManager.getApplicationLabel(it).toString() }.getOrNull() },
            systemApp = appInfo?.flags?.and(ApplicationInfo.FLAG_SYSTEM) != 0,
            enabled = appInfo?.enabled ?: false,
            versionName = packageInfo?.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo?.longVersionCode ?: 0L
            } else {
                @Suppress("DEPRECATION")
                packageInfo?.versionCode?.toLong() ?: 0L
            },
        )
    }

    private fun isPackageInstalled(packageName: String): Boolean =
        runCatching { packageManager.getApplicationInfo(packageName, 0) }.isSuccess

    private fun packageVersionName(packageName: String): String? =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()

    private fun parseServiceResponse(raw: String): ServiceResponse {
        val obj = Json.parseToJsonElement(raw).jsonObject
        return ServiceResponse(
            ok = obj["ok"]?.jsonPrimitive?.booleanOrNull == true,
            code = obj["code"]?.jsonPrimitive?.contentOrNull ?: "INVALID_RESPONSE",
            message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "Invalid UserService response.",
            output = obj["data"]?.jsonObject?.get("output")?.jsonPrimitive?.contentOrNull
                ?: obj["output"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    private data class ServiceResponse(
        val ok: Boolean,
        val code: String,
        val message: String,
        val output: String,
    ) {
        fun toActionResult() = ExternalPrivilegeActionResult(ok, code, message)

        fun toMutationActionResult(): ExternalPrivilegeActionResult =
            normalizeExternalMutationFailure(ok, code, message)
    }

    private companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SUI_PACKAGE = "rikka.sui"
        private const val PERMISSION_REQUEST_CODE = 74_201
        private const val USER_SERVICE_VERSION = 2
        private const val USER_SERVICE_TAG = "rikkahub.external_privilege.v2"
        private const val BIND_TIMEOUT_MS = 10_000L
        private const val MAX_PACKAGES = 2_000
        private const val PER_USER_RANGE = 100_000

        private val STATIC_PROTECTED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "moe.shizuku.privileged.api",
            "rikka.sui",
        )
    }
}

internal fun normalizeExternalMutationFailure(
    ok: Boolean,
    code: String,
    message: String,
): ExternalPrivilegeActionResult {
    if (ok) return ExternalPrivilegeActionResult(true, code, message)
    if (code != "COMMAND_FAILED") return ExternalPrivilegeActionResult(false, code, message)
    val normalized = message.lowercase()
    val mapped = when {
        listOf("unknown package", "package not found", "not installed").any(normalized::contains) ->
            "PACKAGE_NOT_FOUND"
        listOf("unknown option", "not supported", "unsupported").any(normalized::contains) ->
            "NOT_SUPPORTED"
        listOf("securityexception", "permission denied", "not allowed", "operation not permitted")
            .any(normalized::contains) -> "OEM_REJECTED"
        else -> code
    }
    return ExternalPrivilegeActionResult(false, mapped, message)
}
