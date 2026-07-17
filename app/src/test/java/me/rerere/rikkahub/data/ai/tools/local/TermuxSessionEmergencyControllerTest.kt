package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxSessionEmergencyControllerTest {
    @Test
    fun `stop all verifies every agent session disappeared`() = runBlocking {
        val live = linkedSetOf("rk_one", "rk_two")

        val result = stopAgentTermuxSessions(
            listSessions = { live.toList() },
            killSession = { live.remove(it) },
        )

        assertTrue(result.ok)
        assertEquals(2, result.requestedCount)
        assertEquals(2, result.stoppedCount)
        assertTrue(result.remainingSessionIds.isEmpty())
    }

    @Test
    fun `stop all reports sessions that could not be confirmed stopped`() = runBlocking {
        val live = linkedSetOf("rk_one", "rk_stuck")

        val result = stopAgentTermuxSessions(
            listSessions = { live.toList() },
            killSession = { id -> if (id == "rk_one") live.remove(id) else false },
        )

        assertFalse(result.ok)
        assertEquals(listOf("rk_stuck"), result.remainingSessionIds)
        assertEquals("TERMUX_TERMINATION_UNKNOWN", result.code)
    }
}
