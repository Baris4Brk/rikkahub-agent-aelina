package me.rerere.rikkahub.assistant

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import me.rerere.rikkahub.data.ai.AgentSafetySettings
import me.rerere.rikkahub.data.execution.ApprovalStatus
import me.rerere.rikkahub.data.execution.CompletionPolicy
import me.rerere.rikkahub.data.execution.ExecutionKind
import me.rerere.rikkahub.data.execution.ExecutionProbeScheduler
import me.rerere.rikkahub.data.execution.ExecutionRecord
import me.rerere.rikkahub.data.execution.ExecutionRepository
import me.rerere.rikkahub.data.execution.ExecutionRuntime
import me.rerere.rikkahub.data.execution.ExecutionStatus
import me.rerere.rikkahub.data.execution.PendingToolApprovalDao
import me.rerere.rikkahub.data.execution.PendingToolApprovalRecord
import me.rerere.rikkahub.data.execution.RuntimeContinuity
import me.rerere.rikkahub.data.execution.VerificationState
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.chat.QueueStatus
import me.rerere.rikkahub.service.chat.RuntimeState
import me.rerere.rikkahub.subagent.SubAgentRegistry
import me.rerere.rikkahub.subagent.SubAgentRun
import me.rerere.rikkahub.subagent.SubAgentStatus
import kotlin.uuid.Uuid

data class SecondUserTarget(
    val assistantId: Uuid,
    val conversationId: Uuid,
)

enum class SecondUserPresentationStatus {
    SAFETY_BLOCKED,
    WAITING_APPROVAL,
    CANCEL_REQUESTED,
    TERMINATING,
    TOOL_RUNNING,
    MODEL_GENERATING,
    QUEUED,
    RECOVERING,
    STALE,
    FAILED_RECENTLY,
    SUCCEEDED_RECENTLY,
    BACKGROUND_SERVICE_RUNNING,
    IDLE,
}

enum class SafeExecutionCategory {
    LINUX,
    REMOTE_COMPUTE,
    FILES,
    DEVICE,
    NETWORK,
    AUTOMATION,
    DATA,
    EXTENSION,
    OTHER,
}

/** Parameter-free execution projection safe for trusted presentation surfaces. */
data class SafeExecutionSummary(
    val category: SafeExecutionCategory,
    val runtime: ExecutionRuntime,
    val kind: ExecutionKind,
    val status: ExecutionStatus,
    val verification: VerificationState,
    val completionPolicy: CompletionPolicy,
    val continuity: RuntimeContinuity,
)

data class SecondUserPresentationState(
    val status: SecondUserPresentationStatus,
    val modelGenerating: Boolean,
    val queueCount: Int,
    val activeExecutionCount: Int,
    val backgroundServiceCount: Int,
    val activeSubAgentCount: Int,
    val pendingApprovalCount: Int,
    val safetyBlocked: Boolean,
    val trusted: Boolean,
    val verification: VerificationState,
    val continuity: RuntimeContinuity,
    val cancellable: Boolean,
    val executionSummaries: List<SafeExecutionSummary>,
    val totalExecutionCount: Int,
    val updatedAtMs: Long,
)

fun interface SecondUserPresentationSource {
    fun observe(target: SecondUserTarget): Flow<SecondUserPresentationState>
}

