package me.rerere.rikkahub.execution

import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.LegacyToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.StartableTool
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolResult
import me.rerere.rikkahub.data.ai.tools.ToolTerminationState
import me.rerere.rikkahub.data.execution.CompletionPolicy
import me.rerere.rikkahub.data.execution.ExecutionRuntime
import me.rerere.rikkahub.data.execution.ManagedExecutionRegistration
import me.rerere.rikkahub.data.execution.ManagedExecutionReservation
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceProcessManager
import me.rerere.workspace.WorkspaceProcessResult
import me.rerere.workspace.WorkspaceProcessStartRequest
import me.rerere.workspace.WorkspaceProcessStatus
import me.rerere.workspace.WorkspaceProcessStopReason
import me.rerere.workspace.WorkspaceRestartPolicy

class WorkspaceManagedProcessStarter(
    private val manager: WorkspaceProcessManager,
    private val registration: ManagedExecutionRegistration,
    private val scope: CoroutineScope,
) {
    suspend fun start(
        request: WorkspaceProcessStartRequest,
        context: ToolExecutionContext,
        completionPolicy: CompletionPolicy,
    ): ToolExecutionHandle {
        val reserved = manager.reserveProcessId(request)
        val reservedProcess = reserved.process
            ?: return rejected(context, reserved.code, reserved.message)
        val executionId = managedExecutionId(
            ManagedExecutionRuntime.WORKSPACE,
            reservedProcess.processId,
        )
        try {
            registration.reserve(
                context = context,
                reservation = ManagedExecutionReservation(
                    executionId = executionId,
                    runtime = ExecutionRuntime.WORKSPACE,
                    completionPolicy = completionPolicy,
                ),
            )
        } catch (failure: Throwable) {
            manager.stop(
                processId = reservedProcess.processId,
                force = true,
                reason = WorkspaceProcessStopReason.USER,
            )
            throw failure
        }
        val started = manager.startReserved(reservedProcess.processId)
        val snapshot = started.process
        if (!started.ok || snapshot == null || !snapshot.alive) {
            runCatching { registration.failed(executionId, started.code.lowercase()) }
            return WorkspaceProcessExecutionHandle(
                executionId = executionId,
                processId = reservedProcess.processId,
                manager = manager,
                registration = registration,
                scope = scope,
                result = workspaceResult(started, executionId),
            )
        }
        runCatching {
            registration.running(
                executionId = executionId,
                runtimeInstanceMarker = snapshot.runtimeInstanceMarker,
            )
        }
        return WorkspaceProcessExecutionHandle(
            executionId = executionId,
            processId = snapshot.processId,
            manager = manager,
            registration = registration,
            scope = scope,
            result = workspaceResult(started, executionId),
        )
    }

    private fun rejected(
        context: ToolExecutionContext,
        code: String,
        message: String,
    ): ToolExecutionHandle = LegacyToolExecutionHandle(
        executionId = "not-started:${context.runId}:${context.toolCallId}",
        result = scope.async {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("ok", false)
                put("code", code)
                put("message", message)
            }.toString()))
        },
    )
}

