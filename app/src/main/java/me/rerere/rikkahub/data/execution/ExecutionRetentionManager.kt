package me.rerere.rikkahub.data.execution

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ExecutionRetentionManager(
    private val recordDao: ExecutionRecordDao,
    private val eventDao: ExecutionEventDao,
    private val approvalDao: PendingToolApprovalDao,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val scheduled = AtomicBoolean(false)
    private val cleanupMutex = Mutex()

    fun requestCleanup(
        executionId: String? = null,
        includeGlobalRetention: Boolean = executionId == null,
    ) {
        scope.launch(Dispatchers.IO) {
            executionId?.let { eventDao.trimForExecution(it, MAX_EVENTS_PER_EXECUTION) }
            if (!includeGlobalRetention) return@launch
            if (!scheduled.compareAndSet(false, true)) return@launch
            try {
                cleanupMutex.withLock { cleanupNow() }
            } finally {
                scheduled.set(false)
            }
        }
    }

    internal suspend fun cleanupNow() {
        val cutoff = nowMs() - RETENTION_AGE_MS
        recordDao.deleteTerminalBefore(cutoff)
        recordDao.trimTerminal(MAX_TERMINAL_EXECUTIONS)
        approvalDao.deleteResolvedBefore(cutoff)
        approvalDao.trimResolved(MAX_RESOLVED_APPROVALS)
    }

    companion object {
        const val MAX_TERMINAL_EXECUTIONS = 2_000
        const val MAX_EVENTS_PER_EXECUTION = 64
        const val MAX_RESOLVED_APPROVALS = 1_000
        const val RETENTION_AGE_MS = 30L * 24 * 60 * 60 * 1_000
    }
}
