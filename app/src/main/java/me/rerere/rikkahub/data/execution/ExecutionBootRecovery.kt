package me.rerere.rikkahub.data.execution

import me.rerere.rikkahub.execution.ManagedExecutionLedger

data class ExecutionRecoverySummary(
    val verifiedManaged: Int,
    val orphaned: Int,
)

/** True only when a managed runtime's existing durable ledger proves the native task exists. */
fun interface ManagedExecutionVerifier {
    suspend fun isVerifiable(record: ExecutionRecord): Boolean
}

/**
 * Recovery intentionally does not replay tool calls. A matching managed ledger row proves only
 * that a process can be inspected/re-attached from the task center; it does not prove an old
 * model request is still safe to repeat.
 */
class ExecutionBootRecovery(
    private val repository: ExecutionRepository,
    private val approvalDao: PendingToolApprovalDao,
    private val reconciler: ExecutionReconciler,
    private val cancellationCoordinator: CancellationCoordinator,
) {
    suspend fun runRecovery(): ExecutionRecoverySummary {
        var verified = 0
        var orphaned = 0
        repository.getInFlight().forEach { record ->
            if (ExecutionStatus.fromWire(record.status) == ExecutionStatus.waiting_approval &&
                approvalDao.getByExecutionId(record.id)?.status == ApprovalStatus.PENDING.name
            ) {
                // Pending approvals are intentionally durable and never expire. Their executable
                // payload is reconciled against the conversation graph before this sweep.
                return@forEach
            }
            if (record.runtime.isManagedRuntime()) {
                var update = reconciler.reconcile(record.id)
                if (shouldResumeCancellation(update)) {
                    cancellationCoordinator.resumeCancellation(record.id)
                    update = reconciler.reconcile(record.id)
                }
                if (update.record?.let { VerificationState.fromWire(it.verificationState) } ==
                    VerificationState.RUNTIME_CONFIRMED
                ) {
                    verified++
                }
                if (update.record?.let { ExecutionStatus.fromWire(it.status) } ==
                    ExecutionStatus.orphaned
                ) {
                    orphaned++
                }
            } else {
                repository.transition(
                    id = record.id,
                    target = ExecutionStatus.orphaned,
                    detail = "process_restart_unverified_no_auto_retry",
                )
                orphaned++
            }
        }
        return ExecutionRecoverySummary(verifiedManaged = verified, orphaned = orphaned)
    }

    private fun String.isManagedRuntime(): Boolean = when (ExecutionRuntime.fromWire(this)) {
        ExecutionRuntime.TERMUX,
        ExecutionRuntime.SSH,
        ExecutionRuntime.WORKSPACE,
        -> true

        else -> false
    }
}

internal fun shouldResumeCancellation(update: ExecutionProbeUpdate): Boolean =
    update.probe is RuntimeProbeResult.Alive && update.record?.let {
        ExecutionStatus.fromWire(it.status) in setOf(
            ExecutionStatus.cancel_requested,
            ExecutionStatus.terminating,
        )
    } == true

/** Revalidates Termux against its authenticated supervisor; other runtimes use their ledger. */
class LiveManagedExecutionVerifier(
    private val ledgerVerifier: LedgerManagedExecutionVerifier,
    private val termuxSupervisor: me.rerere.rikkahub.execution.TermuxManagedSupervisor,
    private val tokenProvider: me.rerere.rikkahub.execution.ExecutionTokenProvider,
) : ManagedExecutionVerifier {
    override suspend fun isVerifiable(record: ExecutionRecord): Boolean {
        if (ExecutionRuntime.fromWire(record.runtime) != ExecutionRuntime.TERMUX) {
            return ledgerVerifier.isVerifiable(record)
        }
        val handle = record.runtimeHandleSummary ?: return false
        val nativeId = me.rerere.rikkahub.execution.nativeManagedExecutionId(handle) ?: return false
        val token = tokenProvider.tokenFor(nativeId)
        return termuxSupervisor.status(nativeId, token).getOrNull()?.running == true
    }
}

/** Bridges the existing PID/process-group ledger into the new execution-record recovery path. */
class LedgerManagedExecutionVerifier(
    private val ledger: ManagedExecutionLedger,
) : ManagedExecutionVerifier {
    override suspend fun isVerifiable(record: ExecutionRecord): Boolean {
        val handle = record.runtimeHandleSummary ?: return false
        val expectedRuntime = ExecutionRuntime.fromWire(record.runtime)
        return ledger.list().any { managed ->
            managed.executionId == handle &&
                managed.runtime.toExecutionRuntime() == expectedRuntime &&
                managed.status.lowercase() !in TERMINAL_MANAGED_STATUSES
        }
    }

    private fun String.toExecutionRuntime(): ExecutionRuntime = when (lowercase()) {
        "termux" -> ExecutionRuntime.TERMUX
        "ssh" -> ExecutionRuntime.SSH
        "workspace" -> ExecutionRuntime.WORKSPACE
        else -> ExecutionRuntime.UNKNOWN
    }

    private companion object {
        val TERMINAL_MANAGED_STATUSES = setOf("succeeded", "failed", "cancelled", "stopped", "exited", "lost")
    }
}
