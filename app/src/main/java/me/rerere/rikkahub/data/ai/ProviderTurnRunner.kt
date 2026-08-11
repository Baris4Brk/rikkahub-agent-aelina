package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class ProviderTurnRequest(
    val stream: Boolean,
    val streamCall: suspend () -> Flow<MessageChunk>,
    val retryStreamCall: (suspend () -> Flow<MessageChunk>)? = null,
    val singleCall: suspend () -> MessageChunk,
    val onChunk: suspend (MessageChunk) -> Unit,
    val onBeforeRetry: suspend (ProviderStreamStall) -> Unit = {},
    val watchdogConfig: ProviderStreamWatchdogConfig? = null,
)

data class ProviderStreamWatchdogConfig(
    val firstProgressTimeoutMillis: Long = 180_000L,
    val lowSpeedWindowMillis: Long = 90_000L,
    val checkIntervalMillis: Long = 5_000L,
    /** Ten estimated tokens/second over the default 90-second rolling window. */
    val minimumProgressUnitsPerWindow: Long = 900L,
) {
    init {
        require(firstProgressTimeoutMillis > 0L)
        require(lowSpeedWindowMillis > 0L)
        require(checkIntervalMillis > 0L)
        require(checkIntervalMillis <= lowSpeedWindowMillis)
        require(minimumProgressUnitsPerWindow > 0L)
    }
}

enum class ProviderStreamStallReason {
    FIRST_PROGRESS_TIMEOUT,
    SUSTAINED_LOW_THROUGHPUT,
}

data class ProviderStreamStall(
    val reason: ProviderStreamStallReason,
    val observedProgressUnits: Long,
    val observationMillis: Long,
)

class ProviderStreamStalledException(
    val stall: ProviderStreamStall,
) : IllegalStateException(
    "Provider stream remained unhealthy after one fresh-connection retry " +
        "(${stall.reason.name.lowercase()})",
)

sealed interface ProviderTurnOutcome {
    data object Completed : ProviderTurnOutcome
    data object CancelledForSteering : ProviderTurnOutcome
}

interface ProviderTurnRunner {
    suspend fun run(request: ProviderTurnRequest): ProviderTurnOutcome
}

