package me.rerere.rikkahub.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.tools.local.DEFAULT_GNSS_OBSERVATION_WINDOW_MS
import me.rerere.rikkahub.data.ai.tools.local.GnssObservationRequest
import me.rerere.rikkahub.data.ai.tools.local.GnssObservationResult
import me.rerere.rikkahub.data.ai.tools.local.GnssObservationSource

internal sealed interface GnssDiagnosticUiState {
    data object Idle : GnssDiagnosticUiState
    data class Running(val remainingMs: Long) : GnssDiagnosticUiState
    data class Completed(val result: GnssObservationResult) : GnssDiagnosticUiState
    data object Cancelled : GnssDiagnosticUiState
}

internal class GnssDiagnosticsController(
    private val source: GnssObservationSource,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<GnssDiagnosticUiState>(GnssDiagnosticUiState.Idle)
    val state: StateFlow<GnssDiagnosticUiState> = mutableState.asStateFlow()

    private var observationJob: Job? = null

    fun start(observationWindowMs: Long = DEFAULT_GNSS_OBSERVATION_WINDOW_MS) {
        val previous = observationJob
        lateinit var next: Job
        next = scope.launch(start = CoroutineStart.LAZY) {
            previous?.cancelAndJoin()
            mutableState.value = GnssDiagnosticUiState.Running(observationWindowMs)
            val ticker = launch {
                var remaining = observationWindowMs
                while (isActive && remaining > 0L) {
                    delay(1_000L)
                    remaining = (remaining - 1_000L).coerceAtLeast(0L)
                    if (isActive) {
                        mutableState.value = GnssDiagnosticUiState.Running(remaining)
                    }
                }
            }
            try {
                val result = source.observe(GnssObservationRequest(observationWindowMs))
                ticker.cancel()
                mutableState.value = GnssDiagnosticUiState.Completed(result)
            } catch (cancelled: CancellationException) {
                ticker.cancel()
                if (observationJob === next && mutableState.value is GnssDiagnosticUiState.Running) {
                    mutableState.value = GnssDiagnosticUiState.Cancelled
                }
                throw cancelled
            }
        }
        observationJob = next
        next.start()
    }

    fun cancel() {
        if (observationJob?.isActive == true) {
            mutableState.value = GnssDiagnosticUiState.Cancelled
            observationJob?.cancel()
        }
    }

    fun close() {
        observationJob?.cancel()
        observationJob = null
    }
}
