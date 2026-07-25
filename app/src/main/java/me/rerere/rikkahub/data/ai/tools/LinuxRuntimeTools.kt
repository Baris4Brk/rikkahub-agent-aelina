package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.termuxRunCommandTool
import me.rerere.rikkahub.data.ai.tools.local.termuxSessionKillTool
import me.rerere.rikkahub.data.ai.tools.local.termuxSessionListTool
import me.rerere.rikkahub.data.ai.tools.local.termuxSessionReadTool
import me.rerere.rikkahub.data.ai.tools.local.termuxSessionSendTool
import me.rerere.rikkahub.data.ai.tools.local.termuxSessionStartTool
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.files.SharedExchangeDirectory
import me.rerere.workspace.WorkspaceProcessManager
import me.rerere.workspace.WorkspaceProcessResult
import me.rerere.workspace.WorkspaceProcessStartRequest
import me.rerere.workspace.WorkspaceRestartPolicy
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageMode

enum class LinuxProfileType { AUTO, TERMUX_NATIVE, WORKSPACE_PROOT }

data class LinuxRouteRequest(
    val requested: LinuxProfileType = LinuxProfileType.AUTO,
    val workspaceMode: WorkspaceStorageMode? = null,
    val ubuntuRequired: Boolean = false,
    val isolated: Boolean = false,
)

object LinuxRuntimeRouter {
    fun route(request: LinuxRouteRequest): LinuxProfileType = when (request.requested) {
        LinuxProfileType.TERMUX_NATIVE -> LinuxProfileType.TERMUX_NATIVE
        LinuxProfileType.WORKSPACE_PROOT -> LinuxProfileType.WORKSPACE_PROOT
        LinuxProfileType.AUTO -> when {
            request.ubuntuRequired || request.isolated -> LinuxProfileType.WORKSPACE_PROOT
            request.workspaceMode == WorkspaceStorageMode.PRIVATE -> LinuxProfileType.WORKSPACE_PROOT
            else -> LinuxProfileType.TERMUX_NATIVE
        }
    }
}

fun linuxRuntimeTools(
    context: Context,
    invocation: ToolInvocationContext,
    workspaceRepository: WorkspaceRepository,
    processManager: WorkspaceProcessManager,
): List<Tool> {
    if (invocation.privilege?.expandLocalTools != true) return emptyList()
    val defaultWorkspaceId = invocation.callerWorkspaceId
    val termuxRun = termuxRunCommandTool(context)
    val profileList = Tool(
        name = "linux_profile_list",
        description = "List the real Linux profiles available to this conversation and their file roots.",
        parameters = { InputSchema.Obj(properties = buildJsonObject { }, required = emptyList()) },
        needsApproval = { false },
        execute = {
            val workspace = defaultWorkspaceId?.let { workspaceRepository.getById(it) }
            val termuxInstalled = runCatching {
                context.packageManager.getPackageInfo("com.termux", 0)
            }.isSuccess
            val termuxPermission = ContextCompat.checkSelfPermission(
                context,
                "com.termux.permission.RUN_COMMAND",
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            listOf(UIMessagePart.Text(buildJsonObject {
                put("profiles", buildJsonArray {
                    add(buildJsonObject {
                        put("type", LinuxProfileType.TERMUX_NATIVE.name)
                        put("available", termuxInstalled && termuxPermission)
                        put("workspace_root", "${SharedExchangeDirectory.TERMUX_PATH}/workspaces/<workspaceId>")
                        put("persistent_sessions", true)
                    })
                    add(buildJsonObject {
                        put("type", LinuxProfileType.WORKSPACE_PROOT.name)
                        put("available", workspace?.shellStatus == WorkspaceShellStatus.READY.name)
                        workspace?.let {
                            put("workspace_id", it.id)
                            put("storage_mode", it.storageMode)
                        }
                        put("workspace_root", "/workspace")
                        put("persistent_sessions", false)
                    })
                })
                put("routing", "Ubuntu/private/isolated -> WORKSPACE_PROOT; shared builds -> TERMUX_NATIVE")
            }.toString()))
        },
    )
    val run = Tool(
        name = "linux_run",
        description = "Run a command through the unified Linux runtime. AUTO deterministically selects Workspace PRoot for Ubuntu/private/isolated work and Termux Native for shared builds. A failure is never retried in another profile.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("command", buildJsonObject { put("type", "string") })
                    put("profile", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { LinuxProfileType.entries.forEach { add(it.name) } })
                    })
                    put("workspace_id", buildJsonObject { put("type", "string") })
                    put("cwd", buildJsonObject { put("type", "string") })
                    put("background", buildJsonObject { put("type", "boolean") })
                    put("ubuntu_required", buildJsonObject { put("type", "boolean") })
                    put("isolated", buildJsonObject { put("type", "boolean") })
                    put("package_install", buildJsonObject { put("type", "boolean") })
                    put("timeout_seconds", buildJsonObject { put("type", "integer") })
                },
                required = listOf("command"),
            )
        },
        needsApproval = { true },
        execute = { input ->
            executeLinuxRun(
                input = input,
                context = context,
                defaultWorkspaceId = defaultWorkspaceId,
                workspaceRepository = workspaceRepository,
                processManager = processManager,
                termuxRun = termuxRun,
            )
        },
    )
    val ownerPrefix = "su_" + Integer.toHexString(
        "${invocation.callerAssistantId}:${invocation.callerConversationId}".hashCode(),
    )
    val sessions = listOf(
        ownedTermuxSessionTool(
            delegate = termuxSessionStartTool(context),
            name = "linux_session_create",
            ownerPrefix = ownerPrefix,
            create = true,
        ).copy(description = "Create an owned persistent TERMUX_NATIVE PTY session. Workspace PRoot interactive sessions remain UI-owned."),
        ownedTermuxSessionTool(termuxSessionSendTool(context), "linux_session_exec", ownerPrefix),
        ownedTermuxSessionTool(termuxSessionReadTool(context), "linux_session_inspect", ownerPrefix),
        ownedTermuxSessionTool(termuxSessionListTool(context), "linux_session_list", ownerPrefix, list = true),
        ownedTermuxSessionTool(termuxSessionKillTool(context), "linux_session_close", ownerPrefix),
    )
    return listOf(profileList, run) + sessions
}

