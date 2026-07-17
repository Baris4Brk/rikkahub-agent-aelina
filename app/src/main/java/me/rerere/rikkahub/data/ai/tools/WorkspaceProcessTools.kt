package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import me.rerere.rikkahub.service.chat.runSuspendCatching
import me.rerere.workspace.DEFAULT_WORKSPACE_PROCESS_LOG_TAIL_BYTES
import me.rerere.workspace.MAX_WORKSPACE_PROCESS_LOG_TAIL_BYTES
import me.rerere.workspace.WorkspaceProcessLogStream
import me.rerere.workspace.WorkspaceProcessLogs
import me.rerere.workspace.WorkspaceProcessManager
import me.rerere.workspace.WorkspaceProcessResult
import me.rerere.workspace.WorkspaceProcessSnapshot
import me.rerere.workspace.WorkspaceProcessStartRequest
import me.rerere.workspace.WorkspaceProcessStopReason
import me.rerere.workspace.WorkspaceRestartPolicy

val WORKSPACE_PROCESS_TOOL_NAMES: Set<String> = setOf(
    "workspace_process_start",
    "workspace_process_list",
    "workspace_process_status",
    "workspace_process_logs",
    "workspace_process_stop",
    "workspace_process_restart",
)

fun shouldInjectWorkspaceProcessTools(
    privilege: PrivilegedSessionContext,
    origin: ToolCallOrigin,
    isHeadless: Boolean,
): Boolean = privilege.isPrivileged &&
    InvocationSurfacePolicy.canInjectPrivilegedTools(origin, isHeadless)

