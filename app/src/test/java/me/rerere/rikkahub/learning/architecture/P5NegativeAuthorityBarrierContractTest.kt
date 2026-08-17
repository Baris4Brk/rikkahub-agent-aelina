package me.rerere.rikkahub.learning.architecture

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain-JVM source contract for the P5-007 negative-authority and provider-byte barrier. */
class P5NegativeAuthorityBarrierContractTest {
    @Test
    fun `capture rollout cannot suppress negative authority maintenance`() {
        val root = appRoot()
        val production = Files.readString(
            root.resolve(
                "src/main/java/me/rerere/rikkahub/learning/jobs/" +
                    "P1ProductionRuntimeDependencies.kt",
            ),
        )
        val sourceInvalidationBlock = production
            .substringAfter("sourceInvalidationResolver = SourceInvalidationJobMaterialResolver")
            .substringBefore("derivedJobEnqueuer = downstream")

        assertFalse(
            "SOURCE_INVALIDATED still depends on positive capture consent",
            "if (!gate.captureEnabled())" in sourceInvalidationBlock,
        )
        listOf(
            "rewardAuthorityCaptureGateAllows(",
            "previousSourceRevision = event.previousSourceRevision",
            "rewardAuthority = alwaysReadyP1JobProbe()",
            "sourceInvalidation = alwaysReadyP1JobProbe()",
        ).forEach { needle ->
            assertTrue("Missing negative authority lane: $needle", needle in production)
        }
    }

    @Test
    fun `final provider path is fenced by a bounded content-free invalidation count`() {
        val root = appRoot()
        val facade = Files.readString(
            root.resolve(
                "src/main/java/me/rerere/rikkahub/learning/runtime/LearningRuntimeFacade.kt",
            ),
        )
        val dao = Files.readString(
            root.resolve(
                "src/main/java/me/rerere/rikkahub/learning/storage/LearningJobDao.kt",
            ),
        )
        val generation = Files.readString(
            root.resolve("src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt"),
        )

        assertTrue(
            "Facade must re-check the barrier at retrieval and both final dispatch fences",
            Regex("hasNonDoneAuthorityInvalidationBarrier\\(")
                .findAll(facade).count() >= 4,
        )
        listOf(
            "countNonDoneAuthorityInvalidationBarrier",
            "j.job_type = 'INVALIDATE_SOURCE_V1'",
            "j.job_type = 'APPLY_REWARD_AUTHORITY_V1'",
            "i.previous_source_revision IS NOT NULL",
            "j.state != 'DONE'",
            "LIMIT 1",
        ).forEach { needle ->
            assertTrue("Invalidation barrier SQL is incomplete: $needle", needle in dao)
        }
        assertFalse(
            "Invalidation barrier must not select source or Policy bodies",
            "SELECT * FROM (SELECT j.id FROM learning_jobs j" in dao,
        )
        listOf(
            "preDispatchFence = if (policySelected)",
            "primaryFallback = if (policySelected)",
            "messages = baselinePrepared.finalPreparation.messages",
        ).forEach { needle ->
            assertTrue("Baseline provider fallback is not preserved: $needle", needle in generation)
        }
    }

    private fun appRoot(): Path {
        var cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(6) {
            if (Files.isDirectory(cursor.resolve("app/src/main/java"))) {
                return cursor.resolve("app")
            }
            cursor = cursor.parent ?: return@repeat
        }
        error("Unable to locate app source root")
    }
}
