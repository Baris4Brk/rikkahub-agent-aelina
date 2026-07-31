package me.rerere.rikkahub.owner

import me.rerere.rikkahub.execution.ManagedExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerServiceRecoveryPolicyTest {
    @Test
    fun `runtime errors remain unreachable and are never interpreted as process exit`() {
        val probe = ownerTermuxProbeResult(ManagedExecutionResult.Error("TERMUX_BINDER_UNREACHABLE"))

        assertTrue(probe is OwnerTermuxProbeResult.Unreachable)
        assertEquals("TERMUX_BINDER_UNREACHABLE", (probe as OwnerTermuxProbeResult.Unreachable).code)
    }

    @Test
    fun `restart backoff is bounded`() {
        assertEquals(5_000L, ownerServiceRestartBackoffMs(0))
        assertEquals(10_000L, ownerServiceRestartBackoffMs(1))
        assertEquals(300_000L, ownerServiceRestartBackoffMs(100))
    }

    @Test
    fun `encrypted service health target participates in integrity fingerprint`() {
        val base = OwnerLocalServiceSpec(
            runtime = "TERMUX",
            command = "run-service",
            workingDirectory = "home",
            healthUrl = "http://127.0.0.1:8080/health",
            name = "service",
            keepAwake = true,
            restartPolicy = "ON_FAILURE",
        )

        val changed = base.copy(healthUrl = "http://127.0.0.1:8081/health")

        assertTrue(ownerServiceSpecHash(base) != ownerServiceSpecHash(changed))
    }
}