class WorkspaceProcessStartableFactory(
    private val starter: WorkspaceManagedProcessStarter,
    private val workspaceRepository: WorkspaceRepository,
    private val scope: CoroutineScope,
) {
    fun create(@Suppress("UNUSED_PARAMETER") legacyTool: Tool): StartableTool = object : StartableTool {
        override suspend fun start(
            args: JsonElement,
            context: ToolExecutionContext,
        ): ToolExecutionHandle {
            val params = args.jsonObject
            val explicitWorkspaceId = params.string("workspace_id")?.takeIf(String::isNotBlank)
            val workspaceId = explicitWorkspaceId ?: context.workspaceId
                ?: return rejected(context, "WORKSPACE_NOT_FOUND", "No Workspace was selected.")
            val workspace = workspaceRepository.getById(workspaceId)
                ?: return rejected(context, "WORKSPACE_NOT_FOUND", "Workspace was not found.")
            val command = params.string("command").orEmpty()
            if (command.isBlank()) {
                return rejected(context, "INVALID_ARGUMENTS", "Command is required.")
            }
            val restartPolicy = when (params.string("restart_policy")?.lowercase() ?: "never") {
                "never" -> WorkspaceRestartPolicy.NEVER
                "on_failure" -> WorkspaceRestartPolicy.ON_FAILURE
                "always" -> WorkspaceRestartPolicy.ALWAYS
                else -> return rejected(context, "INVALID_ARGUMENTS", "Invalid restart_policy.")
            }
            val cwd = params.string("cwd") ?: if (
                explicitWorkspaceId == null || workspaceId == context.workspaceId
            ) {
                context.workspaceCwd.orEmpty().removePrefix("/workspace/").removePrefix("/workspace")
            } else {
                ""
            }
            return starter.start(
                request = WorkspaceProcessStartRequest(
                    workspaceId = workspace.id,
                    workspaceRoot = workspace.root,
                    name = params.string("name").orEmpty(),
                    command = command,
                    cwd = cwd,
                    keepAwake = params.boolean("keep_awake") ?: false,
                    restartPolicy = restartPolicy,
                ),
                context = context,
                completionPolicy = if (restartPolicy == WorkspaceRestartPolicy.NEVER) {
                    CompletionPolicy.DETACH_BACKGROUND
                } else {
                    CompletionPolicy.SERVICE_EXPECTED_TO_STAY_ALIVE
                },
            )
        }

        private fun rejected(
            context: ToolExecutionContext,
            code: String,
            message: String,
        ): ToolExecutionHandle = LegacyToolExecutionHandle(
            executionId = "not-started:${context.runId}:${context.toolCallId}",
            result = scope.async {
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("ok", false)
                    put("code", code)
                    put("message", message)
                }.toString()))
            },
        )
    }
}

class WorkspaceProcessExecutionHandle(
    override val executionId: String,
    private val processId: String,
    private val manager: WorkspaceProcessManager,
    private val registration: ManagedExecutionRegistration,
    private val scope: CoroutineScope,
    private val result: ToolResult,
) : ToolExecutionHandle {
    private val stopRequest = AtomicReference<Deferred<WorkspaceProcessResult>?>(null)

    override suspend fun awaitResult(): ToolResult = result

    override fun requestCancel(reason: ToolCancelReason): CancelRequestResult {
        val job = scope.async {
            runCatching { registration.cancelRequested(executionId) }
            manager.stop(
                processId = processId,
                force = false,
                reason = WorkspaceProcessStopReason.USER,
            )
        }
        return if (stopRequest.compareAndSet(null, job)) {
            CancelRequestResult.Requested
        } else {
            job.cancel()
            CancelRequestResult.AlreadyRequested
        }
    }

    override suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState {
        val request = stopRequest.get() ?: return probeTermination()
        withTimeoutOrNull(gracePeriod.inWholeMilliseconds) { request.await() }
        val firstProbe = probeTermination()
        if (firstProbe != ToolTerminationState.StillRunning) return firstProbe
        manager.stop(
            processId = processId,
            force = true,
            reason = WorkspaceProcessStopReason.USER,
        )
        return probeTermination()
    }

    private suspend fun probeTermination(): ToolTerminationState {
        val status = manager.status(processId)
        val snapshot = status.process ?: return ToolTerminationState.Unknown
        val stopped = !snapshot.alive && snapshot.status in setOf(
            WorkspaceProcessStatus.EXITED,
            WorkspaceProcessStatus.STOPPED,
            WorkspaceProcessStatus.FAILED,
            WorkspaceProcessStatus.LOST,
        )
        runCatching { registration.cancellationProbed(executionId, stopped) }
        return when {
            stopped -> ToolTerminationState.StoppedConfirmed
            snapshot.alive -> ToolTerminationState.StillRunning
            else -> ToolTerminationState.Unknown
        }
    }
}

private fun workspaceResult(
    result: WorkspaceProcessResult,
    executionId: String,
): ToolResult = listOf(UIMessagePart.Text(buildJsonObject {
    put("ok", result.ok)
    put("code", result.code)
    put("message", result.message)
    put("execution_id", executionId)
    result.process?.let { process ->
        put("process_id", process.processId)
        put("status", process.status.name.lowercase())
        put("alive", process.alive)
        put("restart_policy", process.restartPolicy.name.lowercase())
    }
}.toString()))

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
