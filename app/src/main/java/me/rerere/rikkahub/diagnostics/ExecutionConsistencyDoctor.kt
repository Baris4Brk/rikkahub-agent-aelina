package me.rerere.rikkahub.diagnostics

import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.execution.ExecutionTrackingHealth
import me.rerere.rikkahub.data.execution.ApprovalRecoverySummary
import me.rerere.rikkahub.data.execution.CompletionPolicy
import me.rerere.rikkahub.data.execution.ExecutionConsistencyMetrics
import me.rerere.rikkahub.data.execution.ExecutionEventDao
import me.rerere.rikkahub.data.execution.ExecutionEventRecord
import me.rerere.rikkahub.data.execution.ExecutionKind
import me.rerere.rikkahub.data.execution.ExecutionProbeUpdate
import me.rerere.rikkahub.data.execution.ExecutionRecord
import me.rerere.rikkahub.data.execution.ExecutionReconciler
import me.rerere.rikkahub.data.execution.ExecutionRepository
import me.rerere.rikkahub.data.execution.ExecutionRetentionManager
import me.rerere.rikkahub.data.execution.ExecutionRuntime
import me.rerere.rikkahub.data.execution.ExecutionStatus
import me.rerere.rikkahub.data.execution.PendingToolApprovalDao
import me.rerere.rikkahub.data.execution.PendingToolApprovalRecord
import me.rerere.rikkahub.data.execution.SecondUserApprovalRecovery
import me.rerere.rikkahub.data.execution.VerificationState
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.workspace.WorkspaceProcessManager
import me.rerere.workspace.WorkspaceProcessManagerState
import kotlin.uuid.Uuid

data class ExecutionConsistencyDiagnostic(
    val knownExecutionCount: Int,
    val activeExecutionCount: Int,
    val terminalReturnedAsInFlightCount: Int,
    val approvalProjectionMismatchCount: Int,
    val missingRuntimeHandleCount: Int,
    val workspaceManagerState: WorkspaceProcessManagerState,
    val workspaceManagerNotReadyCount: Int,
    val staleProbeCount: Int,
    val casConflictCount: Long,
    val staleProbeDiscardCount: Long,
    val trackingDegraded: Boolean,
    val trackingReasonCode: String?,
    val activeChildUnderTerminalParentCount: Int,
    val allowedDetachedChildCount: Int,
    val redactionViolationCount: Int,
) {
    val healthy: Boolean
        get() = terminalReturnedAsInFlightCount == 0 &&
            approvalProjectionMismatchCount == 0 &&
            missingRuntimeHandleCount == 0 &&
            workspaceManagerNotReadyCount == 0 &&
            staleProbeCount == 0 &&
            !trackingDegraded &&
            activeChildUnderTerminalParentCount == 0 &&
            redactionViolationCount == 0
}

