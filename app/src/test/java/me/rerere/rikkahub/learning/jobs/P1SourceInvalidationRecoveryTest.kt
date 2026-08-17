package me.rerere.rikkahub.learning.jobs

import java.nio.file.Files
import java.nio.file.Path
import me.rerere.rikkahub.learning.storage.LearningSourceValidityState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P1SourceInvalidationRecoveryTest {
    @Test
    fun `terminal validity never regresses during older source replay`() {
        listOf(
            LearningSourceValidityState.INVALIDATED,
            LearningSourceValidityState.SUPERSEDED,
            LearningSourceValidityState.TOMBSTONED,
        ).forEach { terminal ->
            assertTrue(
                shouldPreserveExistingSourceInvalidity(
                    terminal.name,
                    LearningSourceValidityState.UNKNOWN.name,
                ),
            )
            assertTrue(
                shouldPreserveExistingSourceInvalidity(
                    terminal.name,
                    LearningSourceValidityState.VALID.name,
                ),
            )
        }
        assertFalse(
            shouldPreserveExistingSourceInvalidity(
                LearningSourceValidityState.UNKNOWN.name,
                LearningSourceValidityState.SUPERSEDED.name,
            ),
        )
        assertFalse(
            shouldPreserveExistingSourceInvalidity(
                LearningSourceValidityState.SUPERSEDED.name,
                LearningSourceValidityState.TOMBSTONED.name,
            ),
        )
    }

    @Test
    fun `claim order and replay repair are bound to durable outbox authority`() {
        val dao = Files.readString(
            projectRoot().resolve(
                "app/src/main/java/me/rerere/rikkahub/learning/storage/LearningJobDao.kt",
            ),
        )
        val claim = dao.substringAfter("suspend fun findActiveClockRollbackCandidate")
            .substringBefore("suspend fun claim(")
        val outboxOrder = claim.indexOf("SELECT i.outbox_seq")
        val fallbackOrder = claim.indexOf("created_at_ms ASC")
        assertTrue(outboxOrder >= 0)
        assertTrue(fallbackOrder > outboxOrder)
        assertTrue(dao.contains("requeueExhaustedMandatorySourceInvalidations"))
        assertTrue(dao.contains("job_type = 'INVALIDATE_SOURCE_V1'"))
        assertTrue(dao.contains("last_error_code = 'ATTEMPTS_EXHAUSTED'"))
    }

    @Test
    fun `source validity CAS fills the adjacent revision provenance`() {
        val dao = Files.readString(
            projectRoot().resolve(
                "app/src/main/java/me/rerere/rikkahub/learning/storage/LearningEpisodeDao.kt",
            ),
        )
        val update = dao.substringAfter("UPDATE learning_source_validity SET")
            .substringBefore("suspend fun updateSourceValidityIfCurrent")
        assertTrue(update.contains("previous_source_revision = :previousSourceRevision"))
        assertTrue(dao.contains("state IN ('VALID', 'UNKNOWN')"))
    }

    private fun projectRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { candidate -> Files.isDirectory(candidate.resolve("app/src/main")) }
}
