package me.rerere.rikkahub.privilege

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolResult
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import me.rerere.rikkahub.data.ai.tools.local.ExternalPrivilegeBridge
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.uuid.Uuid

class StructuredPrivilegedCommandExecutor(
    private val bridge: ExternalPrivilegeBridge,
    private val scope: CoroutineScope,
    private val packageMetadataReader: PrivilegedPackageMetadataReader,
    private val runtimeStatusProvider: PrivilegedRuntimeStatusProvider,
    protectedPackages: Set<String>,
    criticalSystemPackages: Set<String> = emptySet(),
    private val isEmergencyStopActive: () -> Boolean,
) {
    private val protectedPackages = protectedPackages.mapTo(linkedSetOf()) { it.lowercase() }
    private val criticalSystemPackages = criticalSystemPackages.mapTo(linkedSetOf()) { it.lowercase() }

    suspend fun start(operation: StructuredPrivilegedOperation): ToolExecutionHandle {
        val executionId = "structured_${Uuid.random()}"
        val cancelled = AtomicBoolean(false)
        val current = AtomicReference<ToolExecutionHandle?>()
        lateinit var result: Deferred<StructuredPrivilegedResult>
        result = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            execute(operation, cancelled, current)
        }
        return StructuredPrivilegedExecutionHandle(
            executionId = executionId,
            result = result,
            cancelled = cancelled,
            current = current,
        )
    }

    private suspend fun execute(
        operation: StructuredPrivilegedOperation,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult = when (operation) {
        is StructuredPrivilegedOperation.SettingGet -> settingGet(operation, cancelled, current)
        is StructuredPrivilegedOperation.SettingPut -> settingPut(operation, cancelled, current)
        is StructuredPrivilegedOperation.SettingDelete -> settingDelete(operation, cancelled, current)
        is StructuredPrivilegedOperation.AppOpGet -> appOpGet(operation, cancelled, current)
        is StructuredPrivilegedOperation.AppOpSet -> appOpSet(operation, cancelled, current)
        is StructuredPrivilegedOperation.AppOpReset -> appOpReset(operation, cancelled, current)
        is StructuredPrivilegedOperation.PermissionStatus -> permissionStatus(operation)
        is StructuredPrivilegedOperation.PermissionGrant -> permissionWrite(
            packageName = operation.packageName,
            permission = operation.permission,
            grant = true,
            verify = operation.verify,
            cancelled = cancelled,
            current = current,
        )
        is StructuredPrivilegedOperation.PermissionRevoke -> permissionWrite(
            packageName = operation.packageName,
            permission = operation.permission,
            grant = false,
            verify = operation.verify,
            cancelled = cancelled,
            current = current,
        )
        is StructuredPrivilegedOperation.PackageInspect -> packageInspect(operation, cancelled, current)
        is StructuredPrivilegedOperation.Dumpsys -> dumpsys(operation, cancelled, current)
        is StructuredPrivilegedOperation.ProcessList -> processList(operation, cancelled, current)
        is StructuredPrivilegedOperation.ServiceStatus -> serviceStatus(operation, cancelled, current)
        is StructuredPrivilegedOperation.PackageEnable -> packageMutation(
            packageName = operation.packageName,
            mutation = PackageMutation.ENABLE,
            cancelled = cancelled,
            current = current,
        )
        is StructuredPrivilegedOperation.PackageDisable -> packageMutation(
            packageName = operation.packageName,
            mutation = PackageMutation.DISABLE,
            cancelled = cancelled,
            current = current,
        )
        is StructuredPrivilegedOperation.PackageSuspend -> packageMutation(
            packageName = operation.packageName,
            mutation = PackageMutation.SUSPEND,
            cancelled = cancelled,
            current = current,
        )
        is StructuredPrivilegedOperation.PackageUnsuspend -> packageMutation(
            packageName = operation.packageName,
            mutation = PackageMutation.UNSUSPEND,
            cancelled = cancelled,
            current = current,
        )
        is StructuredPrivilegedOperation.PackageUninstall -> packageMutation(
            packageName = operation.packageName,
            mutation = PackageMutation.UNINSTALL,
            cancelled = cancelled,
            current = current,
        )
        is StructuredPrivilegedOperation.ResolveIntent -> resolveIntent(operation, cancelled, current)
        is StructuredPrivilegedOperation.QueryActivities -> queryActivities(operation, cancelled, current)
        is StructuredPrivilegedOperation.StartActivity -> startActivity(operation, cancelled, current)
        is StructuredPrivilegedOperation.SendBroadcast -> sendBroadcast(operation, cancelled, current)
        is StructuredPrivilegedOperation.LogcatRead -> logcatRead(operation, cancelled, current)
        is StructuredPrivilegedOperation.WindowState -> windowState(operation, cancelled, current)
        is StructuredPrivilegedOperation.JobStatus -> diagnosticStatus(
            service = "jobscheduler",
            packageName = operation.packageName,
            filter = operation.filter,
            maxOutputBytes = operation.maxOutputBytes,
            successCode = "JOB_STATUS_READ",
            cancelled = cancelled,
            current = current,
        )
        is StructuredPrivilegedOperation.AlarmStatus -> diagnosticStatus(
            service = "alarm",
            packageName = operation.packageName,
            filter = operation.filter,
            maxOutputBytes = operation.maxOutputBytes,
            successCode = "ALARM_STATUS_READ",
            cancelled = cancelled,
            current = current,
        )
    }

    private suspend fun settingGet(
        operation: StructuredPrivilegedOperation.SettingGet,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        validateSettingKey(operation.key)?.let { return it }
        val command = readSetting(operation.namespace, operation.key, cancelled, current)
        command.failure?.let { return it }
        val value = command.stdout.settingValueOrNull()
            ?: return failure("SETTING_NOT_FOUND", "The requested setting does not exist.")
        return success(
            code = "SETTING_READ",
            message = "Setting read.",
            data = buildJsonObject {
                put("namespace", operation.namespace.wire)
                put("key", operation.key)
                put("value", value)
            },
        )
    }

    private suspend fun settingPut(
        operation: StructuredPrivilegedOperation.SettingPut,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        validateSettingKey(operation.key)?.let { return it }
        validateSettingWrite(operation.namespace, operation.key, operation.verify)?.let { return it }
        validateSettingValue(operation.value)?.let { return it }

        val old = readSetting(operation.namespace, operation.key, cancelled, current)
        old.failure?.let { return it }
        val write = runArgv(
            executable = SETTINGS_EXECUTABLE,
            arguments = settingArguments("put", operation.namespace, operation.key) + operation.value,
            cancelled = cancelled,
            current = current,
        )
        write.failure?.let { return it.remapCommandFailure("OEM_REJECTED") }
        val actual = readSetting(operation.namespace, operation.key, cancelled, current)
        actual.failure?.let { return it }
        val actualValue = actual.stdout.settingValueOrNull()
        val verified = actualValue == operation.value
        return StructuredPrivilegedResult(
            ok = verified,
            code = if (verified) "SETTING_UPDATED" else "SETTING_VERIFY_FAILED",
            message = if (verified) "Setting updated and verified." else "Setting did not match the requested value.",
            data = buildJsonObject {
                put("namespace", operation.namespace.wire)
                put("key", operation.key)
                put("old_value", old.stdout.settingValueOrNull())
                put("requested_value", operation.value)
                put("actual_value", actualValue)
                put("command_code", write.commandCode)
            },
            verified = verified,
        )
    }

    private suspend fun settingDelete(
        operation: StructuredPrivilegedOperation.SettingDelete,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        validateSettingKey(operation.key)?.let { return it }
        validateSettingWrite(operation.namespace, operation.key, operation.verify)?.let { return it }
        val old = readSetting(operation.namespace, operation.key, cancelled, current)
        old.failure?.let { return it }
        val write = runArgv(
            executable = SETTINGS_EXECUTABLE,
            arguments = settingArguments("delete", operation.namespace, operation.key),
            cancelled = cancelled,
            current = current,
        )
        write.failure?.let { return it.remapCommandFailure("OEM_REJECTED") }
        val actual = readSetting(operation.namespace, operation.key, cancelled, current)
        actual.failure?.let { return it }
        val actualValue = actual.stdout.settingValueOrNull()
        val verified = actualValue == null
        return StructuredPrivilegedResult(
            ok = verified,
            code = if (verified) "SETTING_DELETED" else "SETTING_VERIFY_FAILED",
            message = if (verified) "Setting deleted and verified." else "Setting still exists after deletion.",
            data = buildJsonObject {
                put("namespace", operation.namespace.wire)
                put("key", operation.key)
                put("old_value", old.stdout.settingValueOrNull())
                put("actual_value", actualValue)
                put("command_code", write.commandCode)
            },
            verified = verified,
        )
    }

    private suspend fun readSetting(
        namespace: StructuredSettingNamespace,
        key: String,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ) = runArgv(
        executable = SETTINGS_EXECUTABLE,
        arguments = settingArguments("get", namespace, key),
        cancelled = cancelled,
        current = current,
    )

    private suspend fun appOpGet(
        operation: StructuredPrivilegedOperation.AppOpGet,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        val op = normalizeAppOp(operation.op) ?: return failure("INVALID_ARGUMENT", "AppOp name is invalid.")
        validatePackage(operation.packageName)?.let { return it }
        val read = readAppOp(operation.packageName, op, cancelled, current)
        read.failure?.let { return it }
        val mode = parseAppOpMode(read.stdout, op) ?: StructuredAppOpMode.DEFAULT.wire
        return success(
            code = "APPOP_READ",
            message = "AppOp read.",
            data = buildJsonObject {
                put("package_name", operation.packageName)
                put("op", op)
                put("mode", mode)
            },
        )
    }

    private suspend fun appOpSet(
        operation: StructuredPrivilegedOperation.AppOpSet,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        val op = normalizeAppOp(operation.op) ?: return failure("INVALID_ARGUMENT", "AppOp name is invalid.")
        validatePackage(operation.packageName)?.let { return it }
        if (!operation.verify) return failure("INVALID_ARGUMENT", "verify must be true for privileged writes.")
        if (operation.packageName.lowercase() in protectedPackages && operation.mode != StructuredAppOpMode.ALLOW) {
            return failure("PROTECTED_RESOURCE", "Protected packages can only receive an allow AppOp mode.")
        }
        return writeAppOp(
            packageName = operation.packageName,
            op = op,
            requestedMode = operation.mode.wire,
            successCode = "APPOP_UPDATED",
            cancelled = cancelled,
            current = current,
        )
    }

    private suspend fun appOpReset(
        operation: StructuredPrivilegedOperation.AppOpReset,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        val op = normalizeAppOp(operation.op) ?: return failure("INVALID_ARGUMENT", "AppOp name is invalid.")
        validatePackage(operation.packageName)?.let { return it }
        if (!operation.verify) return failure("INVALID_ARGUMENT", "verify must be true for privileged writes.")
        if (operation.packageName.lowercase() in protectedPackages) {
            return failure("PROTECTED_RESOURCE", "AppOps cannot be reset for a protected package.")
        }
        return writeAppOp(
            packageName = operation.packageName,
            op = op,
            requestedMode = StructuredAppOpMode.DEFAULT.wire,
            successCode = "APPOP_RESET",
            unsupportedCode = "APPOP_MODE_UNSUPPORTED",
            cancelled = cancelled,
            current = current,
        )
    }

    private suspend fun writeAppOp(
        packageName: String,
        op: String,
        requestedMode: String,
        successCode: String,
        unsupportedCode: String? = null,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        val old = readAppOp(packageName, op, cancelled, current)
        old.failure?.let { return it }
        val oldMode = parseAppOpMode(old.stdout, op) ?: StructuredAppOpMode.DEFAULT.wire
        val write = runArgv(
            executable = CMD_EXECUTABLE,
            arguments = appOpArguments("set", packageName, op) + requestedMode,
            cancelled = cancelled,
            current = current,
        )
        write.failure?.let { failed ->
            return failed.remapCommandFailure(unsupportedCode ?: "OEM_REJECTED")
        }
        val actual = readAppOp(packageName, op, cancelled, current)
        actual.failure?.let { return it }
        val actualMode = parseAppOpMode(actual.stdout, op) ?: StructuredAppOpMode.DEFAULT.wire
        val verified = actualMode == requestedMode
        return StructuredPrivilegedResult(
            ok = verified,
            code = if (verified) successCode else "VERIFY_FAILED",
            message = if (verified) "AppOp updated and verified." else "AppOp did not match the requested mode.",
            data = buildJsonObject {
                put("package_name", packageName)
                put("op", op)
                put("old_mode", oldMode)
                put("requested_mode", requestedMode)
                put("actual_mode", actualMode)
                put("command_code", write.commandCode)
            },
            verified = verified,
        )
    }

    private suspend fun readAppOp(
        packageName: String,
        op: String,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ) = runArgv(
        executable = CMD_EXECUTABLE,
        arguments = appOpArguments("get", packageName, op),
        cancelled = cancelled,
        current = current,
    )

    private fun appOpArguments(
        action: String,
        packageName: String,
        op: String,
    ) = listOf(
        "appops",
        action,
        "--user",
        packageMetadataReader.currentUserId.toString(),
        packageName,
        op,
    )

    private fun validatePackage(packageName: String): StructuredPrivilegedResult? = when {
        packageName.isBlank() || packageName.length > MAX_PACKAGE_NAME_CHARS ||
            !PACKAGE_NAME.matches(packageName) -> failure("INVALID_ARGUMENT", "Package name is invalid.")
        packageMetadataReader.packageMetadata(packageName) == null ->
            failure("PACKAGE_NOT_FOUND", "Package is not installed for the current user.")
        else -> null
    }

    private fun normalizeAppOp(op: String): String? = op.trim().uppercase()
        .takeIf { it.length <= MAX_APP_OP_CHARS && APP_OP.matches(it) }

    private fun parseAppOpMode(output: String, op: String): String? {
        val match = Regex(
            "(?im)^\\s*${Regex.escape(op)}\\s*:\\s*(?:mode=)?" +
                "(allow|ignore|deny|default|foreground|errored)\\b",
        ).find(output)
        return match?.groupValues?.get(1)?.lowercase()
    }

    private fun permissionStatus(
        operation: StructuredPrivilegedOperation.PermissionStatus,
    ): StructuredPrivilegedResult {
        val metadata = validatePermission(operation.packageName, operation.permission)
        metadata.failure?.let { return it }
        return success(
            code = "PERMISSION_STATUS",
            message = "Permission status read.",
            data = metadata.value!!.toJson(),
        )
    }

    private suspend fun permissionWrite(
        packageName: String,
        permission: String,
        grant: Boolean,
        verify: Boolean,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        if (!verify) return failure("INVALID_ARGUMENT", "verify must be true for privileged writes.")
        val before = validatePermission(packageName, permission)
        before.failure?.let { return it }
        val old = before.value!!
        if (!old.declared) return failure("PERMISSION_NOT_DECLARED", "Package does not declare this permission.")
        if (!old.runtime || !old.shellMayManage) {
            return failure("NOT_SUPPORTED", "This permission cannot be managed by Android Shell.")
        }
        if (!grant && packageName.lowercase() in protectedPackages) {
            return failure("PROTECTED_RESOURCE", "Permissions cannot be revoked from a protected package.")
        }
        val desired = grant
        if (old.granted == desired) {
            return StructuredPrivilegedResult(
                ok = true,
                code = if (grant) "PERMISSION_GRANTED" else "PERMISSION_REVOKED",
                message = "Permission already has the requested state.",
                data = buildJsonObject {
                    put("package_name", packageName)
                    put("permission", permission)
                    put("old_granted", old.granted)
                    put("actual_granted", old.granted)
                    put("changed", false)
                },
                verified = true,
            )
        }
        val write = runArgv(
            executable = PM_EXECUTABLE,
            arguments = listOf(
                if (grant) "grant" else "revoke",
                "--user",
                packageMetadataReader.currentUserId.toString(),
                packageName,
                permission,
            ),
            cancelled = cancelled,
            current = current,
        )
        write.failure?.let { failed -> return failed.remapCommandFailure("ANDROID_REJECTED") }
        val actual = validatePermission(packageName, permission)
        actual.failure?.let { return it }
        val actualGranted = actual.value!!.granted
        val verified = actualGranted == desired
        return StructuredPrivilegedResult(
            ok = verified,
            code = when {
                !verified -> "VERIFY_FAILED"
                grant -> "PERMISSION_GRANTED"
                else -> "PERMISSION_REVOKED"
            },
            message = if (verified) "Permission updated and verified." else "Permission state did not change as requested.",
            data = buildJsonObject {
                put("package_name", packageName)
                put("permission", permission)
                put("old_granted", old.granted)
                put("requested_granted", desired)
                put("actual_granted", actualGranted)
                put("changed", old.granted != actualGranted)
                put("command_code", write.commandCode)
            },
            verified = verified,
        )
    }

    private fun validatePermission(
        packageName: String,
        permission: String,
    ): PermissionLookup {
        validatePackage(packageName)?.let { return PermissionLookup(failure = it) }
        if (permission.isBlank() || permission.length > MAX_PERMISSION_CHARS ||
            !PERMISSION_NAME.matches(permission)) {
            return PermissionLookup(failure = failure("INVALID_ARGUMENT", "Permission name is invalid."))
        }
        val metadata = packageMetadataReader.permissionMetadata(packageName, permission)
            ?: return PermissionLookup(
                failure = failure("PERMISSION_NOT_DECLARED", "Package does not declare this permission."),
            )
        return PermissionLookup(value = metadata)
    }

    private fun StructuredPermissionMetadata.toJson() = buildJsonObject {
        put("package_name", packageName)
        put("permission", permission)
        put("declared", declared)
        put("granted", granted)
        put("runtime", runtime)
        put("shell_may_manage", shellMayManage)
        put("user_id", packageMetadataReader.currentUserId)
    }

    private data class PermissionLookup(
        val value: StructuredPermissionMetadata? = null,
        val failure: StructuredPrivilegedResult? = null,
    )

    private suspend fun packageInspect(
        operation: StructuredPrivilegedOperation.PackageInspect,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        validatePackage(operation.packageName)?.let { return it }
        val metadata = packageMetadataReader.packageMetadata(operation.packageName)
            ?: return failure("PACKAGE_NOT_FOUND", "Package is not installed for the current user.")
        val appOps = runArgv(
            executable = CMD_EXECUTABLE,
            arguments = listOf(
                "appops",
                "get",
                "--user",
                packageMetadataReader.currentUserId.toString(),
                operation.packageName,
            ),
            cancelled = cancelled,
            current = current,
            maxOutputBytes = PACKAGE_INSPECT_OUTPUT_BYTES,
        )
        appOps.failure?.let { return it }
        val parsedAppOps = parseAppOpsSummary(appOps.stdout)
        return success(
            code = "PACKAGE_INSPECTED",
            message = "Package inspected.",
            data = buildJsonObject {
                put("package_name", metadata.packageName)
                metadata.label?.let { put("label", it) }
                metadata.versionName?.let { put("version_name", it) }
                put("version_code", metadata.versionCode)
                put("uid", metadata.uid)
                put("enabled", metadata.enabled)
                put("suspended", metadata.suspended)
                put("stopped", metadata.stopped)
                metadata.installSource?.let { put("install_source", it) }
                put("user_id", packageMetadataReader.currentUserId)
                put("protected", metadata.packageName.lowercase() in protectedPackages)
                put("runtime_permissions", buildJsonArray {
                    metadata.runtimePermissions.take(MAX_PERMISSION_SUMMARY).forEach { permission ->
                        addJsonObject {
                            put("permission", permission.permission)
                            put("granted", permission.granted)
                            put("shell_may_manage", permission.shellMayManage)
                        }
                    }
                })
                put("runtime_permissions_truncated", metadata.runtimePermissions.size > MAX_PERMISSION_SUMMARY)
                put("appops", buildJsonArray {
                    parsedAppOps.entries.forEach { (op, mode) ->
                        addJsonObject {
                            put("op", op)
                            put("mode", mode)
                        }
                    }
                })
                put("appops_count", parsedAppOps.entries.size)
                put("appops_truncated", parsedAppOps.truncated || appOps.truncated)
            },
        )
    }

    private fun parseAppOpsSummary(output: String): AppOpsSummary {
        val entries = output.lineSequence().mapNotNull { line ->
            APP_OP_SUMMARY.find(line)?.let { match ->
                match.groupValues[1] to match.groupValues[2].lowercase()
            }
        }.toList()
        return AppOpsSummary(
            entries = entries.take(MAX_APP_OP_SUMMARY),
            truncated = entries.size > MAX_APP_OP_SUMMARY,
        )
    }

    private data class AppOpsSummary(
        val entries: List<Pair<String, String>>,
        val truncated: Boolean,
    )

    private suspend fun dumpsys(
        operation: StructuredPrivilegedOperation.Dumpsys,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        val service = operation.service.trim().lowercase()
        if (service !in DUMPSYS_SERVICES) {
            return failure("INVALID_ARGUMENT", "dumpsys service is not in the allowlist.")
        }
        if ('\u0000' in operation.filter || operation.filter.length > MAX_FILTER_CHARS) {
            return failure("INVALID_ARGUMENT", "dumpsys filter is invalid or too large.")
        }
        if (operation.maxOutputBytes !in 1..PrivilegedCommandLimits.MAX_COMBINED_OUTPUT_BYTES) {
            return failure("INVALID_ARGUMENT", "max_output_bytes is outside the supported range.")
        }
        val command = runArgv(
            executable = DUMPSYS_EXECUTABLE,
            arguments = listOf(service),
            cancelled = cancelled,
            current = current,
            maxOutputBytes = operation.maxOutputBytes,
        )
        command.failure?.let { return it }
        val output = if (operation.filter.isBlank()) {
            command.stdout.trimEnd()
        } else {
            command.stdout.lineSequence()
                .filter { operation.filter in it }
                .joinToString("\n")
        }
        return success(
            code = "DUMPSYS_READ",
            message = "System service state read.",
            data = buildJsonObject {
                put("service", service)
                put("filter", operation.filter)
                put("output", output)
                put("truncated", command.truncated)
            },
        )
    }

    private fun settingArguments(
        action: String,
        namespace: StructuredSettingNamespace,
        key: String,
    ) = listOf(
        "--user",
        packageMetadataReader.currentUserId.toString(),
        action,
        namespace.wire,
        key,
    )

    private suspend fun processList(
        operation: StructuredPrivilegedOperation.ProcessList,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        if (operation.maxProcesses !in 1..MAX_PROCESSES) {
            return failure("INVALID_ARGUMENT", "max_processes is outside the supported range.")
        }
        var format = "structured"
        var command = runArgv(
            executable = PS_EXECUTABLE,
            arguments = listOf("-A", "-o", "PID,UID,STAT,NAME,ARGS"),
            cancelled = cancelled,
            current = current,
            maxOutputBytes = PROCESS_OUTPUT_BYTES,
        )
        if (command.failure?.code == "COMMAND_FAILED") {
            format = "fallback"
            command = runArgv(
                executable = PS_EXECUTABLE,
                arguments = listOf("-A"),
                cancelled = cancelled,
                current = current,
                maxOutputBytes = PROCESS_OUTPUT_BYTES,
            )
        }
        command.failure?.let { return it }
        val parsed = if (format == "structured") {
            parseStructuredProcesses(command.stdout)
        } else {
            parseFallbackProcesses(command.stdout)
        }
        val selected = parsed.take(operation.maxProcesses)
        return success(
            code = "PROCESS_LISTED",
            message = "Processes listed.",
            data = buildJsonObject {
                put("format", format)
                put("count", selected.size)
                put("truncated", command.truncated || parsed.size > selected.size)
                put("processes", buildJsonArray {
                    selected.forEach { process ->
                        addJsonObject {
                            process.pid?.let { put("pid", it) }
                            put("uid", process.uid)
                            put("state", process.state)
                            put("name", process.name)
                            process.packageName?.let { put("package_name", it) }
                            put("args", process.args)
                        }
                    }
                })
            },
        )
    }

    private fun parseStructuredProcesses(output: String): List<ProcessRecord> = output
        .lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val columns = line.trim().split(Regex("\\s+"), limit = 5)
            if (columns.size < 4) return@mapNotNull null
            val args = columns.getOrElse(4) { columns[3] }
            ProcessRecord(
                pid = columns[0].toIntOrNull(),
                uid = columns[1],
                state = columns[2],
                name = columns[3],
                args = args,
                packageName = inferPackage(columns[3], args),
            )
        }
        .toList()

    private fun parseFallbackProcesses(output: String): List<ProcessRecord> = output
        .lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val columns = line.trim().split(Regex("\\s+"))
            if (columns.size < 2) return@mapNotNull null
            val pidIndex = columns.indexOfFirst { it.toIntOrNull() != null }
            if (pidIndex < 0) return@mapNotNull null
            val name = columns.last()
            ProcessRecord(
                pid = columns[pidIndex].toIntOrNull(),
                uid = columns.first(),
                state = columns.getOrNull(columns.size - 2).orEmpty(),
                name = name,
                args = name,
                packageName = inferPackage(name, name),
            )
        }
        .toList()

    private fun inferPackage(name: String, args: String): String? = sequenceOf(args, name)
        .map { it.substringBefore(':') }
        .firstOrNull { PACKAGE_NAME.matches(it) && '.' in it }

    private data class ProcessRecord(
        val pid: Int?,
        val uid: String,
        val state: String,
        val name: String,
        val args: String,
        val packageName: String?,
    )

    private suspend fun serviceStatus(
        operation: StructuredPrivilegedOperation.ServiceStatus,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        val target = operation.target.trim().lowercase()
        if (target !in SERVICE_STATUS_TARGETS) {
            return failure("INVALID_ARGUMENT", "Service status target is not supported.")
        }
        val serviceName = operation.serviceName?.trim()?.lowercase()
        if (target == "android_binder" && serviceName !in DUMPSYS_SERVICES) {
            return failure("INVALID_ARGUMENT", "Android Binder service is not in the allowlist.")
        }
        if (target != "android_binder" && serviceName != null) {
            return failure("INVALID_ARGUMENT", "service_name is only valid for android_binder.")
        }
        if (target != "android_binder") return runtimeStatusProvider.status(target, serviceName)
        val command = runArgv(
            executable = SERVICE_EXECUTABLE,
            arguments = listOf("check", serviceName!!),
            cancelled = cancelled,
            current = current,
        )
        command.failure?.let { return it }
        val found = BINDER_SERVICE_FOUND.containsMatchIn(command.stdout)
        return success(
            code = "SERVICE_STATUS",
            message = "Android Binder service status observed.",
            data = buildJsonObject {
                put("target", target)
                put("service_name", serviceName)
                put("state", if (found) "READY" else "SERVICE_OFFLINE")
                put("found", found)
                put("output", command.stdout.trim().take(MAX_SERVICE_STATUS_OUTPUT_CHARS))
            },
        )
    }

    private suspend fun packageMutation(
        packageName: String,
        mutation: PackageMutation,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        validatePackage(packageName)?.let { return it }
        val normalized = packageName.lowercase()
        if (normalized in protectedPackages && mutation in PROTECTED_PACKAGE_MUTATIONS) {
            return failure(
                "PROTECTED_RESOURCE",
                "This operation cannot weaken a protected control package.",
            )
        }
        if (normalized in criticalSystemPackages && mutation in CRITICAL_SYSTEM_PACKAGE_MUTATIONS) {
            return failure(
                "SYSTEM_PACKAGE_PROTECTED",
                "This operation cannot disable, suspend, or uninstall a critical system package.",
            )
        }
        val old = packageMetadataReader.packageMetadata(packageName)
            ?: return failure("PACKAGE_NOT_FOUND", "Package is not installed for the current user.")
        val write = runArgv(
            executable = PM_EXECUTABLE,
            arguments = mutation.arguments(packageMetadataReader.currentUserId, packageName),
            cancelled = cancelled,
            current = current,
        )
        write.failure?.let { return it.remapCommandFailure("OEM_REJECTED") }
        val actual = packageMetadataReader.packageMetadata(packageName)
        val verified = mutation.isVerified(actual)
        return StructuredPrivilegedResult(
            ok = verified,
            code = if (verified) mutation.successCode else "PACKAGE_VERIFY_FAILED",
            message = if (verified) {
                "Package state updated and verified."
            } else {
                "Package state did not match the requested state."
            },
            data = buildJsonObject {
                put("package_name", packageName)
                put("user_id", packageMetadataReader.currentUserId)
                put("operation", mutation.wire)
                put("old_installed", true)
                put("old_enabled", old.enabled)
                put("old_suspended", old.suspended)
                put("requested_installed", mutation != PackageMutation.UNINSTALL)
                mutation.requestedEnabled?.let { put("requested_enabled", it) }
                mutation.requestedSuspended?.let { put("requested_suspended", it) }
                put("actual_installed", actual != null)
                actual?.let {
                    put("actual_enabled", it.enabled)
                    put("actual_suspended", it.suspended)
                }
                put("command_code", write.commandCode)
                put("command_output", write.stdout.trim().take(MAX_PACKAGE_COMMAND_OUTPUT_CHARS))
            },
            verified = verified,
        )
    }

    private enum class PackageMutation(
        val wire: String,
        val successCode: String,
        val requestedEnabled: Boolean? = null,
        val requestedSuspended: Boolean? = null,
    ) {
        ENABLE("enable", "PACKAGE_ENABLED", requestedEnabled = true),
        DISABLE("disable", "PACKAGE_DISABLED", requestedEnabled = false),
        SUSPEND("suspend", "PACKAGE_SUSPENDED", requestedSuspended = true),
        UNSUSPEND("unsuspend", "PACKAGE_UNSUSPENDED", requestedSuspended = false),
        UNINSTALL("uninstall", "PACKAGE_UNINSTALLED"),
        ;

        fun arguments(userId: Int, packageName: String): List<String> = when (this) {
            ENABLE -> listOf("enable", "--user", userId.toString(), packageName)
            DISABLE -> listOf("disable-user", "--user", userId.toString(), packageName)
            SUSPEND -> listOf("suspend", "--user", userId.toString(), packageName)
            UNSUSPEND -> listOf("unsuspend", "--user", userId.toString(), packageName)
            UNINSTALL -> listOf("uninstall", "--user", userId.toString(), packageName)
        }

        fun isVerified(actual: StructuredPackageMetadata?): Boolean = when (this) {
            ENABLE -> actual?.enabled == true
            DISABLE -> actual?.enabled == false
            SUSPEND -> actual?.suspended == true
            UNSUSPEND -> actual?.suspended == false
            UNINSTALL -> actual == null
        }
    }

    private suspend fun resolveIntent(
        operation: StructuredPrivilegedOperation.ResolveIntent,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        validateIntent(operation.intent)?.let { return it }
        val command = runArgv(
            executable = PM_EXECUTABLE,
            arguments = listOf(
                "resolve-activity",
                "--user",
                packageMetadataReader.currentUserId.toString(),
                "--brief",
            ) + intentArguments(operation.intent),
            cancelled = cancelled,
            current = current,
            maxOutputBytes = INTENT_OUTPUT_BYTES,
        )
        command.failure?.let { return it.remapCommandFailure("ANDROID_REJECTED") }
        val component = parseComponents(command.stdout).firstOrNull()
        return success(
            code = "INTENT_RESOLVED",
            message = if (component == null) "No matching activity was found." else "Intent resolved.",
            data = buildJsonObject {
                put("found", component != null)
                component?.let {
                    put("component", it)
                    put("package_name", it.substringBefore('/'))
                }
                put("user_id", packageMetadataReader.currentUserId)
                put("truncated", command.truncated)
            },
        )
    }

    private suspend fun queryActivities(
        operation: StructuredPrivilegedOperation.QueryActivities,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        validateIntent(operation.intent)?.let { return it }
        if (operation.maxResults !in 1..MAX_INTENT_RESULTS) {
            return failure("INVALID_ARGUMENT", "max_results is outside the supported range.")
        }
        val command = runArgv(
            executable = PM_EXECUTABLE,
            arguments = listOf(
                "query-activities",
                "--user",
                packageMetadataReader.currentUserId.toString(),
                "--brief",
                "--components",
            ) + intentArguments(operation.intent),
            cancelled = cancelled,
            current = current,
            maxOutputBytes = INTENT_OUTPUT_BYTES,
        )
        command.failure?.let { return it.remapCommandFailure("ANDROID_REJECTED") }
        val all = parseComponents(command.stdout).distinct()
        val selected = all.take(operation.maxResults)
        return success(
            code = "ACTIVITIES_QUERIED",
            message = "Matching activities queried.",
            data = buildJsonObject {
                put("count", selected.size)
                put("activities", buildJsonArray {
                    selected.forEach { component ->
                        addJsonObject {
                            put("component", component)
                            put("package_name", component.substringBefore('/'))
                        }
                    }
                })
                put("truncated", command.truncated || all.size > selected.size)
                put("user_id", packageMetadataReader.currentUserId)
            },
        )
    }

    private suspend fun startActivity(
        operation: StructuredPrivilegedOperation.StartActivity,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        validateIntent(operation.intent)?.let { return it }
        val command = runArgv(
            executable = AM_EXECUTABLE,
            arguments = listOf(
                "start",
                "--user",
                packageMetadataReader.currentUserId.toString(),
                "-W",
            ) + intentArguments(operation.intent),
            cancelled = cancelled,
            current = current,
            maxOutputBytes = INTENT_OUTPUT_BYTES,
        )
        command.failure?.let { return it.remapCommandFailure("ANDROID_REJECTED") }
        val status = ACTIVITY_STATUS.find(command.stdout)?.groupValues?.get(1)?.lowercase()
        val component = ACTIVITY_COMPONENT.find(command.stdout)?.groupValues?.get(1)
        return success(
            code = "ACTIVITY_STARTED",
            message = "Activity start request completed.",
            data = buildJsonObject {
                put("dispatched", true)
                status?.let { put("status", it) }
                component?.let { put("component", it) }
                put("user_id", packageMetadataReader.currentUserId)
                put("output", command.stdout.trim().take(MAX_INTENT_STATUS_OUTPUT_CHARS))
                put("truncated", command.truncated)
            },
        )
    }

    private suspend fun sendBroadcast(
        operation: StructuredPrivilegedOperation.SendBroadcast,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        validateIntent(operation.intent, requireAction = true)?.let { return it }
        val command = runArgv(
            executable = AM_EXECUTABLE,
            arguments = listOf(
                "broadcast",
                "--user",
                packageMetadataReader.currentUserId.toString(),
            ) + intentArguments(operation.intent),
            cancelled = cancelled,
            current = current,
            maxOutputBytes = INTENT_OUTPUT_BYTES,
        )
        command.failure?.let { return it.remapCommandFailure("ANDROID_REJECTED") }
        val resultCode = BROADCAST_RESULT.find(command.stdout)?.groupValues?.get(1)?.toIntOrNull()
        return success(
            code = "BROADCAST_DISPATCHED",
            message = "Broadcast request completed.",
            data = buildJsonObject {
                put("dispatched", true)
                resultCode?.let { put("result_code", it) }
                put("user_id", packageMetadataReader.currentUserId)
                put("output", command.stdout.trim().take(MAX_INTENT_STATUS_OUTPUT_CHARS))
                put("truncated", command.truncated)
            },
        )
    }

    private fun validateIntent(
        intent: StructuredIntentSpec,
        requireAction: Boolean = false,
    ): StructuredPrivilegedResult? {
        if (requireAction && intent.action.isNullOrBlank()) {
            return failure("INVALID_ARGUMENT", "An explicit action is required for this operation.")
        }
        if (listOf(intent.action, intent.component, intent.packageName, intent.dataUri).all { it.isNullOrBlank() }) {
            return failure("INVALID_ARGUMENT", "Intent must include an action, component, package, or data URI.")
        }
        intent.action?.let {
            if (it.isBlank() || it.length > MAX_INTENT_ACTION_CHARS || !INTENT_ACTION.matches(it)) {
                return failure("INVALID_ARGUMENT", "Intent action is invalid.")
            }
        }
        intent.packageName?.let { validatePackage(it)?.let { failure -> return failure } }
        intent.component?.let { component ->
            if (component.length > MAX_COMPONENT_CHARS || !COMPONENT_NAME.matches(component)) {
                return failure("INVALID_ARGUMENT", "Intent component is invalid.")
            }
            val componentPackage = component.substringBefore('/')
            validatePackage(componentPackage)?.let { return it }
            if (intent.packageName != null && intent.packageName != componentPackage) {
                return failure("INVALID_ARGUMENT", "Intent package and component package do not match.")
            }
        }
        intent.dataUri?.let {
            if (it.length > MAX_URI_CHARS || it.any { ch -> ch.isISOControl() } ||
                !URI_WITH_SCHEME.matches(it)) {
                return failure("INVALID_ARGUMENT", "Intent data_uri must be a bounded URI with an explicit scheme.")
            }
        }
        intent.mimeType?.let {
            if (it.length > MAX_MIME_CHARS || !MIME_TYPE.matches(it)) {
                return failure("INVALID_ARGUMENT", "Intent mime_type is invalid.")
            }
        }
        if (intent.categories.size > MAX_INTENT_CATEGORIES || intent.categories.any {
                it.isBlank() || it.length > MAX_INTENT_ACTION_CHARS || !INTENT_ACTION.matches(it)
            }) {
            return failure("INVALID_ARGUMENT", "Intent categories are invalid or too numerous.")
        }
        if (intent.extras.size > MAX_INTENT_EXTRAS) {
            return failure("INVALID_ARGUMENT", "Intent has too many extras.")
        }
        intent.extras.forEach { (key, value) ->
            if (key.isBlank() || key.length > MAX_EXTRA_KEY_CHARS || !EXTRA_KEY.matches(key)) {
                return failure("INVALID_ARGUMENT", "Intent extra key is invalid.")
            }
            if (value is StructuredIntentExtraValue.Text &&
                ('\u0000' in value.value || value.value.toByteArray(Charsets.UTF_8).size > MAX_EXTRA_TEXT_BYTES)
            ) {
                return failure("INVALID_ARGUMENT", "Intent string extra is invalid or too large.")
            }
            if (value is StructuredIntentExtraValue.DoubleValue && !value.value.isFinite()) {
                return failure("INVALID_ARGUMENT", "Intent numeric extra must be finite.")
            }
        }
        return null
    }

    private fun intentArguments(intent: StructuredIntentSpec): List<String> = buildList {
        intent.action?.let { addAll(listOf("-a", it)) }
        intent.component?.let { addAll(listOf("-n", it)) }
        intent.packageName?.let { addAll(listOf("-p", it)) }
        intent.dataUri?.let { addAll(listOf("-d", it)) }
        intent.mimeType?.let { addAll(listOf("-t", it)) }
        intent.categories.forEach { addAll(listOf("-c", it)) }
        intent.extras.toSortedMap().forEach { (key, value) ->
            when (value) {
                is StructuredIntentExtraValue.Text -> addAll(listOf("--es", key, value.value))
                is StructuredIntentExtraValue.BooleanValue ->
                    addAll(listOf("--ez", key, value.value.toString()))
                is StructuredIntentExtraValue.LongValue -> if (
                    value.value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                ) {
                    addAll(listOf("--ei", key, value.value.toString()))
                } else {
                    addAll(listOf("--el", key, value.value.toString()))
                }
                is StructuredIntentExtraValue.DoubleValue ->
                    addAll(listOf("--ef", key, value.value.toString()))
            }
        }
    }

    private fun parseComponents(output: String): List<String> = COMPONENT_IN_OUTPUT
        .findAll(output)
        .map { it.value }
        .filterNot { it.startsWith("android.intent", ignoreCase = true) }
        .toList()

    private suspend fun logcatRead(
        operation: StructuredPrivilegedOperation.LogcatRead,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        validateLiteralFilter(operation.filter)?.let { return it }
        validateOutputLimit(operation.maxOutputBytes)?.let { return it }
        if (operation.maxLines !in 1..MAX_LOGCAT_LINES) {
            return failure("INVALID_ARGUMENT", "max_lines is outside the supported range.")
        }
        val command = runArgv(
            executable = LOGCAT_EXECUTABLE,
            arguments = listOf("-d", "-v", "threadtime", "-t", operation.maxLines.toString()),
            cancelled = cancelled,
            current = current,
            maxOutputBytes = operation.maxOutputBytes,
        )
        command.failure?.let { return it }
        val output = literalFilter(command.stdout, packageName = null, filter = operation.filter)
        return success(
            code = "LOGCAT_READ",
            message = "Recent logcat output read.",
            data = buildJsonObject {
                put("output", output)
                put("line_count", output.lineSequence().count { it.isNotBlank() })
                put("truncated", command.truncated)
            },
        )
    }

    private suspend fun windowState(
        operation: StructuredPrivilegedOperation.WindowState,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        validateOutputLimit(operation.maxOutputBytes)?.let { return it }
        val command = runArgv(
            executable = DUMPSYS_EXECUTABLE,
            arguments = listOf("window", "windows"),
            cancelled = cancelled,
            current = current,
            maxOutputBytes = operation.maxOutputBytes,
        )
        command.failure?.let { return it }
        val currentFocus = CURRENT_FOCUS.find(command.stdout)?.groupValues?.get(1)
        val focusedApp = FOCUSED_APP.find(command.stdout)?.groupValues?.get(1)
        val component = currentFocus ?: focusedApp
        val summary = command.stdout.lineSequence()
            .filter { line -> WINDOW_SUMMARY_MARKERS.any(line::contains) }
            .joinToString("\n")
            .take(MAX_DIAGNOSTIC_OUTPUT_CHARS)
        return success(
            code = "WINDOW_STATE_READ",
            message = "Current window state read.",
            data = buildJsonObject {
                put("found", component != null)
                currentFocus?.let { put("current_focus", it) }
                focusedApp?.let { put("focused_app", it) }
                component?.let {
                    put("package_name", it.substringBefore('/'))
                    put("component", it)
                }
                put("summary", summary)
                put("truncated", command.truncated || summary.length >= MAX_DIAGNOSTIC_OUTPUT_CHARS)
            },
        )
    }

    private suspend fun diagnosticStatus(
        service: String,
        packageName: String?,
        filter: String,
        maxOutputBytes: Int,
        successCode: String,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
    ): StructuredPrivilegedResult {
        packageName?.let { validatePackage(it)?.let { failure -> return failure } }
        validateLiteralFilter(filter)?.let { return it }
        validateOutputLimit(maxOutputBytes)?.let { return it }
        val command = runArgv(
            executable = DUMPSYS_EXECUTABLE,
            arguments = listOf(service),
            cancelled = cancelled,
            current = current,
            maxOutputBytes = maxOutputBytes,
        )
        command.failure?.let { return it }
        val output = literalFilter(command.stdout, packageName, filter)
            .take(MAX_DIAGNOSTIC_OUTPUT_CHARS)
        return success(
            code = successCode,
            message = "System diagnostic state read.",
            data = buildJsonObject {
                put("service", service)
                packageName?.let { put("package_name", it) }
                put("filter", filter)
                put("output", output)
                put(
                    "truncated",
                    command.truncated || output.length >= MAX_DIAGNOSTIC_OUTPUT_CHARS,
                )
            },
        )
    }

    private fun validateLiteralFilter(filter: String): StructuredPrivilegedResult? = when {
        '\u0000' in filter || filter.length > MAX_FILTER_CHARS ->
            failure("INVALID_ARGUMENT", "Literal filter is invalid or too large.")
        else -> null
    }

    private fun validateOutputLimit(maxOutputBytes: Int): StructuredPrivilegedResult? = when {
        maxOutputBytes !in 1..PrivilegedCommandLimits.MAX_COMBINED_OUTPUT_BYTES ->
            failure("INVALID_ARGUMENT", "max_output_bytes is outside the supported range.")
        else -> null
    }

    private fun literalFilter(
        output: String,
        packageName: String?,
        filter: String,
    ): String = output.lineSequence()
        .filter { line -> packageName == null || packageName in line }
        .filter { line -> filter.isBlank() || filter in line }
        .joinToString("\n")
        .trimEnd()

    private suspend fun runArgv(
        executable: String,
        arguments: List<String>,
        cancelled: AtomicBoolean,
        current: AtomicReference<ToolExecutionHandle?>,
        maxOutputBytes: Int = PrivilegedCommandLimits.DEFAULT_OUTPUT_BYTES,
    ): CommandOutput {
        if (cancelled.get()) return CommandOutput(failure = failure("COMMAND_CANCELLED", "Operation was cancelled."))
        if (isEmergencyStopActive()) {
            return CommandOutput(failure = failure("EMERGENCY_STOP_ACTIVE", "Emergency stop is active."))
        }
        val handle = bridge.startCommand(
            PrivilegedCommandInput(
                mode = PrivilegedCommandMode.ARGV,
                executable = executable,
                arguments = arguments,
                maxOutputBytes = maxOutputBytes,
            ),
        )
        current.set(handle)
        if (cancelled.get()) handle.requestCancel(ToolCancelReason.USER_INTERRUPTED)
        return try {
            val text = handle.awaitResult().filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            val result = runCatching { PrivilegedCommandJson.decodeResult(text) }.getOrElse {
                return CommandOutput(failure = failure("INVALID_BRIDGE_RESPONSE", "The bridge returned invalid JSON."))
            }
            if (!result.ok) {
                CommandOutput(
                    commandCode = result.code,
                    failure = bridgeFailure(result),
                )
            } else {
                CommandOutput(
                    stdout = result.data?.stdout.orEmpty(),
                    stderr = result.data?.stderr.orEmpty(),
                    commandCode = result.code,
                    truncated = result.data?.truncated == true,
                )
            }
        } finally {
            current.compareAndSet(handle, null)
        }
    }

    private fun bridgeFailure(result: PrivilegedCommandResult): StructuredPrivilegedResult {
        val mapped = when (result.code) {
            "SHIZUKU_UNAVAILABLE", "BINDER_DIED" -> "BRIDGE_UNAVAILABLE"
            "COMMAND_CANCELLED", "CANCEL_REQUESTED" -> "COMMAND_CANCELLED"
            "TERMINATION_UNKNOWN" -> "TERMINATION_UNKNOWN"
            "COMMAND_REJECTED" -> "PROTECTED_RESOURCE"
            else -> "COMMAND_FAILED"
        }
        return failure(mapped, result.message, buildJsonObject { put("command_code", result.code) })
    }

    private fun validateSettingKey(key: String): StructuredPrivilegedResult? = when {
        key.isBlank() || key.length > MAX_SETTING_KEY_CHARS || !SETTING_KEY.matches(key) ->
            failure("INVALID_ARGUMENT", "Setting key is invalid.")
        else -> null
    }

    private fun validateSettingValue(value: String): StructuredPrivilegedResult? = when {
        '\u0000' in value || value.toByteArray(Charsets.UTF_8).size > MAX_SETTING_VALUE_BYTES ->
            failure("INVALID_ARGUMENT", "Setting value is invalid or too large.")
        else -> null
    }

    private fun validateSettingWrite(
        namespace: StructuredSettingNamespace,
        key: String,
        verify: Boolean,
    ): StructuredPrivilegedResult? = when {
        !verify -> failure("INVALID_ARGUMENT", "verify must be true for privileged writes.")
        key.lowercase() in PROTECTED_SETTING_KEYS ->
            failure("SETTING_PROTECTED", "This setting is protected from structured writes.")
        namespace == StructuredSettingNamespace.SECURE &&
            key.lowercase() in PROTECTED_SECURE_SETTING_KEYS ->
            failure("SETTING_PROTECTED", "This secure setting is protected from structured writes.")
        else -> null
    }

    private fun success(
        code: String,
        message: String,
        data: kotlinx.serialization.json.JsonObject = buildJsonObject { },
    ) = StructuredPrivilegedResult(true, code, message, data)

    private fun failure(
        code: String,
        message: String,
        data: kotlinx.serialization.json.JsonObject = buildJsonObject { },
    ) = StructuredPrivilegedResult(false, code, message, data, verified = false)

    private fun StructuredPrivilegedResult.remapCommandFailure(code: String): StructuredPrivilegedResult =
        if (this.code == "COMMAND_FAILED") copy(code = code) else this

    private data class CommandOutput(
        val stdout: String = "",
        val stderr: String = "",
        val commandCode: String = "",
        val truncated: Boolean = false,
        val failure: StructuredPrivilegedResult? = null,
    )

    private fun String.settingValueOrNull(): String? = trimEnd('\r', '\n')
        .takeUnless { it == "null" }

    companion object {
        private const val SETTINGS_EXECUTABLE = "/system/bin/settings"
        private const val CMD_EXECUTABLE = "/system/bin/cmd"
        private const val PM_EXECUTABLE = "/system/bin/pm"
        private const val AM_EXECUTABLE = "/system/bin/am"
        private const val DUMPSYS_EXECUTABLE = "/system/bin/dumpsys"
        private const val PS_EXECUTABLE = "/system/bin/ps"
        private const val SERVICE_EXECUTABLE = "/system/bin/service"
        private const val LOGCAT_EXECUTABLE = "/system/bin/logcat"
        private const val MAX_SETTING_KEY_CHARS = 128
        private const val MAX_SETTING_VALUE_BYTES = 4 * 1024
        private const val MAX_PACKAGE_NAME_CHARS = 255
        private const val MAX_APP_OP_CHARS = 128
        private const val MAX_PERMISSION_CHARS = 255
        private const val PACKAGE_INSPECT_OUTPUT_BYTES = 128 * 1024
        private const val MAX_PERMISSION_SUMMARY = 100
        private const val MAX_APP_OP_SUMMARY = 100
        private const val MAX_FILTER_CHARS = 256
        private const val MAX_PROCESSES = 500
        private const val PROCESS_OUTPUT_BYTES = 256 * 1024
        private const val MAX_SERVICE_STATUS_OUTPUT_CHARS = 4 * 1024
        private const val MAX_PACKAGE_COMMAND_OUTPUT_CHARS = 4 * 1024
        private const val INTENT_OUTPUT_BYTES = 128 * 1024
        private const val MAX_INTENT_STATUS_OUTPUT_CHARS = 8 * 1024
        private const val MAX_INTENT_RESULTS = 100
        private const val MAX_INTENT_ACTION_CHARS = 255
        private const val MAX_COMPONENT_CHARS = 512
        private const val MAX_URI_CHARS = 4 * 1024
        private const val MAX_MIME_CHARS = 255
        private const val MAX_INTENT_CATEGORIES = 16
        private const val MAX_INTENT_EXTRAS = 32
        private const val MAX_EXTRA_KEY_CHARS = 128
        private const val MAX_EXTRA_TEXT_BYTES = 4 * 1024
        private const val MAX_LOGCAT_LINES = 2_000
        private const val MAX_DIAGNOSTIC_OUTPUT_CHARS = 256 * 1024
        private val SETTING_KEY = Regex("^[A-Za-z0-9_.:-]+$")
        private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*$")
        private val APP_OP = Regex("^[A-Z][A-Z0-9_]*$")
        private val PERMISSION_NAME = Regex("^[A-Za-z][A-Za-z0-9_.]*$")
        private val INTENT_ACTION = Regex("^[A-Za-z][A-Za-z0-9_.]*$")
        private val COMPONENT_NAME = Regex(
            "^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*/" +
                "(?:\\.[A-Za-z0-9_\$]+|[A-Za-z][A-Za-z0-9_.\$]*)$",
        )
        private val COMPONENT_IN_OUTPUT = Regex(
            "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*/" +
                "(?:\\.[A-Za-z0-9_\$]+|[A-Za-z][A-Za-z0-9_.\$]*)",
        )
        private val URI_WITH_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*$")
        private val MIME_TYPE = Regex("^[A-Za-z0-9!#\$&^_.+-]+/(?:[A-Za-z0-9!#\$&^_.+-]+|\\*)$")
        private val EXTRA_KEY = Regex("^[A-Za-z][A-Za-z0-9_.:-]*$")
        private val ACTIVITY_STATUS = Regex("(?im)^Status:\\s*([^\\s]+)")
        private val ACTIVITY_COMPONENT = Regex(
            "(?im)^(?:Activity|cmp):\\s*(" + COMPONENT_IN_OUTPUT.pattern + ")",
        )
        private val BROADCAST_RESULT = Regex("(?i)Broadcast completed:\\s*result=(-?\\d+)")
        private val CURRENT_FOCUS = Regex(
            "(?im)^\\s*mCurrentFocus=.*?\\s(" + COMPONENT_IN_OUTPUT.pattern + ")(?:\\s|\\})",
        )
        private val FOCUSED_APP = Regex(
            "(?im)^\\s*mFocusedApp=.*?\\s(" + COMPONENT_IN_OUTPUT.pattern + ")(?:\\s|\\})",
        )
        private val WINDOW_SUMMARY_MARKERS = listOf(
            "mCurrentFocus=",
            "mFocusedApp=",
            "mObscuringWindow=",
            "mTopFocusedDisplayId=",
        )
        private val APP_OP_SUMMARY = Regex(
            "^\\s*([A-Z][A-Z0-9_]*)\\s*:\\s*(?:mode=)?" +
                "(allow|ignore|deny|default|foreground|errored)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val BINDER_SERVICE_FOUND = Regex(
            "(?im)^\\s*service\\s+[^:]+:\\s*found\\b",
        )
        val PROTECTED_SETTING_KEYS = setOf(
            "enabled_accessibility_services",
            "enabled_notification_listeners",
            "default_input_method",
            "adb_enabled",
            "development_settings_enabled",
            "device_provisioned",
            "user_setup_complete",
        )
        private val PROTECTED_SECURE_SETTING_KEYS = setOf(
            "assistant",
            "voice_interaction_service",
            "voice_recognition_service",
            "magicvoice_stop_status",
            "magic_voice_service_state",
            "invoke_hivoice_keypress_type",
            "power_invoke_hivoice_used",
            "hw_long_home_voice_assistant",
        )
        val DUMPSYS_SERVICES = setOf(
            "activity",
            "package",
            "window",
            "display",
            "notification",
            "audio",
            "battery",
            "deviceidle",
            "jobscheduler",
            "alarm",
            "accessibility",
            "input_method",
            "telecom",
            "phone",
            "wifi",
            "connectivity",
            "power",
            "appops",
        )
        val SERVICE_STATUS_TARGETS = setOf(
            "accessibility",
            "notification_listener",
            "shizuku",
            "workspace_process_service",
            "rikkahub_foreground",
            "android_binder",
        )
        private val PROTECTED_PACKAGE_MUTATIONS = setOf(
            PackageMutation.DISABLE,
            PackageMutation.SUSPEND,
            PackageMutation.UNINSTALL,
        )
        private val CRITICAL_SYSTEM_PACKAGE_MUTATIONS = setOf(
            PackageMutation.DISABLE,
            PackageMutation.SUSPEND,
            PackageMutation.UNINSTALL,
        )
    }
}

