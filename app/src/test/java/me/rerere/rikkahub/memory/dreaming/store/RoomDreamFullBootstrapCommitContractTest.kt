package me.rerere.rikkahub.memory.dreaming.store

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDreamFullBootstrapCommitContractTest {
    @Test
    fun `full bootstrap closes only after snapshot cas and incremental keeps journal finish`() {
        val source = Files.readString(
            locateAppRoot().resolve(
                "src/main/java/me/rerere/rikkahub/memory/dreaming/store/RoomDreamSynthesisStore.kt",
            ),
            StandardCharsets.UTF_8,
        )
        val commit = source.substringAfter("private suspend fun commitOrAbort(")
            .substringBefore("private suspend fun touchUnchangedActiveClaim(")

        assertTrue(commit.indexOf("commitActiveSnapshotCas(") < commit.indexOf("finishCommittedSynthesisRun("))
        assertTrue("if (fence.mode != DreamSynthesisMode.FULL)" in commit)
        assertTrue("observerStore.finish(" in commit)
        assertTrue("run.mode != DreamRunMode.FULL.name" in commit)
        assertTrue("run.checkpointEpoch != fence.baseMemoryEpoch" in commit)
        assertTrue("dreamDao.finishRunMirror(" in commit)
        assertTrue("dreamDao.advanceObserverCheckpoint(" in commit)
        assertTrue("dreamDao.releaseScopeLease(" in commit)
    }

    private fun locateAppRoot(): Path {
        val cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        return if (Files.isDirectory(cwd.resolve("src/main/java"))) cwd else cwd.resolve("app")
    }
}