private fun ownedTermuxSessionTool(
    delegate: Tool,
    name: String,
    ownerPrefix: String,
    create: Boolean = false,
    list: Boolean = false,
): Tool = delegate.copy(
    name = name,
    execute = execute@ { input ->
        val args = input.jsonObject
        if (!create && !list) {
            val sessionId = args["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!sessionId.startsWith("rk_${ownerPrefix}")) {
                return@execute linuxError("SESSION_NOT_OWNED", "The session does not belong to this selected conversation.")
            }
        }
        val delegatedInput = if (create) {
            buildJsonObject {
                args.forEach { (key, value) -> put(key, value) }
                val friendly = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                put("name", listOf(ownerPrefix, friendly).filter(String::isNotBlank).joinToString("_"))
            }
        } else input
        val result = delegate.execute(delegatedInput)
        if (!list) return@execute result
        result.map { part ->
            if (part !is UIMessagePart.Text) return@map part
            val payload = runCatching { Json.parseToJsonElement(part.text).jsonObject }.getOrNull()
                ?: return@map part
            val sessions = payload["sessions"]?.jsonArray.orEmpty().filter { session ->
                session.jsonObject["session_id"]?.jsonPrimitive?.contentOrNull
                    ?.startsWith("rk_${ownerPrefix}") == true
            }
            UIMessagePart.Text(buildJsonObject {
                put("success", true)
                put("sessions", buildJsonArray { sessions.forEach { add(it) } })
            }.toString())
        }
    },
)

