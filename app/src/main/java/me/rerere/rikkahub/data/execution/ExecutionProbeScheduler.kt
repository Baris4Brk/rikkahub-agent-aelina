package me.rerere.rikkahub.data.execution

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.workspace.WorkspaceProcessManager

/** Visibility-gated adaptive polling. It creates neither a Worker nor a dedicated service. */
class ExecutionProbeScheduler(
    context: Context,
    private val scope: CoroutineScope,
    private val repository: ExecutionRepository,
    private val reconciler: ExecutionReconciler,
    private val workspaceManager: WorkspaceProcessManager,
) : DefaultLifecycleObserver {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val visible = MutableStateFlow(false)
    private val started = AtomicBoolean(false)
    private val unreachableAttempts = mutableMapOf<String, Int>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            var pollingJob: Job? = null
            val activeShape = repository.observeRecent(2_500).map { records ->
                records.filter { record ->
                    !ExecutionStatus.fromWire(record.status).isTerminal &&
                        ExecutionKind.fromWire(record.executionKind) == ExecutionKind.MANAGED_PROCESS
                }.map { record ->
                    ActiveShape(record.id, record.status, record.completionPolicy)
                }.sortedBy(ActiveShape::id)
            }.distinctUntilChanged()
            combine(visible, workspaceManager.summary, activeShape) { isVisible, workspace, active ->
                PollGate(
                    enabled = isVisible || workspace.desiredRunningCount > 0,
                    active = active,
                )
            }.collect { gate ->
                if (gate.active.isNotEmpty()) {
                    // Shape/runtime-summary changes are immediate evidence triggers; polling is
                    // only the quiet-period fallback.
                    scope.launch { reconcileOnce() }
                }
                if (!gate.enabled || gate.active.isEmpty()) {
                    pollingJob?.cancel()
                    pollingJob = null
                } else if (pollingJob?.isActive != true) {
                    pollingJob = scope.launch { pollWhileEligible() }
                }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        visible.value = true
        requestProbe()
    }

    override fun onStop(owner: LifecycleOwner) {
        visible.value = false
    }

    fun requestProbe() {
        if (!started.get()) return
        scope.launch { reconcileOnce() }
    }

    private suspend fun pollWhileEligible() {
        while (visible.value || workspaceManager.summary.value.desiredRunningCount > 0) {
            val records = repository.getInFlight().filter {
                ExecutionKind.fromWire(it.executionKind) == ExecutionKind.MANAGED_PROCESS
            }
            if (records.isEmpty()) return
            reconcileOnce()
            val base = records.minOf(::baseIntervalMs)
            val adjusted = if (powerManager?.isInteractive == false) base * 4 else base
            delay(adjusted.coerceAtMost(MAX_INTERVAL_MS))
        }
    }

    private suspend fun reconcileOnce() {
        reconciler.reconcileAll().forEach { update ->
            if (update.probe is RuntimeProbeResult.Unreachable) {
                unreachableAttempts[update.executionId] =
                    (unreachableAttempts[update.executionId] ?: 0) + 1
            } else {
                unreachableAttempts.remove(update.executionId)
            }
        }
    }

    private fun baseIntervalMs(record: ExecutionRecord): Long {
        if (VerificationState.fromWire(record.verificationState) == VerificationState.STALE) {
            return UNREACHABLE_BACKOFF_MS[
                (unreachableAttempts[record.id] ?: 0).coerceIn(0, UNREACHABLE_BACKOFF_MS.lastIndex)
            ]
        }
        return when {
            ExecutionStatus.fromWire(record.status) in setOf(
                ExecutionStatus.cancel_requested,
                ExecutionStatus.terminating,
            ) -> 2_000L
            CompletionPolicy.fromWire(record.completionPolicy) == CompletionPolicy.WAIT_FOR_CHILDREN ->
                2_000L
            CompletionPolicy.fromWire(record.completionPolicy) == CompletionPolicy.DETACH_BACKGROUND ->
                15_000L
            else -> 60_000L
        }
    }

    private data class ActiveShape(
        val id: String,
        val status: String,
        val completionPolicy: String,
    )

    private data class PollGate(
        val enabled: Boolean,
        val active: List<ActiveShape>,
    )

    private companion object {
        const val MAX_INTERVAL_MS = 5 * 60_000L
        val UNREACHABLE_BACKOFF_MS = longArrayOf(5_000L, 15_000L, 60_000L, 300_000L)
    }
}
