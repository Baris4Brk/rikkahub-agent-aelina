package me.rerere.rikkahub.service.chat

import me.rerere.rikkahub.data.ai.GenerationRunControl
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface RuntimeState {
    data object Hydrating : RuntimeState
    data object Idle : RuntimeState
    data object Running : RuntimeState
    data object Paused : RuntimeState
    data class Cancelling(val runId: Uuid) : RuntimeState
    data object WaitingApproval : RuntimeState
    data class HydrationFailed(val message: String?) : RuntimeState
    data class Fatal(val cause: Throwable?) : RuntimeState
}

enum class HydrationState { NotHydrated, Hydrating, Hydrated, Failed }

data class QueueStatus(
    val paused: Boolean,
    val pendingCount: Int,
    val activeCommandId: Uuid?,
    /** Pending normal commands in FIFO order, excluding the active run. */
    val pendingCommandIds: List<Uuid> = emptyList(),
)

data class QueuedMessageUiEntry(
    val commandId: Uuid,
    val content: RawUserContent,
    val position: Int,
    val createdAt: Instant,
)

enum class RunKind { LLM_GENERATION, FAST_PATH, ROUTINE }

enum class RunCapability {
    SOFT_STEERABLE,
    PROVIDER_CANCELLABLE,
    TOOL_CANCELLABLE,
    PARALLEL_TOOLS,
}

data class ActiveRun(
    val id: Uuid,
    val commandId: Uuid,
    val kind: RunKind,
    val capabilities: Set<RunCapability>,
    val control: GenerationRunControl,
    val job: kotlinx.coroutines.Job,
    val outcome: kotlinx.coroutines.CompletableDeferred<RunOutcome>,
)

sealed interface RunOutcome {
    data class Completed(val finalRevision: Long = 0L) : RunOutcome
    data class Interrupted(val cleanup: InterruptCleanupResult) : RunOutcome
    data class Stopped(val cleanup: InterruptCleanupResult) : RunOutcome
    data class WaitingApproval(val pendingToolIds: Set<String>) : RunOutcome
    data class Failed(val error: Throwable) : RunOutcome
    data class Rejected(val reason: String) : RunOutcome
    data class Conflict(val reason: String) : RunOutcome
}

sealed interface InterruptCleanupResult {
    data object Completed : InterruptCleanupResult
    data class DeferredRepairScheduled(val repairId: Uuid) : InterruptCleanupResult
    data class PartialFailure(val reason: String) : InterruptCleanupResult
}

enum class AssistantMessageState { DRAFT, STREAMING, WAITING_TOOL, COMPLETED, INTERRUPTED, FAILED }
enum class ToolCallState { PENDING, APPROVED, EXECUTING, COMPLETED, CANCELLED, TERMINATION_UNKNOWN }

data class ToolCallRuntimeState(
    val toolCallId: String,
    val state: ToolCallState,
    val resultCommitted: Boolean,
)
