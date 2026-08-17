package me.rerere.rikkahub.memory.dreaming.runtime

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamStartupRecoveryContractTest {
    @Test
    fun `production preferences wait for restored settings before arming recovery`() {
        val source = Files.readString(
            projectRoot().resolve(
                "app/src/main/java/me/rerere/rikkahub/memory/dreaming/runtime/" +
                    "DreamingCostPolicySource.kt",
            ),
        )
        val production = source.substringAfter("class SettingsDreamingPreferencesSource")
            .substringBefore("object DisabledDreamingPreferencesSource")
        assertTrue(production.contains("settingsFlow.first"))
        assertTrue(production.contains("!settings.init"))
        assertFalse(production.contains("settingsFlow.value"))
    }

    @Test
    fun `unexpected worker failure retries instead of stranding a pending run`() {
        val source = Files.readString(
            projectRoot().resolve(
                "app/src/main/java/me/rerere/rikkahub/memory/dreaming/work/" +
                    "DreamSynthesisWorker.kt",
            ),
        )
        val unexpectedFailure = source.substringAfter(
            "catch (error: Exception)",
        ).substringBefore("companion object")

        assertTrue(unexpectedFailure.contains("Result.retry()"))
        assertFalse(unexpectedFailure.contains("Result.failure()"))
    }

    @Test
    fun `lease conflict retires stale scope work and requests durable recovery`() {
        val source = Files.readString(
            projectRoot().resolve(
                "app/src/main/java/me/rerere/rikkahub/memory/dreaming/work/" +
                    "DreamSynthesisWorker.kt",
            ),
        )
        val retryBranch = source.substringAfter("is DreamSynthesisWorkerDirective.Retry ->")
            .substringBefore("is DreamSynthesisWorkerDirective.Deferred ->")

        assertTrue(retryBranch.contains("DreamSynthesisRetryReason.LEASE_CONFLICT"))
        assertTrue(retryBranch.contains("DreamSynthesisScanReason.FOLLOW_UP"))
        assertTrue(retryBranch.contains("Result.success()"))
    }

    @Test
    fun `provider first attempt comes from durable dispatch marker rather than retry counters`() {
        val source = Files.readString(
            projectRoot().resolve(
                "app/src/main/java/me/rerere/rikkahub/memory/dreaming/runtime/" +
                    "DreamSynthesisRuntime.kt",
            ),
        )

        assertTrue(source.contains("existing?.hasDurableProviderDispatchEvidence() != true"))
        assertTrue(source.contains("promptContractVersion != null"))
        assertTrue(source.contains("inputManifestHash != null"))
        assertFalse(source.contains("existing.attempt == 0"))
        assertFalse(source.contains("firstProviderAttempt = workAttempt == 0"))
    }

    private fun projectRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { candidate -> Files.isDirectory(candidate.resolve("app/src/main")) }
}
