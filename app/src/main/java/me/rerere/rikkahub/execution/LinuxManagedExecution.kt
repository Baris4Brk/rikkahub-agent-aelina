package me.rerere.rikkahub.execution

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
import me.rerere.rikkahub.data.ai.tools.LegacyToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.LinuxProfileType
import me.rerere.rikkahub.data.ai.tools.LinuxRouteRequest
import me.rerere.rikkahub.data.ai.tools.LinuxRuntimeRouter
import me.rerere.rikkahub.data.ai.tools.StartableTool
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.local.termuxRunCommandTool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.execution.CompletionPolicy
import me.rerere.rikkahub.data.files.SharedExchangeDirectory
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceProcessStartRequest
import me.rerere.workspace.WorkspaceRestartPolicy
import me.rerere.workspace.WorkspaceStorageMode

/** Gives canonical linux_run a real Termux or Workspace process handle when one exists. */
class LinuxManagedStartableFactory(
    private val appContext: Context,
    private val termuxFactory: TermuxManagedStartableFactory,
    private val workspaceRepository: WorkspaceRepository,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
    private val workspaceStarter: WorkspaceManagedProcessStarter,
) {
    fun create(linuxTool: Tool): StartableTool = object : StartableTool {
        override suspend fun start(
            args: JsonElement,
            context: ToolExecutionContext,
        ): ToolExecutionHandle {
            val params = args.jsonObject
            val workspaceId = params["workspace_id"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: context.workspaceId
                ?: settingsStore.settingsFlow.value.assistants
                    .firstOrNull { it.id.toString() == context.assistantId }
                    ?.workspaceId?.toString()
            val workspace = workspaceId?.let { workspaceRepository.getById(it) }
            val mode = workspace?.storageMode?.let { raw ->
                WorkspaceStorageMode.entries.firstOrNull { it.name == raw }
            }
            val requested = params["profile"]?.jsonPrimitive?.contentOrNull?.uppercase()?.let { raw ->
                LinuxProfileType.entries.firstOrNull { it.name == raw }
            } ?: LinuxProfileType.AUTO
            val profile = LinuxRuntimeRouter.route(
                LinuxRouteRequest(
                    requested = requested,
                    workspaceMode = mode,
                    ubuntuRequired = params.bool("ubuntu_required"),
                    isolated = params.bool("isolated"),
                ),
            )
            if (profile == LinuxProfileType.TERMUX_NATIVE) {
                val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank {
                    if (workspace != null && mode == WorkspaceStorageMode.SHARED) {
                        "/data/data/com.termux/files/home/storage/shared/" +
                            "${SharedExchangeDirectory.DIRECTORY_NAME}/workspaces/${workspace.root}"
                    } else {
                        "/data/data/com.termux/files/home"
                    }
                }
                val translated = buildJsonObject {
                    put("command", params["command"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    put("working_dir", cwd)
                    put("background", params.bool("background"))
                    put("interactive", false)
                    put("timeout_seconds", params["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 0)
                }
                return termuxFactory.create(termuxRunCommandTool(appContext)).start(translated, context)
            }

            val background = params.bool("background")
            val command = params["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (background && workspace != null && command.isNotBlank() &&
                HardlineCommandGuard.checkCommand(command) == null
            ) {
                return workspaceStarter.start(
                    request = WorkspaceProcessStartRequest(
                        workspaceId = workspace.id,
                        workspaceRoot = workspace.root,
                        name = "linux-run",
                        command = command,
                        cwd = params["cwd"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            .removePrefix("/workspace/")
                            .removePrefix("/workspace"),
                        keepAwake = false,
                        allowSharedStorage = true,
                        restartPolicy = WorkspaceRestartPolicy.NEVER,
                    ),
                    context = context,
                    completionPolicy = CompletionPolicy.DETACH_BACKGROUND,
                )
            }

            // Finite Workspace commands have no independently addressable native process in the
            // current repository API. Keep them explicitly inline instead of inventing a UUID.
            return LegacyToolExecutionHandle(
                executionId = "inline:${context.runId}:${context.toolCallId}",
                result = scope.async(Dispatchers.IO) { linuxTool.execute(args) },
            )
        }
    }
}

private fun kotlinx.serialization.json.JsonObject.bool(name: String): Boolean =
    get(name)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
