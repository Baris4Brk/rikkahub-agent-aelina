package me.rerere.rikkahub.data.ai.tools

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import me.rerere.rikkahub.data.execution.RequestedTerminalOutcome

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
    private val onCancellationRequested: suspend (RequestedTerminalOutcome) -> Unit = {},
    private val onCancellationProbed: suspend (RequestedTerminalOutcome, Boolean) -> Unit = { _, _ -> },
) : ToolExecutionHandle {
    private val cancelRequested = AtomicBoolean(false)
    private val requestedOutcome = AtomicReference(RequestedTerminalOutcome.NONE)

    override suspend fun awaitResult(): ToolResult = result.await()

    override fun requestCancel(reason: ToolCancelReason): CancelRequestResult {
        if (!cancelRequested.compareAndSet(false, true)) return CancelRequestResult.AlreadyRequested
        requestedOutcome.set(
            if (reason == ToolCancelReason.TIMEOUT) {
                RequestedTerminalOutcome.TIMED_OUT
            } else {
                RequestedTerminalOutcome.CANCELLED
            },
        )
        return CancelRequestResult.Requested
    }

    override suspend fun awaitTermination(gracePeriod: Duration): ToolTerminationState {
        if (!cancelRequested.get()) {
            return if (result.isCompleted) ToolTerminationState.StoppedConfirmed
            else ToolTerminationState.Unknown
        }
        val outcome = requestedOutcome.get()
        runCatching { onCancellationRequested(outcome) }
        try {
            bridge.cancel(runId, force = false)
        } catch (_: Throwable) {
            // A status poll can still confirm a process that exited while the cancellation
            // transport was failing, so continue through the normal confirmation path.
        }
        if (waitForStopped(gracePeriod)) {
            runCatching { onCancellationProbed(outcome, true) }
            return ToolTerminationState.StoppedConfirmed
        }

        try {
            bridge.cancel(runId, force = true)
        } catch (_: Throwable) {
            // Keep the outcome unknown unless the next bounded status polling can prove it.
        }
        val stopped = waitForStopped(gracePeriod)
        runCatching { onCancellationProbed(outcome, stopped) }
        return if (stopped) {
            ToolTerminationState.StoppedConfirmed
        } else {
            ToolTerminationState.Unknown
        }
    }

    /**
     * A Termux cancel request only means the supervisor accepted the signal. Poll the identity
     * through the whole grace period before escalating, otherwise a naturally exiting process
     * is needlessly killed and a delayed exit is reported as unconfirmed.
     */
    private suspend fun waitForStopped(gracePeriod: Duration): Boolean =
        withTimeoutOrNull(gracePeriod) {
            while (true) {
                if (verifyStopped()) return@withTimeoutOrNull true
                delay(TERMINATION_POLL_INTERVAL)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } ?: false

    private suspend fun verifyStopped(): Boolean {
        val status = bridge.status(runId)
        if (!secureEquals(status.runId, runId)) return false
        if (status.pid != expectedPid || status.pgid != expectedPgid) return false
        if (status.startTimeMillis != expectedStartTimeMillis) return false
        return !status.running && status.cancellationConfirmed
    }

    private fun secureEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(), right.toByteArray())

    private companion object {
        val TERMINATION_POLL_INTERVAL = 50.milliseconds
    }
}
