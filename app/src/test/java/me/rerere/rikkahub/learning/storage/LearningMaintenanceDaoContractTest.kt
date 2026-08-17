package me.rerere.rikkahub.learning.storage

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningMaintenanceDaoContractTest {
    @Test
    fun inboxPruneRequiresConsumptionAgeKnownDecodeAndNoRunnableOrRetryableJob() {
        val source = File(
            "src/main/java/me/rerere/rikkahub/learning/storage/LearningInboxDao.kt",
        ).readText()
        val query = queryBefore(source, "suspend fun deleteExpiredConsumedPage")
        listOf(
            "i.stream_id = :streamId",
            "i.replay_generation = :replayGeneration",
            "i.outbox_seq <= :throughContiguousSeq",
            "i.ingested_at_ms < :ingestedBeforeMs",
            "i.decode_state = 'KNOWN'",
            "i.event_type_code != 'STREAM_INIT'",
            "i.source_state IS NULL",
            // DEAD_LETTER is deliberately retained with its source inbox row: it remains an
            // auditable, explicitly requeueable job rather than an ordinary expired cache row.
            "j.state IN ('PENDING','RETRY','RUNNING','DEAD_LETTER')",
            "LIMIT :limit",
        ).forEach { required -> assertTrue("missing $required", required in query) }
    }

    @Test
    fun donePruneCannotRemoveDeadLettersAndManualReplayIsFullyFenced() {
        val source = File(
            "src/main/java/me/rerere/rikkahub/learning/storage/LearningJobDao.kt",
        ).readText()
        val prune = queryBefore(source, "suspend fun deleteDonePage")
        assertTrue("state = 'DONE'" in prune)
        assertTrue("finished_at_ms < :finishedBeforeMs" in prune)
        assertTrue("LIMIT :limit" in prune)
        assertTrue("DEAD_LETTER" !in prune)

        val replay = queryBefore(source, "suspend fun requeueDeadLetterIfCurrent")
        listOf(
            "state = 'DEAD_LETTER'",
            "lease_generation = :expectedLeaseGeneration",
            "updated_at_ms = :expectedUpdatedAtMs",
            "lease_generation = lease_generation + 1",
            "last_error_code = 'MANUAL_REQUEUE'",
            "finished_at_ms = NULL",
        ).forEach { required -> assertTrue("missing $required", required in replay) }
    }

    private fun queryBefore(source: String, signature: String): String =
        source.substringBefore(signature).substringAfterLast("@Query(")
}
