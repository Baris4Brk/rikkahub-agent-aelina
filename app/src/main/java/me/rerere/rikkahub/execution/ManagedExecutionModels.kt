package me.rerere.rikkahub.execution

import kotlinx.coroutines.flow.StateFlow
import me.rerere.rikkahub.data.ai.ToolCallOrigin

enum class ManagedExecutionRuntime(val idPrefix: String) {
    WORKSPACE("workspace"),
    TERMUX("termux"),
    SSH("ssh"),
}

enum class ManagedExecutionStatus {
    STARTING,
    RUNNING,
    EXITED,
    STOP_REQUESTED,
    STOPPED,
    FAILED,
    RECOVERING,
    LOST,
    UNKNOWN,
}

data class ManagedExecutionCaller(
    val assistantId: String,
    val conversationId: String,
    val runId: String,
    val origin: ToolCallOrigin,
    val allowedRuntimes: Set<ManagedExecutionRuntime>,
    val workspaceId: String? = null,
)

data class ManagedExecutionSnapshot(
    val executionId: String,
    val runtime: ManagedExecutionRuntime,
    val name: String,
    val status: ManagedExecutionStatus,
    val alive: Boolean,
    val startedAtMs: Long? = null,
    val runtimeInstanceMarker: String? = null,
    val lastExitCode: Int? = null,
    val terminationUncertain: Boolean = false,
)

data class ManagedExecutionLogs(
    val stdout: String = "",
    val stderr: String = "",
    val truncated: Boolean = false,
)

data class ManagedExecutionState(
    val executions: List<ManagedExecutionSnapshot> = emptyList(),
)

sealed interface ManagedExecutionRequest {
    data class List(
        val caller: ManagedExecutionCaller,
        val includeStopped: Boolean = false,
    ) : ManagedExecutionRequest

    data class Status(
        val caller: ManagedExecutionCaller,
        val executionId: String,
    ) : ManagedExecutionRequest

    data class Logs(
        val caller: ManagedExecutionCaller,
        val executionId: String,
        val tailBytes: Int,
    ) : ManagedExecutionRequest

    data class Stop(
        val caller: ManagedExecutionCaller,
        val executionId: String,
        val force: Boolean = false,
    ) : ManagedExecutionRequest

    data object EmergencyStop : ManagedExecutionRequest

    /** Stops only the selected backends, used when another emergency participant owns one. */
    data class EmergencyStopRuntimes(
        val runtimes: Set<ManagedExecutionRuntime>,
    ) : ManagedExecutionRequest
}

sealed interface ManagedExecutionResult {
    data class Executions(val executions: List<ManagedExecutionSnapshot>) : ManagedExecutionResult
    data class Snapshot(val execution: ManagedExecutionSnapshot) : ManagedExecutionResult
    data class Logs(
        val execution: ManagedExecutionSnapshot,
        val logs: ManagedExecutionLogs,
    ) : ManagedExecutionResult
    data class Stopped(val execution: ManagedExecutionSnapshot) : ManagedExecutionResult
    data class Error(val code: String) : ManagedExecutionResult
}

interface ManagedExecutionCoordinator {
    val state: StateFlow<ManagedExecutionState>
    suspend fun dispatch(request: ManagedExecutionRequest): ManagedExecutionResult
}

interface ManagedExecutionAdapter {
    val runtime: ManagedExecutionRuntime
    suspend fun list(caller: ManagedExecutionCaller, includeStopped: Boolean): List<ManagedExecutionSnapshot>
    suspend fun status(caller: ManagedExecutionCaller, executionId: String): ManagedExecutionResult
    suspend fun logs(
        caller: ManagedExecutionCaller,
        executionId: String,
        tailBytes: Int,
    ): ManagedExecutionResult
    suspend fun stop(
        caller: ManagedExecutionCaller,
        executionId: String,
        force: Boolean,
    ): ManagedExecutionResult
    suspend fun emergencyStop(): List<ManagedExecutionSnapshot>
}

fun managedExecutionId(runtime: ManagedExecutionRuntime, nativeId: String): String =
    "${runtime.idPrefix}:$nativeId"

fun parseManagedExecutionRuntime(executionId: String): ManagedExecutionRuntime? {
    val prefix = executionId.substringBefore(':', missingDelimiterValue = "")
    return ManagedExecutionRuntime.entries.firstOrNull { it.idPrefix == prefix }
}

fun nativeManagedExecutionId(executionId: String): String? = executionId
    .substringAfter(':', missingDelimiterValue = "")
    .takeIf(String::isNotBlank)
