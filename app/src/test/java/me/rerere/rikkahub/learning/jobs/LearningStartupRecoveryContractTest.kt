package me.rerere.rikkahub.learning.jobs

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningStartupRecoveryContractTest {
    @Test
    fun `application waits for restored settings before flag gated startup scheduling`() {
        val source = Files.readString(
            projectRoot().resolve(
                "app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt",
            ),
        )
        val startup = source.substringAfter("Privacy maintenance is content-free")
            .substringBefore("SecondUserAuthorityService")
        val restoredSettings = startup.indexOf(
            "settingsFlow.first { settings -> !settings.init }",
        )
        val recoverySchedule = startup.indexOf(".scheduleStartupAndRecovery()")

        assertTrue(restoredSettings >= 0)
        assertTrue(recoverySchedule > restoredSettings)
    }

    private fun projectRoot(): Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { candidate -> Files.isDirectory(candidate.resolve("app/src/main")) }
}
