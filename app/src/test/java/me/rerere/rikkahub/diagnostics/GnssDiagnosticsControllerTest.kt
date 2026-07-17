package me.rerere.rikkahub.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.tools.local.GnssObservationSource
import me.rerere.rikkahub.data.ai.tools.local.GnssObservationResult
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssDiagnosticsControllerTest {
    @Test
    fun `restarting diagnostic waits for the previous observation to cancel`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        var active = 0
        var maxActive = 0
        val secondStarted = CompletableDeferred<Unit>()
        var invocation = 0
        val source = GnssObservationSource {
            invocation++
            active++
            maxActive = maxOf(maxActive, active)
            if (invocation == 2) secondStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                active--
            }
        }
        val controller = GnssDiagnosticsController(source, scope)

        controller.start()
        controller.start()
        secondStarted.await()

        assertTrue(maxActive == 1)
        assertTrue(controller.state.value is GnssDiagnosticUiState.Running)
        controller.cancel()
    }

    @Test
    fun `offline diagnostic exposes a completed observation result`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val expected = GnssObservationResult.Failure(
            code = "GNSS_STATUS_TIMEOUT",
            message = "No callback arrived.",
            recovery = "Move outdoors and try again.",
        )
        val controller = GnssDiagnosticsController(
            source = GnssObservationSource { expected },
            scope = scope,
        )

        controller.start()

        val completed = controller.state.value as GnssDiagnosticUiState.Completed
        assertTrue(completed.result === expected)
    }

    @Test
    fun `cancelling offline diagnostic propagates cancellation to observation`() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        var sourceCancelled = false
        val source = GnssObservationSource {
            try {
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                sourceCancelled = true
                throw cancelled
            }
        }
        val controller = GnssDiagnosticsController(source, scope)

        controller.start()
        controller.cancel()

        assertTrue(controller.state.value is GnssDiagnosticUiState.Cancelled)
        assertTrue(sourceCancelled)
    }
}
