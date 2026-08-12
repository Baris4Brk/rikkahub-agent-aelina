package me.rerere.rikkahub.learning.storage.restore

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.storage.LearningDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LearningRestoreCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun noDatabasePreflightTouchesNoPortOrFile() = runBlocking {
        val fixture = fixture("noop")
        val source = File(fixture.databases, LearningDatabase.FILE_NAME).apply {
            writeText("must remain")
        }
        val coordinator = LearningRestoreCoordinator(
            ownedPaths = fixture.validation,
            scheduler = LearningRestoreSchedulerPort { fail("scheduler touched") },
            runtime = FailingRuntimePort(),
            mainWriteGate = MainDatabaseRestoreWriteGate { error("main gate touched") },
        )

        val result = coordinator.restore(
            LearningRestorePreflight.NoDatabaseSelected,
            VerifiedMainDatabaseRestoreAction { fail("restore touched") },
        )

        assertEquals(LearningRestoreResult.NoOp, result)
        assertEquals("must remain", source.readText())
    }

    @Test
    fun missingApplicationWideMainWriteGateBlocksBeforeSchedulerRuntimeOrFiles() = runBlocking {
        val fixture = fixture("missing-gate")
        val source = File(fixture.databases, LearningDatabase.FILE_NAME).apply { writeText("live") }
        val coordinator = LearningRestoreCoordinator(
            ownedPaths = fixture.validation,
            scheduler = LearningRestoreSchedulerPort { fail("scheduler touched") },
            runtime = FailingRuntimePort(),
        )

        val result = coordinator.restore(
            fixture.preflight,
            VerifiedMainDatabaseRestoreAction { fail("restore touched") },
        )

        assertTrue(result is LearningRestoreResult.Blocked)
        result as LearningRestoreResult.Blocked
        assertEquals(LearningRestoreBlockReason.MAIN_DATABASE_WRITE_GATE_MISSING, result.reason)
        assertEquals("live", source.readText())
    }

    @Test
    fun archiveChangedAfterPreflightAbortsBeforeRuntimeAndResumesScheduler() = runBlocking {
        val fixture = fixture("changed-after-preflight")
        val source = File(fixture.databases, LearningDatabase.FILE_NAME).apply { writeText("live") }
        fixture.archive.appendBytes(byteArrayOf(0))
        val events = mutableListOf<String>()
        val gate = RecordingGate(events)
        val scheduler = object : LearningRestoreSchedulerPort {
            override suspend fun stopAndAwaitIdle() {
                events += "scheduler_idle"
            }

            override suspend fun resumeAfterAbortedRestore() {
                events += "scheduler_resumed"
            }
        }
        val coordinator = LearningRestoreCoordinator(
            ownedPaths = fixture.validation,
            scheduler = scheduler,
            runtime = FailingRuntimePort(),
            mainWriteGate = gate,
        )

        val result = coordinator.restore(
            fixture.preflight,
            VerifiedMainDatabaseRestoreAction { fail("restore touched") },
        )

        assertTrue(result is LearningRestoreResult.Blocked)
        result as LearningRestoreResult.Blocked
        assertEquals(LearningRestoreBlockReason.ARCHIVE_CHANGED_AFTER_PREFLIGHT, result.reason)
        assertEquals(
            listOf("gate_acquired", "scheduler_idle", "scheduler_resumed", "gate_released"),
            events,
        )
        assertTrue(gate.released)
        assertFalse(gate.sealed)
        assertEquals("live", source.readText())
    }

    @Test
    fun verifiedRestoreQuarantinesThreeFilesFencesOldCallbacksAndRequiresRestart() = runBlocking {
        val fixture = fixture("success")
        val names = listOf(
            LearningDatabase.FILE_NAME,
            "${LearningDatabase.FILE_NAME}-wal",
            "${LearningDatabase.FILE_NAME}-shm",
        )
        names.forEachIndexed { index, name ->
            File(fixture.databases, name).writeBytes(byteArrayOf(index.toByte()))
        }
        val events = mutableListOf<String>()
        val gate = RecordingGate(events)
        val runtime = RecordingRuntime(events, initialGeneration = 41L)
        val coordinator = LearningRestoreCoordinator(
            ownedPaths = fixture.validation,
            scheduler = LearningRestoreSchedulerPort { events += "scheduler_idle" },
            runtime = runtime,
            mainWriteGate = gate,
            quarantineFactory = { paths ->
                LearningRestoreQuarantine(
                    paths,
                    LearningQuarantineIdSource { "0011223344556677" },
                )
            },
        )
        val oldCallbackGeneration = runtime.generation

        val result = coordinator.restore(
            fixture.preflight,
            VerifiedMainDatabaseRestoreAction { archive ->
                events += "main_restore"
                assertEquals(fixture.archive.absoluteFile, archive.archiveFile)
            },
        )

        assertTrue(result is LearningRestoreResult.ProcessRestartRequired)
        result as LearningRestoreResult.ProcessRestartRequired
        assertEquals(42L, result.runtimeFence.generation)
        assertTrue(result.runtimeFence.fencesCallback(oldCallbackGeneration))
        assertFalse(runtime.tryCommitLateCallback(oldCallbackGeneration))
        assertEquals(RuntimeState.RESTORING, runtime.state)
        assertTrue(gate.sealed)
        assertFalse(gate.released)
        assertEquals(3, result.quarantine.fileCount)
        names.forEach { name ->
            assertFalse(File(fixture.databases, name).exists())
            assertTrue(result.quarantine.containsFileName(name))
        }
        assertEquals(
            listOf("gate_acquired", "scheduler_idle", "runtime_begin", "gate_sealed", "main_restore", "runtime_closed"),
            events,
        )
    }

    @Test
    fun restoreFailureKeepsQuarantineAndRuntimeDegradedWithoutRollback() = runBlocking {
        val fixture = fixture("failure")
        val source = File(fixture.databases, LearningDatabase.FILE_NAME).apply { writeText("old") }
        val events = mutableListOf<String>()
        val gate = RecordingGate(events)
        val runtime = RecordingRuntime(events)
        val coordinator = LearningRestoreCoordinator(
            ownedPaths = fixture.validation,
            scheduler = LearningRestoreSchedulerPort { events += "scheduler_idle" },
            runtime = runtime,
            mainWriteGate = gate,
            quarantineFactory = { paths ->
                LearningRestoreQuarantine(
                    paths,
                    LearningQuarantineIdSource { "8899aabbccddeeff" },
                )
            },
        )

        val result = coordinator.restore(
            fixture.preflight,
            VerifiedMainDatabaseRestoreAction { throw IllegalStateException("simulated") },
        )

        assertTrue(result is LearningRestoreResult.FailedRestartRequired)
        result as LearningRestoreResult.FailedRestartRequired
        assertEquals(LearningRestoreFailureReason.MAIN_DATABASE_RESTORE_FAILED, result.reason)
        assertEquals(RuntimeState.DEGRADED, runtime.state)
        assertTrue(gate.sealed)
        assertFalse(gate.released)
        assertFalse(source.exists())
        assertEquals("old", result.quarantine!!.quarantinedFiles.single().toFile().readText())
        assertTrue(result.quarantine!!.directory!!.toFile().exists())
    }

    @Test
    fun cancellationAfterIrreversibleFencePropagatesAndRetainsQuarantine() = runBlocking {
        val fixture = fixture("cancel")
        val source = File(fixture.databases, LearningDatabase.FILE_NAME).apply { writeText("old") }
        val events = mutableListOf<String>()
        val gate = RecordingGate(events)
        val runtime = RecordingRuntime(events)
        val restoreEntered = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        val coordinator = LearningRestoreCoordinator(
            ownedPaths = fixture.validation,
            scheduler = LearningRestoreSchedulerPort { events += "scheduler_idle" },
            runtime = runtime,
            mainWriteGate = gate,
            quarantineFactory = { paths ->
                LearningRestoreQuarantine(
                    paths,
                    LearningQuarantineIdSource { "ffeeddccbbaa9988" },
                )
            },
        )

        val job = launch {
            coordinator.restore(
                fixture.preflight,
                VerifiedMainDatabaseRestoreAction {
                    restoreEntered.complete(Unit)
                    neverComplete.await()
                },
            )
        }
        restoreEntered.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(RuntimeState.DEGRADED, runtime.state)
        assertEquals(
            LearningRestoreFailureReason.CANCELLED_AFTER_IRREVERSIBLE_FENCE,
            runtime.degradedReason,
        )
        assertTrue(gate.sealed)
        assertFalse(gate.released)
        assertFalse(source.exists())
        assertTrue(
            fixture.databases.listFiles().orEmpty().any {
                it.name.startsWith(".learning_runtime_quarantine_") && it.isDirectory
            },
        )
    }

    private fun fixture(name: String): CoordinatorFixture {
        val appData = temporaryFolder.newFolder("app-$name")
        val databases = File(appData, "databases").apply { mkdir() }
        val archive = createVerifiedArchive(temporaryFolder.newFile("$name.zip"))
        val preflight = LearningRestoreArchivePreflightVerifier().inspect(
            archive,
            setOf(LearningRestoreComponent.DATABASE),
        )
        assertTrue(preflight is LearningRestorePreflight.VerifiedDatabase)
        val validation = LearningOwnedDatabasePaths.verify(
            appData.absoluteFile,
            File(databases, LearningDatabase.FILE_NAME).absoluteFile,
        )
        assertTrue(validation is LearningOwnedDatabasePathValidation.Valid)
        return CoordinatorFixture(
            databases = databases,
            archive = archive,
            preflight = preflight as LearningRestorePreflight.VerifiedDatabase,
            validation = validation,
        )
    }

    private data class CoordinatorFixture(
        val databases: File,
        val archive: File,
        val preflight: LearningRestorePreflight.VerifiedDatabase,
        val validation: LearningOwnedDatabasePathValidation,
    )

    private class RecordingGate(private val events: MutableList<String>) :
        MainDatabaseRestoreWriteGate,
        MainDatabaseRestoreGateLease {
        var sealed: Boolean = false
        var released: Boolean = false

        override suspend fun acquireAndAwaitNoWriters(): MainDatabaseRestoreGateAccess {
            events += "gate_acquired"
            return MainDatabaseRestoreGateAccess.Acquired(this)
        }

        override fun sealUntilProcessRestart() {
            sealed = true
            events += "gate_sealed"
        }

        override fun releaseBeforeRestore() {
            released = true
            events += "gate_released"
        }
    }

    private enum class RuntimeState { READY, RESTORING, DEGRADED }

    private class RecordingRuntime(
        private val events: MutableList<String>,
        initialGeneration: Long = 7L,
    ) : LearningRuntimeRestorePort {
        var generation: Long = initialGeneration
        var state: RuntimeState = RuntimeState.READY
        var degradedReason: LearningRestoreFailureReason? = null

        override suspend fun beginIrreversibleRestore(): LearningRestoreRuntimeFence {
            generation += 1L
            state = RuntimeState.RESTORING
            events += "runtime_begin"
            return LearningRestoreRuntimeFence(generation)
        }

        override suspend fun remainClosedUntilProcessRestart(fence: LearningRestoreRuntimeFence) {
            assertEquals(generation, fence.generation)
            state = RuntimeState.RESTORING
            events += "runtime_closed"
        }

        override suspend fun remainDegradedUntilProcessRestart(
            fence: LearningRestoreRuntimeFence,
            reason: LearningRestoreFailureReason,
        ) {
            assertEquals(generation, fence.generation)
            state = RuntimeState.DEGRADED
            degradedReason = reason
            events += "runtime_degraded"
        }

        fun tryCommitLateCallback(callbackGeneration: Long): Boolean =
            state == RuntimeState.READY && callbackGeneration == generation
    }

    private class FailingRuntimePort : LearningRuntimeRestorePort {
        override suspend fun beginIrreversibleRestore(): LearningRestoreRuntimeFence =
            error("runtime touched")

        override suspend fun remainClosedUntilProcessRestart(fence: LearningRestoreRuntimeFence) {
            error("runtime touched")
        }

        override suspend fun remainDegradedUntilProcessRestart(
            fence: LearningRestoreRuntimeFence,
            reason: LearningRestoreFailureReason,
        ) {
            error("runtime touched")
        }
    }
}
