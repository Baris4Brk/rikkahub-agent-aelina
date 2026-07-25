package me.rerere.rikkahub.execution

import android.content.Context
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.LinuxProfileType
import me.rerere.rikkahub.data.ai.tools.LinuxRouteRequest
import me.rerere.rikkahub.data.ai.tools.LinuxRuntimeRouter
import me.rerere.rikkahub.data.ai.tools.StartableTool
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolResult
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import me.rerere.rikkahub.data.ai.tools.local.termuxRunCommandTool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SharedExchangeDirectory
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceStorageMode

/** Gives canonical linux_run the existing authenticated Termux handle and real cancellation. */
class LinuxManagedStartableFactory(
    private val appContext: Context,
    private val termuxFactory: TermuxManagedStartableFactory,
    private val workspaceRepository: WorkspaceRepository,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
) {
    fun create(linuxTool: Tool): StartableTool = object : StartableTool {
        override suspend fun start(
            args: JsonElement,
            context: ToolExecutionContext,
        ): ToolExecutionHandle {
            val params = args.jsonObject
        val workspaceId = params["workspace_id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
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
        val profile = LinuxRuntimeRouter.route(LinuxRouteRequest(
            requested = requested,
            workspaceMode = mode,
            ubuntuRequired = params.bool("ubuntu_required"),
            isolated = params.bool("isolated"),
        ))
        if (profile == LinuxProfileType.TERMUX_NATIVE) {
            val cwd = params["cwd"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank {
                if (workspace != null && mode == WorkspaceStorageMode.SHARED) {
                    "/data/data/com.termux/files/home/storage/shared/${SharedExchangeDirectory.DIRECTORY_NAME}/workspaces/${workspace.root}"
                } else "/data/data/com.termux/files/home"
            }
            val translated = buildJsonObject {
                put("command", params["command"]?.jsonPrimitive?.contentOrNull.orEmpty())
                put("working_dir", cwd)
                put("background", params.bool("background"))
                put("interactive", false)
                put("timeout_seconds", params["timeout_seconds"]?.jsonPrimitive?.intOrNull ?: 0)
            }
            return termuxFactory.create(termuxRunCommandTool(appContext))
                .start(translated, context)
        } else {
            val deferred = scope.async(Dispatchers.IO) { linuxTool.execute(args) }
            return LinuxWorkspaceExecutionHandle(
                executionId = "workspace:${UUID.randomUUID()}",
                result = deferred,
            )
            }
        }
    }
}

private class LinuxWorkspaceExecutionHandle(
    override val executionId: String,
    private val result: Deferred<ToolResult>,
) : ToolExecutionHandle {
    private val cancellationRequested = AtomicBoolean(false)

    override suspend fun awaitResult(): ToolResult = result.await()

    override fun requestCancel(reason: ToolCancelReason): CancelRequestResult {
        if (!cancellationRequested.compareAndSet(false, true)) return CancelRequestResult.AlreadyRequested
        result.cancel(CancellationException(reason.message))
        return CancelRequestResult.Requested
    }

    override suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState {
        val joined = withTimeoutOrNull(gracePeriod.inWholeMilliseconds) { result.join(); true } == true
        return if (joined && cancellationRequested.get()) ToolTerminationState.StoppedConfirmed
        else if (joined) ToolTerminationState.StoppedConfirmed
        else ToolTerminationState.Unknown
    }
}

private fun kotlinx.serialization.json.JsonObject.bool(name: String): Boolean =
    get(name)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