private class StructuredPrivilegedExecutionHandle(
    override val executionId: String,
    private val result: Deferred<StructuredPrivilegedResult>,
    private val cancelled: AtomicBoolean,
    private val current: AtomicReference<ToolExecutionHandle?>,
) : ToolExecutionHandle {
    override suspend fun awaitResult(): ToolResult = try {
        listOf(UIMessagePart.Text(STRUCTURED_RESULT_JSON.encodeToString(result.await())))
    } catch (cancelledWait: CancellationException) {
        requestCancel(ToolCancelReason.SHUTDOWN)
        throw cancelledWait
    }

    override fun requestCancel(reason: ToolCancelReason): CancelRequestResult {
        if (!cancelled.compareAndSet(false, true)) return CancelRequestResult.AlreadyRequested
        current.get()?.requestCancel(reason)
        result.start()
        return CancelRequestResult.Requested
    }

    override suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState {
        val completed = withTimeoutOrNull(gracePeriod.inWholeMilliseconds.coerceAtLeast(1)) {
            result.await()
        }
        if (completed != null) {
            return if (completed.code == "TERMINATION_UNKNOWN") {
                ToolTerminationState.Unknown
            } else {
                ToolTerminationState.StoppedConfirmed
            }
        }
        return current.get()?.awaitTermination(gracePeriod)
            ?: if (cancelled.get()) ToolTerminationState.CancelRequested else ToolTerminationState.StillRunning
    }
}

private val STRUCTURED_RESULT_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
}
