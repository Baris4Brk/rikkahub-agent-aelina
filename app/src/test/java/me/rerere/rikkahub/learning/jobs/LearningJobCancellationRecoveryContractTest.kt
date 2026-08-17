package me.rerere.rikkahub.learning.jobs

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningJobCancellationRecoveryContractTest {
    @Test
    fun `runner settles a claimed durable job before propagating cancellation`() {
        val source = Files.readString(
            projectRoot().resolve(
                "app/src/main/java/me/rerere/rikkahub/learning/jobs/LearningJobRunner.kt",
            ),
        )

        assertEquals(
            2,
            Regex(
                """catch \(cancelled: CancellationException\) \{\s*""" +
                    """settleCancelledClaim\(""",
            ).findAll(source).count(),
        )
        val settlement = source.substringAfter(
            "private suspend fun settleCancelledClaim",
        ).substringBefore("private fun remainingMs")
        assertTrue(settlement.contains("withContext(NonCancellable)"))
        assertTrue(settlement.contains("coordinator.failAttempt("))
        assertTrue(settlement.contains("LearningJobFailureCode.INTERNAL"))
        assertTrue(settlement.contains("catch (_: Exception)"))
    }

    private fun projectRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { candidate -> Files.isDirectory(candidate.resolve("app/src/main")) }
}
