package me.rerere.rikkahub.memory.dreaming.store

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamDailyUsageDispatchAccountingContractTest {
    @Test
    fun `daily run accounting requires durable provider dispatch evidence`() {
        val source = Files.readString(
            projectRoot().resolve(
                "app/src/main/java/me/rerere/rikkahub/data/db/dao/DreamDao.kt",
            ),
        )
        val query = source.substringAfter("SELECT COUNT(*) AS startedRunCount")
            .substringBefore("suspend fun readGlobalDreamDailyUsage")

        assertTrue(query.contains("prompt_contract_version IS NOT NULL"))
        assertTrue(query.contains("validator_version IS NOT NULL"))
        assertTrue(query.contains("input_memory_count IS NOT NULL"))
        assertTrue(query.contains("input_manifest_hash IS NOT NULL"))
        assertTrue(query.contains("MODEL_PROVIDER_UNAVAILABLE"))
        assertTrue(query.contains("MODEL_OUTPUT_LIMIT"))
        assertTrue(query.contains("MODEL_SAFETY_REJECTION"))
        assertTrue(query.contains("MODEL_INVALID_CONFIGURATION"))
    }

    private fun projectRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { candidate -> Files.isDirectory(candidate.resolve("app/src/main")) }
}
