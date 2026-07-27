package me.rerere.rikkahub.data.ai.execution

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun interface CriticalToolLifecycleSink {
    /** Throws a stable host-side exception when the authoritative write is not durable. */
    suspend fun persist(event: RedactedToolLifecycleEvent)
}

data class ExecutionTrackingHealthState(
    val degraded: Boolean = false,
    val reasonCode: String? = null,
    val degradedSinceMs: Long? = null,
)

class ExecutionTrackingHealth(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(ExecutionTrackingHealthState())
    val state: StateFlow<ExecutionTrackingHealthState> = mutable.asStateFlow()

    fun markDegraded(reasonCode: String) {
        val current = mutable.value
        mutable.value = ExecutionTrackingHealthState(
            degraded = true,
            reasonCode = reasonCode.take(120),
            degradedSinceMs = current.degradedSinceMs ?: nowMs(),
        )
    }

    fun markRecovered() {
        if (mutable.value.degraded) mutable.value = ExecutionTrackingHealthState()
    }
}

enum class ToolTrackingState {
    TRACKED,
    UNTRACKED,
}

internal fun ToolExecutionPolicy.requiresDurableTracking(hasManagedStartable: Boolean): Boolean =
    hasManagedStartable || !allowReadOnlyParallelBatch || effects.any {
        it == ToolEffect.SENSITIVE_READ ||
            it == ToolEffect.NETWORK_WRITE ||
            it == ToolEffect.FILE_WRITE ||
            it == ToolEffect.BROWSER_WRITE ||
            it == ToolEffect.DISPLAY_WRITE ||
            it == ToolEffect.COMMUNICATION ||
            it == ToolEffect.SHELL_EXECUTION ||
            it == ToolEffect.PERSISTENT_STATE ||
            it == ToolEffect.UNKNOWN
    }

class CriticalLifecyclePersistenceException(
    val reasonCode: String,
) : IllegalStateException(reasonCode)
