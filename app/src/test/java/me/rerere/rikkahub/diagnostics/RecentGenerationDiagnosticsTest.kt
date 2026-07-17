package me.rerere.rikkahub.diagnostics

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import me.rerere.ai.ui.FinishCategory
import me.rerere.ai.ui.GenerationOutcome
import me.rerere.ai.ui.GenerationTerminal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecentGenerationDiagnosticsTest {
    @Before
    fun resetBeforeTest() {
        RecentGenerationDiagnostics.resetForTest()
    }

    @After
    fun resetAfterTest() {
        RecentGenerationDiagnostics.resetForTest()
    }

    @Test
    fun `late updates from an older generation cannot mutate the latest generation`() {
        val older = RecentGenerationDiagnostics.begin("command-older")
        val latest = RecentGenerationDiagnostics.begin("command-latest")
        older.record(modelId = "older-model", answerChars = 1)
        latest.record(modelId = "latest-model", answerChars = 2)

        older.markOutcome(GenerationOutcome.Completed)
        older.markRecovery(attempt = 8, status = "FAILED")

        val pageSnapshot = RecentGenerationDiagnostics.snapshot()!!
        assertEquals("latest-model", pageSnapshot.modelId)
        assertEquals(2, pageSnapshot.answerChars)
        assertNull(pageSnapshot.completionOutcome)
        assertNull(pageSnapshot.recoveryAttempt)

        val olderSnapshot = older.snapshotForTest()!!
        assertEquals("Completed", olderSnapshot.completionOutcome)
        assertEquals(8, olderSnapshot.recoveryAttempt)
        assertEquals("FAILED", olderSnapshot.recoveryStatus)
    }

    @Test
    fun `concurrent handles keep outcome and recovery updates isolated`() {
        val first = RecentGenerationDiagnostics.begin("command-first")
        val second = RecentGenerationDiagnostics.begin("command-second")
        first.record(modelId = "first-model", answerChars = 11)
        second.record(modelId = "second-model", answerChars = 22)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        val firstFuture = executor.submit {
            start.await()
            repeat(1_000) {
                first.markOutcome(GenerationOutcome.Completed)
                first.markRecovery(attempt = 1, status = "FIRST")
            }
        }
        val secondFuture = executor.submit {
            start.await()
            repeat(1_000) {
                second.markOutcome(GenerationOutcome.NeedsFinalAnswer(terminal()))
                second.markRecovery(attempt = 10, status = "SECOND")
            }
        }

        start.countDown()
        firstFuture.get(10, TimeUnit.SECONDS)
        secondFuture.get(10, TimeUnit.SECONDS)
        executor.shutdownNow()

        val firstSnapshot = first.snapshotForTest()!!
        val secondSnapshot = second.snapshotForTest()!!
        assertEquals("Completed", firstSnapshot.completionOutcome)
        assertEquals(1, firstSnapshot.recoveryAttempt)
        assertEquals("FIRST", firstSnapshot.recoveryStatus)
        assertEquals("NeedsFinalAnswer", secondSnapshot.completionOutcome)
        assertEquals(10, secondSnapshot.recoveryAttempt)
        assertEquals("SECOND", secondSnapshot.recoveryStatus)
        assertEquals(secondSnapshot, RecentGenerationDiagnostics.snapshot())
        assertTrue(executor.isShutdown)
    }

    private fun GenerationDiagnosticHandle.record(
        modelId: String,
        answerChars: Int,
    ) {
        record(
            terminal = terminal(answerChars),
            modelId = modelId,
            providerType = "test-provider",
            requestMode = "normal:stream",
            contextOriginalTokens = 100,
            contextPlannedTokens = 80,
            contextWindowTokens = 1_000,
            contextCompressed = true,
            historicalReasoningRemoved = 3,
        )
    }

    private fun terminal(answerChars: Int = 0) = GenerationTerminal(
        terminalSeen = true,
        category = FinishCategory.STOP,
        reasoningChars = 0,
        answerChars = answerChars,
        toolCallCount = 0,
    )
}