suspend fun createWorkspaceProcessTools(
    manager: WorkspaceProcessManager,
    workspaceRepository: WorkspaceRepository,
    defaultWorkspaceId: String?,
    defaultCwd: String?,
): List<Tool> = listOf(
    Tool(
        name = "workspace_process_start",
        description = """
            Start a long-running foreground program inside a RikkaHub Workspace PRoot environment.
            The program survives the current tool call, chat turn, chat screen, and Workspace Terminal.
            Use workspace_shell for finite commands. Run the long-lived program in the foreground;
            never use nohup or a trailing ampersand. The returned process_id is required for status,
            logs, stop, and restart. Use keep_awake only when the CPU must continue while the screen is off.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    stringProperty("workspace_id", "Optional Workspace ID; defaults to the assistant's Workspace.")
                    stringProperty("name", "Human-readable process name.")
                    stringProperty("command", "Foreground shell command to run inside PRoot.")
                    stringProperty("cwd", "Absolute PRoot path, or a path relative to /workspace.")
                    put("keep_awake", buildJsonObject { put("type", "boolean") })
                    put("restart_policy", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("never"); add("on_failure"); add("always") })
                    })
                },
                required = listOf("command"),
            )
        },
        needsApproval = { true },
        execute = { args ->
            toolResult {
                val params = args.jsonObject
                val explicitWorkspaceId = params.string("workspace_id")?.takeIf(String::isNotBlank)
                val workspaceId = resolveWorkspaceProcessTarget(explicitWorkspaceId, defaultWorkspaceId)
                    ?: return@toolResult errorJson("WORKSPACE_NOT_FOUND", "No Workspace was selected.")
                val workspace = workspaceRepository.getById(workspaceId)
                    ?: return@toolResult errorJson("WORKSPACE_NOT_FOUND", "Workspace was not found.")
                val cwd = params.string("cwd") ?: if (explicitWorkspaceId == null || workspaceId == defaultWorkspaceId) {
                    defaultCwd.orEmpty().removePrefix("/workspace/").removePrefix("/workspace")
                } else {
                    ""
                }
                val restartPolicy = when (params.string("restart_policy")?.lowercase() ?: "never") {
                    "never" -> WorkspaceRestartPolicy.NEVER
                    "on_failure" -> WorkspaceRestartPolicy.ON_FAILURE
                    "always" -> WorkspaceRestartPolicy.ALWAYS
                    else -> return@toolResult errorJson("INVALID_ARGUMENTS", "Invalid restart_policy.")
                }
                manager.start(
                    WorkspaceProcessStartRequest(
                        workspaceId = workspace.id,
                        workspaceRoot = workspace.root,
                        name = params.string("name").orEmpty(),
                        command = params.string("command").orEmpty(),
                        cwd = cwd,
                        keepAwake = params.boolean("keep_awake") ?: false,
                        restartPolicy = restartPolicy,
                    ),
                ).toJson()
            }
        },
    ),
    Tool(
        name = "workspace_process_list",
        description = "List managed long-running PRoot programs and their stable process IDs.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    stringProperty("workspace_id", "Optional Workspace ID filter.")
                    put("include_stopped", buildJsonObject { put("type", "boolean") })
                },
            )
        },
        needsApproval = { true },
        execute = { args ->
            toolResult {
                val params = args.jsonObject
                val workspaceId = resolveWorkspaceProcessTarget(
                    explicitWorkspaceId = params.string("workspace_id"),
                    defaultWorkspaceId = defaultWorkspaceId,
                ) ?: return@toolResult errorJson("WORKSPACE_NOT_FOUND", "No Workspace was selected.")
                if (workspaceRepository.getById(workspaceId) == null) {
                    return@toolResult errorJson("WORKSPACE_NOT_FOUND", "Workspace was not found.")
                }
                val processes = manager.list(
                    workspaceId = workspaceId,
                    includeStopped = params.boolean("include_stopped") ?: false,
                )
                buildJsonObject {
                    put("ok", true)
                    put("code", "OK")
                    put("message", "Managed workspace processes.")
                    put("data", buildJsonObject {
                        put("processes", buildJsonArray { processes.forEach { add(it.toJson()) } })
                    })
                }
            }
        },
    ),
    processIdTool(
        name = "workspace_process_status",
        description = "Get the current state of one managed PRoot program.",
    ) { manager.status(it).toJson() },
    Tool(
        name = "workspace_process_logs",
        description = "Read a bounded tail of stdout, stderr, or both for one managed PRoot program.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    stringProperty("process_id", "Managed process ID returned by workspace_process_start.")
                    put("stream", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("stdout"); add("stderr"); add("both") })
                    })
                    put("tail_bytes", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", MAX_WORKSPACE_PROCESS_LOG_TAIL_BYTES)
                    })
                },
                required = listOf("process_id"),
            )
        },
        needsApproval = { true },
        execute = { args ->
            toolResult {
                val params = args.jsonObject
                val processId = params.string("process_id").orEmpty()
                val stream = when (params.string("stream")?.lowercase() ?: "both") {
                    "stdout" -> WorkspaceProcessLogStream.STDOUT
                    "stderr" -> WorkspaceProcessLogStream.STDERR
                    "both" -> WorkspaceProcessLogStream.BOTH
                    else -> return@toolResult errorJson("INVALID_ARGUMENTS", "Invalid log stream.")
                }
                manager.logs(
                    processId = processId,
                    stream = stream,
                    tailBytes = params.int("tail_bytes") ?: DEFAULT_WORKSPACE_PROCESS_LOG_TAIL_BYTES,
                ).toJson()
            }
        },
    ),
    Tool(
        name = "workspace_process_stop",
        description = "Stop a managed PRoot program. Set force=true when graceful termination is not enough.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    stringProperty("process_id", "Managed process ID returned by workspace_process_start.")
                    put("force", buildJsonObject { put("type", "boolean") })
                },
                required = listOf("process_id"),
            )
        },
        needsApproval = { true },
        execute = { args ->
            toolResult {
                val params = args.jsonObject
                manager.stop(
                    processId = params.string("process_id").orEmpty(),
                    force = params.boolean("force") ?: false,
                    reason = WorkspaceProcessStopReason.USER,
                ).toJson()
            }
        },
    ),
    processIdTool(
        name = "workspace_process_restart",
        description = "Restart a managed PRoot program with the same process ID and saved definition.",
    ) { manager.restart(it).toJson() },
)

private fun processIdTool(
    name: String,
    description: String,
    execute: suspend (String) -> JsonObject,
) = Tool(
    name = name,
    description = description,
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                stringProperty("process_id", "Managed process ID returned by workspace_process_start.")
            },
            required = listOf("process_id"),
        )
    },
    needsApproval = { true },
    execute = { args -> toolResult { execute(args.jsonObject.string("process_id").orEmpty()) } },
)

private suspend fun toolResult(block: suspend () -> JsonObject): List<UIMessagePart> {
    val result = runSuspendCatching(block).getOrElse { error ->
        errorJson("INTERNAL_ERROR", error.message?.take(300) ?: "Workspace process operation failed.")
    }
    return listOf(UIMessagePart.Text(result.toString()))
}

private fun WorkspaceProcessResult.toJson(): JsonObject = buildJsonObject {
    put("ok", ok)
    put("code", code)
    put("message", message)
    put("data", buildJsonObject {
        process?.let { put("process", it.toJson()) }
        logs?.let { put("logs", it.toJson()) }
    })
}

internal fun WorkspaceProcessSnapshot.toJson(): JsonObject = buildJsonObject {
    put("process_id", processId)
    put("workspace_id", workspaceId)
    put("name", name)
    put("status", status.name.lowercase())
    hostPid?.let { put("host_pid", it) }
    put("alive", alive)
    startedAt?.let { put("started_at", it) }
    put("restart_policy", restartPolicy.name.lowercase())
    put("desired_state", desiredState.name.lowercase())
    put("keep_awake", keepAwake)
    lastExitCode?.let { put("last_exit_code", it) }
    lastExitAt?.let { put("last_exit_at", it) }
    lastErrorCode?.let { put("last_error_code", it) }
}

private fun WorkspaceProcessLogs.toJson(): JsonObject = buildJsonObject {
    put("stdout", stdout)
    put("stderr", stderr)
    put("truncated", truncated)
}

private fun errorJson(code: String, message: String): JsonObject = buildJsonObject {
    put("ok", false)
    put("code", code)
    put("message", message)
    put("data", buildJsonObject {})
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

internal fun resolveWorkspaceProcessTarget(
    explicitWorkspaceId: String?,
    defaultWorkspaceId: String?,
): String? = explicitWorkspaceId?.takeIf(String::isNotBlank)
    ?: defaultWorkspaceId?.takeIf(String::isNotBlank)

private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(name: String, description: String) {
    put(name, buildJsonObject {
        put("type", "string")
        put("description", description)
    })
}
