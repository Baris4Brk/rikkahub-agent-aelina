package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.service.chat.CommandOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cron worker must wait for the command it submitted. Observing a shared `Job?` flow
 * cannot distinguish "accepted but not started" from "finished", because both are null.
 */
class CronJobWorkerLlmAwaitTest {

    @Test
    fun `accepted command does not complete before its run starts`() = runBlocking {
        val outcome = CompletableDeferred<CommandOutcome>()
        val waiting = async { awaitCommandTerminal(outcome, timeoutMs = 5_000L) }

        delay(50L)
        assertFalse("accepted command must still be pending", waiting.isCompleted)

        outcome.complete(CommandOutcome.Completed)
        assertEquals(CommandOutcome.Completed, waiting.await())
    }

    @Test
    fun `terminal command outcome is returned without waiting for a job flow`() = runBlocking {
        val outcome = CompletableDeferred<CommandOutcome>(CommandOutcome.Completed)
        assertEquals(CommandOutcome.Completed, awaitCommandTerminal(outcome, timeoutMs = 1_000L))
    }

    @Test
    fun `timeout returns null while command remains pending`() = runBlocking {
        val outcome = CompletableDeferred<CommandOutcome>()
        assertEquals(null, awaitCommandTerminal(outcome, timeoutMs = 50L))
        assertTrue(outcome.isActive)
    }
}