/** Adds execution-ledger checks and narrowly-scoped repairs to the existing Doctor framework. */
class ExecutionConsistencyDoctor(
    private val repository: ExecutionRepository,
    private val eventDao: ExecutionEventDao,
    private val approvalDao: PendingToolApprovalDao,
    private val conversationRepository: ConversationRepository,
    private val workspaceManager: WorkspaceProcessManager,
    private val reconciler: ExecutionReconciler,
    private val approvalRecovery: SecondUserApprovalRecovery,
    private val retentionManager: ExecutionRetentionManager,
    private val trackingHealth: ExecutionTrackingHealth,
    private val metrics: ExecutionConsistencyMetrics,
) {
    suspend fun inspect(): ExecutionConsistencyDiagnostic {
        val inFlight = repository.getInFlight()
        val recent = repository.getRecent(DOCTOR_RECORD_LIMIT)
        val pendingApprovals = approvalDao.getAllPending()
        val recordsById = recent.associateBy(ExecutionRecord::id).toMutableMap()
        inFlight.mapNotNull(ExecutionRecord::parentExecutionId).distinct().forEach { parentId ->
            if (parentId !in recordsById) repository.get(parentId)?.let { recordsById[parentId] = it }
        }
        val terminalReturnedAsInFlight = inFlight.count {
            ExecutionStatus.fromWire(it.status).isTerminal
        }
        val missingHandles = inFlight.count { record ->
            ExecutionKind.fromWire(record.executionKind) == ExecutionKind.MANAGED_PROCESS &&
                (record.runtimeHandleSummary.isNullOrBlank() || record.runtimeHandleSummary != record.id)
        }
        val workspaceActive = inFlight.count {
            ExecutionKind.fromWire(it.executionKind) == ExecutionKind.MANAGED_PROCESS &&
                ExecutionRuntime.fromWire(it.runtime) == ExecutionRuntime.WORKSPACE
        }
        val managerState = workspaceManager.initializationState.value
        val staleProbeCount = inFlight.count {
            VerificationState.fromWire(it.verificationState) == VerificationState.STALE
        }
        var invalidChildren = 0
        var allowedDetached = 0
        inFlight.forEach { child ->
            val parent = child.parentExecutionId?.let(recordsById::get) ?: return@forEach
            if (!ExecutionStatus.fromWire(parent.status).isTerminal) return@forEach
            if (CompletionPolicy.fromWire(child.completionPolicy) in setOf(
                    CompletionPolicy.DETACH_BACKGROUND,
                    CompletionPolicy.SERVICE_EXPECTED_TO_STAY_ALIVE,
                )
            ) {
                allowedDetached++
            } else {
                invalidChildren++
            }
        }
        val approvalMismatches = pendingApprovals.count { projection ->
            approvalProjectionMismatch(projection)
        }
        val events = recent.take(DOCTOR_EVENT_EXECUTION_LIMIT).flatMap { record ->
            eventDao.getEvents(record.id, DOCTOR_EVENT_LIMIT_PER_EXECUTION)
        }
        val redactionViolations = recent.count(::recordHasRedactionViolation) +
            events.count(::eventHasRedactionViolation) +
            pendingApprovals.count(::approvalHasRedactionViolation)
        val metricSnapshot = metrics.snapshot()
        val tracking = trackingHealth.state.value
        return ExecutionConsistencyDiagnostic(
            knownExecutionCount = recent.size,
            activeExecutionCount = inFlight.size,
            terminalReturnedAsInFlightCount = terminalReturnedAsInFlight,
            approvalProjectionMismatchCount = approvalMismatches,
            missingRuntimeHandleCount = missingHandles,
            workspaceManagerState = managerState,
            workspaceManagerNotReadyCount = workspaceActive.takeIf {
                managerState != WorkspaceProcessManagerState.READY
            } ?: 0,
            staleProbeCount = staleProbeCount,
            casConflictCount = metricSnapshot.casConflicts,
            staleProbeDiscardCount = metricSnapshot.staleProbeDiscards,
            trackingDegraded = tracking.degraded,
            trackingReasonCode = tracking.reasonCode,
            activeChildUnderTerminalParentCount = invalidChildren,
            allowedDetachedChildCount = allowedDetached,
            redactionViolationCount = redactionViolations,
        )
    }

    suspend fun reprobe(): List<ExecutionProbeUpdate> = reconciler.reconcileAll()

    suspend fun rebuildApprovalProjection(): ApprovalRecoverySummary = approvalRecovery.runRecovery()

    suspend fun runRetentionCleanup() = retentionManager.cleanupForDoctor()

    private suspend fun approvalProjectionMismatch(
        projection: PendingToolApprovalRecord,
    ): Boolean {
        val execution = repository.get(projection.executionId)
        if (execution == null ||
            ExecutionStatus.fromWire(execution.status) != ExecutionStatus.waiting_approval
        ) return true
        val conversationId = runCatching { Uuid.parse(projection.conversationId) }.getOrNull()
            ?: return true
        val conversation = conversationRepository.getConversationById(conversationId) ?: return true
        return conversation.messageNodes
            .flatMap { it.messages }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .none { it.toolCallId == projection.toolCallId && it.isPending }
    }
}

internal fun recordHasRedactionViolation(record: ExecutionRecord): Boolean = listOfNotNull(
    record.capabilityKeys,
    record.resourceSummary,
    record.terminalDetail,
    record.lastReasonCode,
).any(::containsSensitiveExecutionDetail)

internal fun eventHasRedactionViolation(event: ExecutionEventRecord): Boolean =
    event.reasonCode?.let(::containsSensitiveExecutionDetail) == true

internal fun approvalHasRedactionViolation(record: PendingToolApprovalRecord): Boolean = listOfNotNull(
    record.capabilityKey,
    record.resourceCategory,
    record.resolutionReason,
).any(::containsSensitiveExecutionDetail)

internal fun containsSensitiveExecutionDetail(value: String): Boolean =
    SENSITIVE_EXECUTION_DETAIL_PATTERNS.any { it.containsMatchIn(value) }

private val SENSITIVE_EXECUTION_DETAIL_PATTERNS = listOf(
    Regex("""(?:content|file)://""", RegexOption.IGNORE_CASE),
    Regex("""https?://[^\s?#]+\?[^\s]+""", RegexOption.IGNORE_CASE),
    Regex("""ssh://[^\s]+""", RegexOption.IGNORE_CASE),
    Regex("""(?:^|[\s=])[a-z]:[\\/]""", RegexOption.IGNORE_CASE),
    Regex(
        """/(?:data|storage|sdcard|home|system|vendor|proc|dev|etc|tmp|workspace)(?:/|$)""",
        RegexOption.IGNORE_CASE,
    ),
    Regex(
        """\b(?:token|password|secret|api[_ -]?key|stdin|stdout|stderr|command|output)\s*[:=]""",
        RegexOption.IGNORE_CASE,
    ),
    Regex("""\beyJ[a-z0-9_-]{8,}\.[a-z0-9_-]{8,}\.[a-z0-9_-]{8,}\b""", RegexOption.IGNORE_CASE),
    Regex("""\b[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,}\b""", RegexOption.IGNORE_CASE),
    Regex("""(?<!\d)(?:\+?\d[\d ()\-]{7,}\d)(?!\d)"""),
    Regex("""\bAKIA[0-9A-Z]{16}\b"""),
    Regex("""\bAIza[0-9A-Za-z_-]{35}\b"""),
    Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}\b"""),
    Regex("""\bxox[baprs]-[A-Za-z0-9\-]{10,}\b"""),
    Regex("""-----BEGIN(?:[ A-Z]+)PRIVATE KEY-----""", RegexOption.IGNORE_CASE),
)

private const val DOCTOR_RECORD_LIMIT = 2_500
private const val DOCTOR_EVENT_EXECUTION_LIMIT = 250
private const val DOCTOR_EVENT_LIMIT_PER_EXECUTION = 64
