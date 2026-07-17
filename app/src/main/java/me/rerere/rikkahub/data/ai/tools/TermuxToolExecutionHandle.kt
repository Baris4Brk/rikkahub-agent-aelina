package me.rerere.rikkahub.data.ai.tools

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Narrow bridge seam for a persistent, authenticated Termux process supervisor. The bridge
 * implementation must bind to localhost/Unix-socket, authenticate every call, and validate
 * runId + pid + pgid + startTime before signalling a process.
 */
interface TermuxBridgeClient {
    suspend fun cancel(runId: String, force: Boolean): Boolean
    suspend fun status(runId: String): TermuxProcessStatus
}

data class TermuxProcessStatus(
    val runId: String,
    val pid: Long,
    val pgid: Long,
    val startTimeMillis: Long,
    val running: Boolean,
    val cancellationConfirmed: Boolean,
)

class TermuxToolExecutionHandle(
    override val executionId: String,
    private val result: Deferred<ToolResult>,
    private val bridge: TermuxBridgeClient,
    private val runId: String,
    private val expectedPid: Long,
    private val expectedPgid: Long,
    private val expectedStartTimeMillis: Long,
) : ToolExecutionHandle {
    private val cancelRequested = AtomicBoolean(false)

    override suspend fun awaitResult(): ToolResult = result.await()

    override fun requestCancel(reason: ToolCancelReason): CancelRequestResult {
        if (!cancelRequested.compareAndSet(false, true)) return CancelRequestResult.AlreadyRequested
        return CancelRequestResult.Requested
    }

    override suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState {
        if (!cancelRequested.get()) {
            return if (result.isCompleted) ToolTerminationState.StoppedConfirmed
            else ToolTerminationState.Unknown
        }
        val graceful = withTimeoutOrNull(gracePeriod) {
            bridge.cancel(runId, force = false)
            verifyStopped()
        } ?: false
        if (graceful) return ToolTerminationState.StoppedConfirmed

        val forced = try {
            bridge.cancel(runId, force = true)
            withTimeoutOrNull(gracePeriod) { verifyStopped() } ?: false
        } catch (_: Throwable) {
            false
        }
        return if (forced) ToolTerminationState.StoppedConfirmed else ToolTerminationState.Unknown
    }

    private suspend fun verifyStopped(): Boolean {
        val status = bridge.status(runId)
        if (!secureEquals(status.runId, runId)) return false
        if (status.pid != expectedPid || status.pgid != expectedPgid) return false
        if (status.startTimeMillis != expectedStartTimeMillis) return false
        return !status.running && status.cancellationConfirmed
    }

    private fun secureEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(), right.toByteArray())
}