private suspend fun executeLinuxRun(
    input: JsonElement,
    context: Context,
    defaultWorkspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    processManager: WorkspaceProcessManager,
    termuxRun: Tool,
): List<UIMessagePart> {
    val args = input.jsonObject
    val command = args["command"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: return linuxError("INVALID_ARGUMENTS", "command is required")
    HardlineCommandGuard.checkCommand(command)?.let {
        return linuxError("BLOCKED_BY_SAFETY_FLOOR", it)
    }
    val workspaceId = args["workspace_id"]?.jsonPrimitive?.contentOrNull
        ?.takeIf(String::isNotBlank) ?: defaultWorkspaceId
    val workspace = workspaceId?.let { workspaceRepository.getById(it) }
    val requested = args["profile"]?.jsonPrimitive?.contentOrNull?.uppercase()?.let { raw ->
        LinuxProfileType.entries.firstOrNull { it.name == raw }
    } ?: LinuxProfileType.AUTO
    val workspaceMode = workspace?.storageMode?.let { raw ->
        WorkspaceStorageMode.entries.firstOrNull { it.name == raw }
    }
    val profile = LinuxRuntimeRouter.route(
        LinuxRouteRequest(
            requested = requested,
            workspaceMode = workspaceMode,
            ubuntuRequired = args.bool("ubuntu_required"),
            isolated = args.bool("isolated"),
        ),
    )
    val background = args.bool("background")
    val cwd = args["cwd"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val timeoutSeconds = args["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 0
    return when (profile) {
        LinuxProfileType.TERMUX_NATIVE -> {
            val termuxCwd = cwd.ifBlank {
                if (workspace != null && workspaceMode == WorkspaceStorageMode.SHARED) {
                    "/data/data/com.termux/files/home/storage/shared/${SharedExchangeDirectory.DIRECTORY_NAME}/workspaces/${workspace.root}"
                } else {
                    "/data/data/com.termux/files/home"
                }
            }
            termuxRun.execute(buildJsonObject {
                put("command", command)
                put("working_dir", termuxCwd)
                put("background", background)
                put("interactive", false)
                put("timeout_seconds", timeoutSeconds)
            })
        }
        LinuxProfileType.WORKSPACE_PROOT -> {
            val target = workspace ?: return linuxError("WORKSPACE_REQUIRED", "Select a Workspace for WORKSPACE_PROOT.")
            if (target.shellStatus != WorkspaceShellStatus.READY.name) {
                return linuxError("WORKSPACE_NOT_READY", "The Workspace Ubuntu rootfs is not ready.")
            }
            if (background) {
                val started = processManager.start(
                    WorkspaceProcessStartRequest(
                        workspaceId = target.id,
                        workspaceRoot = target.root,
                        name = "linux-run",
                        command = command,
                        cwd = cwd.removePrefix("/workspace/").removePrefix("/workspace"),
                        keepAwake = false,
                        allowSharedStorage = true,
                        restartPolicy = WorkspaceRestartPolicy.NEVER,
                    ),
                )
                val process = started.process
                if (started.ok && process != null) {
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", true)
                        put("profile", profile.name)
                        put("background", true)
                        put("execution_id", "workspace:${process.processId}")
                    }.toString()))
                } else {
                    linuxError(started.code, started.message)
                }
            } else {
                val result = workspaceRepository.executeCommand(
                    id = target.id,
                    command = command,
                    cwd = cwd.removePrefix("/workspace/").removePrefix("/workspace"),
                    timeoutMillis = if (timeoutSeconds > 0) timeoutSeconds.coerceAtMost(600) * 1_000L else 30_000L,
                    allowSharedStorage = true,
                )
                val combined = buildString {
                    append(result.stdout)
                    if (result.stderr.isNotBlank()) append("\n[stderr]\n").append(result.stderr)
                }
                val artifact = if (combined.length > INLINE_OUTPUT_LIMIT) {
                    writeLinuxArtifact(context, combined)
                } else null
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", result.exitCode == 0 && !result.timedOut)
                    put("profile", profile.name)
                    put("exit_code", result.exitCode)
                    put("stdout", result.stdout.take(INLINE_OUTPUT_LIMIT))
                    if (result.stderr.isNotBlank()) put("stderr", result.stderr.take(INLINE_OUTPUT_LIMIT))
                    artifact?.let { put("artifact_path", it.absolutePath) }
                    put("timed_out", result.timedOut)
                    put("truncated", result.truncated)
                }.toString()))
            }
        }
        LinuxProfileType.AUTO -> error("AUTO must be resolved")
    }
}

private fun writeLinuxArtifact(context: Context, output: String): java.io.File {
    val directory = java.io.File(
        context.filesDir,
        "${me.rerere.rikkahub.data.files.FileFolders.TOOL_OUTPUTS}/linux",
    ).apply { mkdirs() }
    return java.io.File(directory, "linux_${java.util.UUID.randomUUID()}.log").apply {
        writeText(output)
    }
}

private const val INLINE_OUTPUT_LIMIT = 32 * 1024

private fun kotlinx.serialization.json.JsonObject.bool(name: String): Boolean =
    get(name)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true

private fun linuxError(code: String, detail: String): List<UIMessagePart> =
    listOf(UIMessagePart.Text(buildJsonObject {
        put("error", code)
        put("detail", detail)
    }.toString()))