class DefaultSecondUserPresentationSource(
    private val chatService: ChatService,
    private val executionRepository: ExecutionRepository,
    private val approvalDao: PendingToolApprovalDao,
    private val subAgentRegistry: SubAgentRegistry,
    private val safetySettings: AgentSafetySettings,
    private val probeScheduler: ExecutionProbeScheduler,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SecondUserPresentationSource {
    override fun observe(target: SecondUserTarget): Flow<SecondUserPresentationState> {
        val conversationId = target.conversationId.toString()
        val assistantId = target.assistantId.toString()
        val executionSubjectId = "$assistantId:$conversationId"
        val core = combine(
            chatService.getRuntimeStateFlow(target.conversationId),
            chatService.getQueueStatusFlow(target.conversationId),
            executionRepository.observeActiveForSubject(conversationId, executionSubjectId),
            executionRepository.observeRecentTerminalForSubject(conversationId, executionSubjectId, 20),
        ) { runtime, queue, active, recent ->
            PresentationCore(runtime, queue, active, recent)
        }
        val auxiliary = combine(
            approvalDao.observePending(conversationId),
            subAgentRegistry.runs,
            safetySettings.emergencyStopFlow,
            presentationClock(nowMs),
        ) { approvals, subAgents, safetyBlocked, now ->
            PresentationAuxiliary(
                approvals = approvals.filter { it.subjectId == executionSubjectId },
                subAgents = subAgents.values.filter {
                    it.parentAssistantId == assistantId && it.parentChatId == conversationId
                },
                safetyBlocked = safetyBlocked,
                nowMs = now,
            )
        }
        return combine(core, auxiliary) { current, extra ->
            reduceSecondUserPresentation(
                runtime = current.runtime,
                queue = current.queue,
                activeRecords = current.active,
                recentRecords = current.recent,
                approvals = extra.approvals,
                subAgents = extra.subAgents,
                safetyBlocked = extra.safetyBlocked,
                nowMs = extra.nowMs,
            )
        }.onStart {
            // Opening the assistant is an explicit immediate-probe trigger.
            probeScheduler.requestProbe()
        }.distinctUntilChanged().conflate()
    }
}

internal fun reduceSecondUserPresentation(
    runtime: RuntimeState,
    queue: QueueStatus,
    activeRecords: List<ExecutionRecord>,
    recentRecords: List<ExecutionRecord>,
    approvals: List<PendingToolApprovalRecord>,
    subAgents: List<SubAgentRun>,
    safetyBlocked: Boolean,
    nowMs: Long,
): SecondUserPresentationState {
    val active = activeRecords.filterNot { ExecutionStatus.fromWire(it.status).isTerminal }
    val recent = recentRecords.filter { record ->
        val completedAt = record.finishedAtMs ?: record.updatedAtMs
        completedAt >= nowMs - RECENT_PRESENTATION_MS
    }
    val pendingApprovals = approvals.count { ApprovalStatus.fromWire(it.status) == ApprovalStatus.PENDING }
    val activeSubAgents = subAgents.count {
        it.status == SubAgentStatus.PENDING || it.status == SubAgentStatus.RUNNING
    }
    val background = active.filter(ExecutionRecord::isDetachedBackground)
    val foreground = active - background.toSet()
    val recovering = active.any {
        VerificationState.fromWire(it.verificationState) == VerificationState.RECONCILING
    } || runtime == RuntimeState.Hydrating
    val stale = active.any {
        VerificationState.fromWire(it.verificationState) in setOf(
            VerificationState.STALE,
            VerificationState.UNKNOWN,
        )
    } || recent.any {
        ExecutionStatus.fromWire(it.status) in setOf(ExecutionStatus.orphaned, ExecutionStatus.unknown)
    } || runtime is RuntimeState.HydrationFailed || runtime is RuntimeState.Fatal
    val cancelRequested = active.any {
        ExecutionStatus.fromWire(it.status) == ExecutionStatus.cancel_requested
    } || runtime is RuntimeState.Cancelling
    val terminating = active.any {
        ExecutionStatus.fromWire(it.status) == ExecutionStatus.terminating
    }
    val toolRunning = foreground.any { record ->
        ExecutionStatus.fromWire(record.status) in setOf(
            ExecutionStatus.starting,
            ExecutionStatus.running,
        ) && VerificationState.fromWire(record.verificationState) !in setOf(
            VerificationState.RECONCILING,
            VerificationState.STALE,
            VerificationState.UNKNOWN,
        )
    }
    val modelGenerating = runtime == RuntimeState.Running
    val failedRecently = recent.any {
        ExecutionStatus.fromWire(it.status) in setOf(ExecutionStatus.failed, ExecutionStatus.timed_out)
    }
    val succeededRecently = recent.any {
        ExecutionStatus.fromWire(it.status) == ExecutionStatus.succeeded &&
            VerificationState.fromWire(it.verificationState) !in setOf(
                VerificationState.RECONCILING,
                VerificationState.STALE,
                VerificationState.UNKNOWN,
            )
    }
    val waitingApproval = pendingApprovals > 0 || runtime == RuntimeState.WaitingApproval ||
        active.any { ExecutionStatus.fromWire(it.status) == ExecutionStatus.waiting_approval }
    val status = when {
        safetyBlocked -> SecondUserPresentationStatus.SAFETY_BLOCKED
        waitingApproval -> SecondUserPresentationStatus.WAITING_APPROVAL
        cancelRequested -> SecondUserPresentationStatus.CANCEL_REQUESTED
        terminating -> SecondUserPresentationStatus.TERMINATING
        toolRunning -> SecondUserPresentationStatus.TOOL_RUNNING
        modelGenerating -> SecondUserPresentationStatus.MODEL_GENERATING
        queue.pendingCount > 0 -> SecondUserPresentationStatus.QUEUED
        recovering -> SecondUserPresentationStatus.RECOVERING
        stale -> SecondUserPresentationStatus.STALE
        failedRecently -> SecondUserPresentationStatus.FAILED_RECENTLY
        succeededRecently -> SecondUserPresentationStatus.SUCCEEDED_RECENTLY
        background.isNotEmpty() -> SecondUserPresentationStatus.BACKGROUND_SERVICE_RUNNING
        else -> SecondUserPresentationStatus.IDLE
    }
    val relevant = (active + recent).distinctBy(ExecutionRecord::id)
    val relevantVerification = relevant.map { VerificationState.fromWire(it.verificationState) }
    val verification = when (status) {
        SecondUserPresentationStatus.STALE -> VerificationState.UNKNOWN
        SecondUserPresentationStatus.RECOVERING ->
            relevantVerification.minByOrNull(::verificationTrustRank)
                ?: VerificationState.RECONCILING
        else -> relevantVerification.minByOrNull(::verificationTrustRank) ?: when {
            modelGenerating -> VerificationState.LIVE_CONFIRMED
            else -> VerificationState.DATABASE_CONFIRMED
        }
    }
    val continuity = aggregateContinuity(relevant)
    val summaries = relevant.sortedByDescending(ExecutionRecord::updatedAtMs)
        .take(MAX_SAFE_EXECUTION_SUMMARIES)
        .map(ExecutionRecord::toSafeSummary)
    return SecondUserPresentationState(
        status = status,
        modelGenerating = modelGenerating,
        queueCount = queue.pendingCount,
        activeExecutionCount = active.size,
        backgroundServiceCount = background.size,
        activeSubAgentCount = activeSubAgents,
        pendingApprovalCount = pendingApprovals,
        safetyBlocked = safetyBlocked,
        trusted = status !in setOf(
            SecondUserPresentationStatus.STALE,
            SecondUserPresentationStatus.RECOVERING,
        ) && relevant.none {
            VerificationState.fromWire(it.verificationState) in setOf(
                VerificationState.RECONCILING,
                VerificationState.STALE,
                VerificationState.UNKNOWN,
            )
        },
        verification = verification,
        continuity = continuity,
        cancellable = active.any {
            ExecutionStatus.fromWire(it.status) != ExecutionStatus.waiting_approval
        } || modelGenerating || activeSubAgents > 0,
        executionSummaries = summaries,
        totalExecutionCount = relevant.size,
        updatedAtMs = nowMs,
    )
}

private data class PresentationCore(
    val runtime: RuntimeState,
    val queue: QueueStatus,
    val active: List<ExecutionRecord>,
    val recent: List<ExecutionRecord>,
)

private data class PresentationAuxiliary(
    val approvals: List<PendingToolApprovalRecord>,
    val subAgents: List<SubAgentRun>,
    val safetyBlocked: Boolean,
    val nowMs: Long,
)

private fun presentationClock(nowMs: () -> Long): Flow<Long> = flow {
    while (true) {
        emit(nowMs())
        delay(PRESENTATION_CLOCK_MS)
    }
}

private fun ExecutionRecord.isDetachedBackground(): Boolean =
    ExecutionKind.fromWire(executionKind) == ExecutionKind.MANAGED_PROCESS &&
        CompletionPolicy.fromWire(completionPolicy) in setOf(
            CompletionPolicy.DETACH_BACKGROUND,
            CompletionPolicy.SERVICE_EXPECTED_TO_STAY_ALIVE,
        )

private fun ExecutionRecord.toSafeSummary(): SafeExecutionSummary = SafeExecutionSummary(
    category = safeCategory(),
    runtime = ExecutionRuntime.fromWire(runtime),
    kind = ExecutionKind.fromWire(executionKind),
    status = ExecutionStatus.fromWire(status),
    verification = VerificationState.fromWire(verificationState),
    completionPolicy = CompletionPolicy.fromWire(completionPolicy),
    continuity = continuity(),
)

private fun ExecutionRecord.safeCategory(): SafeExecutionCategory {
    return when (ExecutionRuntime.fromWire(runtime)) {
        ExecutionRuntime.WORKSPACE, ExecutionRuntime.TERMUX -> SafeExecutionCategory.LINUX
        ExecutionRuntime.SSH -> SafeExecutionCategory.REMOTE_COMPUTE
        else -> {
            val roots = capabilityKeys.split(',').map { it.substringBefore('.') }.toSet()
            when {
                roots.any { it in setOf("files", "phone") } -> SafeExecutionCategory.FILES
                "device" in roots -> SafeExecutionCategory.DEVICE
                roots.any { it in setOf("browser", "web") } -> SafeExecutionCategory.NETWORK
                roots.any { it in setOf("workflow", "linux") } -> SafeExecutionCategory.AUTOMATION
                roots.any { it in setOf("conversation", "memory") } -> SafeExecutionCategory.DATA
                roots.any { it in setOf("mcp", "plugin", "tool") } -> SafeExecutionCategory.EXTENSION
                else -> SafeExecutionCategory.OTHER
            }
        }
    }
}

private fun ExecutionRecord.continuity(): RuntimeContinuity = when {
    ExecutionStatus.fromWire(status) == ExecutionStatus.orphaned -> RuntimeContinuity.LOST
    lastReasonCode == "workspace_process_restarted" -> RuntimeContinuity.RESTARTED
    runtimeInstanceMarker != null &&
        VerificationState.fromWire(verificationState) == VerificationState.RUNTIME_CONFIRMED ->
        RuntimeContinuity.SAME_INSTANCE
    else -> RuntimeContinuity.UNKNOWN
}

private fun aggregateContinuity(records: List<ExecutionRecord>): RuntimeContinuity {
    val values = records.map(ExecutionRecord::continuity)
    return when {
        RuntimeContinuity.LOST in values -> RuntimeContinuity.LOST
        RuntimeContinuity.RESTARTED in values -> RuntimeContinuity.RESTARTED
        RuntimeContinuity.UNKNOWN in values || values.isEmpty() -> RuntimeContinuity.UNKNOWN
        else -> RuntimeContinuity.SAME_INSTANCE
    }
}

private fun verificationTrustRank(state: VerificationState): Int = when (state) {
    VerificationState.UNKNOWN -> 0
    VerificationState.STALE -> 1
    VerificationState.RECONCILING -> 2
    VerificationState.DATABASE_CONFIRMED -> 3
    VerificationState.RUNTIME_CONFIRMED -> 4
    VerificationState.LIVE_CONFIRMED -> 5
}

private const val RECENT_PRESENTATION_MS = 8_000L
private const val PRESENTATION_CLOCK_MS = 1_000L
private const val MAX_SAFE_EXECUTION_SUMMARIES = 8
