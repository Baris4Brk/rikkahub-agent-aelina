package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderAttemptObserverContractTest {
    @Test
    fun `attempt observations are one based and terminal vocabulary is exact`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderAttemptEvent.HostDispatched(attemptOrdinal = 0, stream = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderAttemptEvent.Terminal(
                attemptOrdinal = -1,
                outcome = ProviderAttemptTerminalOutcome.FAILED,
            )
        }

        assertEquals(
            listOf(
                "COMPLETED",
                "FAILED",
                "CANCELLED",
                "STEERING_CANCELLED",
                "STALLED_RETRY",
            ),
            ProviderAttemptTerminalOutcome.entries.map { it.name },
        )
    }
}
