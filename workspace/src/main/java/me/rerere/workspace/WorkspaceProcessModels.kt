package me.rerere.workspace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val MAX_MANAGED_WORKSPACE_PROCESSES = 6
const val DEFAULT_WORKSPACE_PROCESS_LOG_TAIL_BYTES = 32 * 1024
const val MAX_WORKSPACE_PROCESS_LOG_TAIL_BYTES = 256 * 1024
const val MAX_WORKSPACE_PROCESS_LOG_FILE_BYTES = 8L * 1024 * 1024

private val WORKSPACE_PROCESS_ID_REGEX = Regex("wp_[A-Za-z0-9-]{8,64}")

fun requireValidWorkspaceProcessId(processId: String): String = processId.also {
    require(WORKSPACE_PROCESS_ID_REGEX.matches(it)) { "Invalid workspace process id" }
}

@Serializable
enum class WorkspaceProcessStatus {
    STARTING,
    RUNNING,
    EXITED,
    STOPPING,
    STOPPED,
    FAILED,
    RECOVERING,
    LOST,
}

enum class WorkspaceProcessManagerState {
    NOT_STARTED,
    LOADING,
    READY,
    FAILED,
}

@Serializable
enum class WorkspaceRestartPolicy {
    @SerialName("never")
    NEVER,

    @SerialName("on_failure")
    ON_FAILURE,

    @SerialName("always")
    ALWAYS,
}

@Serializable
enum class WorkspaceDesiredState {
    @SerialName("running")
    RUNNING,

    @SerialName("stopped")
    STOPPED,
}

@Serializable
enum class WorkspaceProcessLogStream {
    @SerialName("stdout")
    STDOUT,

    @SerialName("stderr")
    STDERR,

    @SerialName("both")
    BOTH,
}

enum class WorkspaceProcessStopReason {
    USER,
    RESTART,
    EMERGENCY_STOP,
    WORKSPACE_DELETE,
    SHUTDOWN,
}

@Serializable
data class WorkspaceProcessDefinition(
    val schemaVersion: Int = 1,
    val id: String,
    val workspaceId: String,
    val name: String,
    val command: String,
    val cwd: String = "",
    val keepAwake: Boolean = false,
    val allowSharedStorage: Boolean = false,
    val restartPolicy: WorkspaceRestartPolicy = WorkspaceRestartPolicy.NEVER,
    val desiredState: WorkspaceDesiredState = WorkspaceDesiredState.RUNNING,
    val createdAt: Long,
    val lastStartedAt: Long? = null,
    val lastExitCode: Int? = null,
    val lastExitAt: Long? = null,
    val recentRestartTimestamps: List<Long> = emptyList(),
    val lastErrorCode: String? = null,
) {
    init {
        require(schemaVersion == 1) { "Unsupported workspace process schema: $schemaVersion" }
        requireValidWorkspaceProcessId(id)
        require(workspaceId.isNotBlank()) { "workspaceId is required" }
        require(name.isNotBlank()) { "name is required" }
        require(command.isNotBlank()) { "command is required" }
        require('\u0000' !in command) { "command contains NUL" }
        require('\u0000' !in cwd) { "cwd contains NUL" }
    }
}

data class WorkspaceProcessStartRequest(
    val workspaceId: String,
    val workspaceRoot: String,
    val name: String,
    val command: String,
    val cwd: String = "",
    val keepAwake: Boolean = false,
    val allowSharedStorage: Boolean = false,
    val restartPolicy: WorkspaceRestartPolicy = WorkspaceRestartPolicy.NEVER,
)

data class WorkspaceProcessSnapshot(
    val processId: String,
    val workspaceId: String,
    val name: String,
    val status: WorkspaceProcessStatus,
    val hostPid: Long? = null,
    val alive: Boolean = false,
    val startedAt: Long? = null,
    /** Changes whenever a new native instance is launched for this stable process id. */
    val runtimeInstanceMarker: String? = null,
    val restartPolicy: WorkspaceRestartPolicy,
    val desiredState: WorkspaceDesiredState,
    val keepAwake: Boolean,
    val lastExitCode: Int? = null,
    val lastExitAt: Long? = null,
    val lastErrorCode: String? = null,
)

data class WorkspaceProcessLogs(
    val stdout: String = "",
    val stderr: String = "",
    val truncated: Boolean = false,
)

data class WorkspaceProcessResult(
    val ok: Boolean,
    val code: String,
    val message: String,
    val process: WorkspaceProcessSnapshot? = null,
    val logs: WorkspaceProcessLogs? = null,
)

data class WorkspaceStopAllResult(
    val ok: Boolean,
    val code: String,
    val stoppedProcessIds: List<String> = emptyList(),
    val failedProcessIds: List<String> = emptyList(),
)

data class WorkspaceProcessSummary(
    val activeCount: Int = 0,
    val keepAwakeCount: Int = 0,
    val recoveringCount: Int = 0,
    val desiredRunningCount: Int = 0,
)