class DefaultProviderTurnRunner(
    private val runControl: GenerationRunControl?,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : ProviderTurnRunner {
    override suspend fun run(request: ProviderTurnRequest): ProviderTurnOutcome {
        if (!request.stream) {
            return runAttempt(request, streamCall = null, watchdogConfig = null).toPublicOutcome()
        }

        var retry = false
        while (true) {
            val streamCall = if (retry) request.retryStreamCall else request.streamCall
            checkNotNull(streamCall) { "A watchdog retry requires retryStreamCall" }
            when (val outcome = runAttempt(request, streamCall, request.watchdogConfig)) {
                ProviderAttemptOutcome.Completed -> return ProviderTurnOutcome.Completed
                ProviderAttemptOutcome.CancelledForSteering ->
                    return ProviderTurnOutcome.CancelledForSteering

                is ProviderAttemptOutcome.Stalled -> {
                    if (!retry && request.retryStreamCall != null) {
                        request.onBeforeRetry(outcome.stall)
                        retry = true
                        continue
                    }
                    throw ProviderStreamStalledException(outcome.stall)
                }
            }
        }
    }

    private suspend fun runAttempt(
        request: ProviderTurnRequest,
        streamCall: (suspend () -> Flow<MessageChunk>)?,
        watchdogConfig: ProviderStreamWatchdogConfig?,
    ): ProviderAttemptOutcome = supervisorScope {
        val steeringCancellationRequested = AtomicBoolean(false)
        val cancellationOrigin = AtomicReference<ProviderCancellationOrigin?>(null)
        val detectedStall = AtomicReference<ProviderStreamStall?>(null)
        val progress = watchdogConfig?.let {
            ProviderStreamProgressTracker(it, nowMillis)
        }
        // Provider work must remain a cancellable child, while callbacks must run in this scoped
        // caller coroutine. GenerationHandler's callback emits from a `flow {}` collector, and
        // invoking it directly inside the Deferred violates Flow's single-coroutine invariant.
        // Keep provider collection independent from UI/database processing. Provider callback
        // flows are already losslessly buffered; a rendezvous channel here would reintroduce
        // backpressure and let a slow onChunk callback look like a slow network stream.
        val chunks = Channel<MessageChunk>(capacity = Channel.UNLIMITED)
        val providerChild = async {
            try {
                if (request.stream) {
                    checkNotNull(streamCall).invoke().collect { chunk ->
                        // Record at the provider boundary, before UI/database work. A slow
                        // collector must never be mistaken for a slow upstream stream.
                        progress?.record(chunk)
                        chunks.send(chunk)
                    }
                } else {
                    chunks.send(request.singleCall())
                }
            } catch (error: Throwable) {
                chunks.close(error)
                throw error
            } finally {
                chunks.close()
            }
        }
        val registration = runControl?.registerProviderCancel {
            steeringCancellationRequested.set(true)
            if (cancellationOrigin.compareAndSet(null, ProviderCancellationOrigin.STEERING)) {
                providerChild.cancel(
                    CancellationException("Provider cancellation requested by steering"),
                )
            }
        }
        val watchdogJob = progress?.let { tracker ->
            launch {
                while (isActive && providerChild.isActive) {
                    delay(checkNotNull(watchdogConfig).checkIntervalMillis)
                    if (!providerChild.isActive) return@launch
                    val stall = tracker.detectStall() ?: continue
                    if (cancellationOrigin.compareAndSet(null, ProviderCancellationOrigin.WATCHDOG)) {
                        detectedStall.set(stall)
                        providerChild.cancel(
                            CancellationException("Provider stream watchdog requested retry"),
                        )
                    }
                    return@launch
                }
            }
        }
        try {
            for (chunk in chunks) {
                request.onChunk(chunk)
            }
            providerChild.await()
            // A successful await means a watchdog cancellation lost a race to natural stream
            // completion. Never retry a provider call that actually completed.
            ProviderAttemptOutcome.Completed
        } catch (cancelled: CancellationException) {
            when (cancellationOrigin.get()) {
                ProviderCancellationOrigin.WATCHDOG -> {
                    if (!currentCoroutineContext().isActive ||
                        runControl?.isRunCancellationRequested() == true
                    ) throw cancelled
                    if (steeringCancellationRequested.get()) {
                        ProviderAttemptOutcome.CancelledForSteering
                    } else {
                        ProviderAttemptOutcome.Stalled(
                            checkNotNull(detectedStall.get()) {
                                "Watchdog cancelled without a stall"
                            },
                        )
                    }
                }

                ProviderCancellationOrigin.STEERING -> {
                    if (!steeringCancellationRequested.get() ||
                        !currentCoroutineContext().isActive ||
                        runControl?.isRunCancellationRequested() == true
                    ) throw cancelled
                    ProviderAttemptOutcome.CancelledForSteering
                }

                null -> throw cancelled
            }
        } finally {
            registration?.close()
            watchdogJob?.cancel()
            chunks.cancel()
            providerChild.cancel()
        }
    }

    private fun ProviderAttemptOutcome.toPublicOutcome(): ProviderTurnOutcome = when (this) {
        ProviderAttemptOutcome.Completed -> ProviderTurnOutcome.Completed
        ProviderAttemptOutcome.CancelledForSteering -> ProviderTurnOutcome.CancelledForSteering
        is ProviderAttemptOutcome.Stalled -> throw ProviderStreamStalledException(stall)
    }
}

private enum class ProviderCancellationOrigin { STEERING, WATCHDOG }

private sealed interface ProviderAttemptOutcome {
    data object Completed : ProviderAttemptOutcome
    data object CancelledForSteering : ProviderAttemptOutcome
    data class Stalled(val stall: ProviderStreamStall) : ProviderAttemptOutcome
}

private data class ProviderProgressSample(val atMillis: Long, val units: Long)

private class ProviderStreamProgressTracker(
    private val config: ProviderStreamWatchdogConfig,
    private val nowMillis: () -> Long,
) {
    private val startedAtMillis = nowMillis()
    private var firstProgressAtMillis: Long? = null
    private val samples = ArrayDeque<ProviderProgressSample>()

    @Synchronized
    fun record(chunk: MessageChunk) {
        val units = chunk.estimatedProgressUnits()
        if (units <= 0L) return
        val now = nowMillis()
        if (firstProgressAtMillis == null) firstProgressAtMillis = now
        samples.addLast(ProviderProgressSample(now, units))
        discardOldSamples(now)
    }

    @Synchronized
    fun detectStall(): ProviderStreamStall? {
        val now = nowMillis()
        val first = firstProgressAtMillis
        if (first == null) {
            val elapsed = now - startedAtMillis
            return if (elapsed >= config.firstProgressTimeoutMillis) {
                ProviderStreamStall(
                    reason = ProviderStreamStallReason.FIRST_PROGRESS_TIMEOUT,
                    observedProgressUnits = 0L,
                    observationMillis = elapsed,
                )
            } else {
                null
            }
        }

        if (now - first < config.lowSpeedWindowMillis) return null
        discardOldSamples(now)
        val units = samples.sumOf(ProviderProgressSample::units)
        return if (units < config.minimumProgressUnitsPerWindow) {
            ProviderStreamStall(
                reason = ProviderStreamStallReason.SUSTAINED_LOW_THROUGHPUT,
                observedProgressUnits = units,
                observationMillis = config.lowSpeedWindowMillis,
            )
        } else {
            null
        }
    }

    private fun discardOldSamples(now: Long) {
        val cutoff = now - config.lowSpeedWindowMillis
        while (samples.firstOrNull()?.atMillis?.let { it <= cutoff } == true) {
            samples.removeFirst()
        }
    }
}

private fun MessageChunk.estimatedProgressUnits(): Long = choices.sumOf { choice ->
    (choice.delta ?: choice.message)?.estimatedProgressUnits() ?: 0L
}

@Suppress("DEPRECATION")
private fun UIMessage.estimatedProgressUnits(): Long = parts.sumOf { part ->
    when (part) {
        is UIMessagePart.Text -> part.text.estimatedTokenUnits()
        is UIMessagePart.Reasoning -> part.reasoning.estimatedTokenUnits()
        is UIMessagePart.Tool ->
            part.toolName.estimatedTokenUnits() + part.input.estimatedTokenUnits()
        is UIMessagePart.ToolCall ->
            part.toolName.estimatedTokenUnits() + part.arguments.estimatedTokenUnits()
        is UIMessagePart.ToolResult -> part.content.toString().estimatedTokenUnits()
        is UIMessagePart.Image,
        is UIMessagePart.Video,
        is UIMessagePart.Audio,
        is UIMessagePart.Document,
        UIMessagePart.Search,
            -> 1L
    }
}

/** Cheap language-neutral approximation: CJK/non-ASCII code points ~= 1 token, ASCII ~= 4. */
private fun String.estimatedTokenUnits(): Long {
    var nonAscii = 0L
    var ascii = 0L
    codePoints().forEach { codePoint ->
        if (!Character.isWhitespace(codePoint)) {
            if (codePoint <= 0x7f) ascii += 1L else nonAscii += 1L
        }
    }
    return nonAscii + (ascii + 3L) / 4L
}
