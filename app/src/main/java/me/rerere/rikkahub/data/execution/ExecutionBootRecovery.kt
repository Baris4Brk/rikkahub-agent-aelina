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
    private val verifier: ManagedExecutionVerifier,
) {
    suspend fun runRecovery(): ExecutionRecoverySummary {
        var verified = 0
        var orphaned = 0
        repository.getInFlight().forEach { record ->
            if (record.runtime.isManagedRuntime() && verifier.isVerifiable(record)) {
                val current = ExecutionStatus.fromWire(record.status)
                repository.transition(
                    id = record.id,
                    target = if (current in setOf(ExecutionStatus.starting, ExecutionStatus.running)) {
                        ExecutionStatus.running
                    } else {
                        ExecutionStatus.unknown
                    },
                    detail = if (current in setOf(ExecutionStatus.cancel_requested, ExecutionStatus.terminating)) {
                        "managed_runtime_alive_after_restart_cancellation_not_replayed"
                    } else {
                        "managed_runtime_live_verified"
                    },
                )
                verified++
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
