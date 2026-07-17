package me.rerere.rikkahub.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebServerLifecyclePolicyTest {
    @Test
    fun `start failure leaves a retryable generic error state`() {
        val failed = WebServerState(
            isRunning = false,
            isLoading = true,
            port = 9090,
            serviceName = "RikkaHub",
            localhostOnly = true,
        ).startFailed()

        assertFalse(failed.isRunning)
        assertFalse(failed.isLoading)
        assertEquals(WebServerFailure.StartFailed, failed.failure)

        val retrying = failed.beginStart()

        assertFalse(retrying.isRunning)
        assertTrue(retrying.isLoading)
        assertNull(retrying.failure)
    }

    @Test
    fun `service stops after an initial failure but not for an untouched idle state`() {
        assertFalse(shouldStopWebServerService(WebServerState(), wasRunning = false))
        assertTrue(
            shouldStopWebServerService(
                WebServerState(failure = WebServerFailure.StartFailed),
                wasRunning = false,
            ),
        )
        assertTrue(
            shouldStopWebServerService(
                WebServerState(isRunning = false, isLoading = false),
                wasRunning = true,
            ),
        )
    }
}
