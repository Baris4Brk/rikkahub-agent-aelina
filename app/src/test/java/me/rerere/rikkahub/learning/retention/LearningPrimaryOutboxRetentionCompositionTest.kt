package me.rerere.rikkahub.learning.retention

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.rerere.rikkahub.learning.runtime.LearningRuntimeOperationFence
import me.rerere.rikkahub.learning.storage.LearningBootstrapState
import me.rerere.rikkahub.learning.storage.LearningStreamCheckpointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningPrimaryOutboxRetentionCompositionTest {
    @Test
    fun onlyOneFullyCompleteCheckpointFreezesExactStreamReplayAndContiguousPosition() {
        val exact = freezeDerivedOutboxConsumerCheckpointOrNull(listOf(checkpoint()))
        requireNotNull(exact)
        assertEquals(LearningDurableConsumerId.LEARNING_DERIVED_RUNTIME, exact.consumerId)
        assertEquals(STREAM, exact.streamId)
        assertEquals(7L, exact.replayGeneration)
        assertEquals(90L, exact.lastContiguousSequence)
        assertTrue(exact.bootstrapComplete)

        assertNull(freezeDerivedOutboxConsumerCheckpointOrNull(emptyList()))
        assertNull(freezeDerivedOutboxConsumerCheckpointOrNull(listOf(checkpoint(), checkpoint())))
        assertNull(
            freezeDerivedOutboxConsumerCheckpointOrNull(
                listOf(checkpoint().copy(bootstrapState = LearningBootstrapState.RUNNING.name)),
            ),
        )
        assertNull(
            freezeDerivedOutboxConsumerCheckpointOrNull(
                listOf(checkpoint().copy(bootstrapHeadSeq = null)),
            ),
        )
        assertNull(
            freezeDerivedOutboxConsumerCheckpointOrNull(
                listOf(checkpoint().copy(lastContiguousSeq = 79L)),
            ),
        )
    }

    @Test
    fun productionCompositionPassesOnlyTheFrozenCheckpointToPrimaryAuthority() = runBlocking {
        var captured: LearningOutboxRetentionRequest? = null
        val result = prunePrimaryOutboxFromFrozenCheckpoint(
            port = LearningPrimaryOutboxRetentionPort { request ->
                captured = request
                LearningOutboxRetentionResult.Completed(1, true)
            },
            checkpoint = requireNotNull(
                freezeDerivedOutboxConsumerCheckpointOrNull(listOf(checkpoint())),
            ),
            frozenNowMs = 123_456L,
            batchSize = 17,
        )

        assertEquals(LearningOutboxRetentionResult.Completed(1, true), result)
        assertEquals(123_456L, captured?.frozenNowMs)
        assertEquals(17, captured?.batchSize)
        assertEquals(7L, captured?.checkpoints?.single()?.replayGeneration)
        assertEquals(90L, captured?.checkpoints?.single()?.lastContiguousSequence)
    }

    @Test
    fun resetAndRestoreCannotEnterBetweenCheckpointFreezeAndPrimaryDelete() = runBlocking {
        val fence = LearningRuntimeOperationFence()
        val primaryEntered = CompletableDeferred<Unit>()
        val releasePrimary = CompletableDeferred<Unit>()
        val resetEntered = CompletableDeferred<Unit>()
        val restoreEntered = CompletableDeferred<Unit>()
        var current = checkpoint()
        var primarySaw: LearningDurableConsumerCheckpoint? = null
        val port = LearningPrimaryOutboxRetentionPort { request ->
            primarySaw = request.checkpoints.single()
            primaryEntered.complete(Unit)
            releasePrimary.await()
            LearningOutboxRetentionResult.Completed(0, false)
        }

        val retention = async {
            fence.withLock {
                val frozen = requireNotNull(
                    freezeDerivedOutboxConsumerCheckpointOrNull(listOf(current)),
                )
                prunePrimaryOutboxFromFrozenCheckpoint(port, frozen, 100L, 10)
            }
        }
        primaryEntered.await()
        val reset = async {
            fence.withLock {
                resetEntered.complete(Unit)
                current = checkpoint(replayGeneration = 8L, contiguous = 1L, bootstrapHead = 1L)
            }
        }
        val restore = async {
            fence.withLock {
                restoreEntered.complete(Unit)
            }
        }
        repeat(4) { yield() }
        assertFalse("reset must wait behind the primary delete", resetEntered.isCompleted)
        assertFalse("restore must wait behind the primary delete", restoreEntered.isCompleted)

        releasePrimary.complete(Unit)
        retention.await()
        reset.await()
        restore.await()
        assertTrue(resetEntered.isCompleted)
        assertTrue(restoreEntered.isCompleted)
        assertEquals(7L, primarySaw?.replayGeneration)
        assertEquals(90L, primarySaw?.lastContiguousSequence)
        assertEquals(8L, current.replayGeneration)
        assertEquals(1L, current.lastContiguousSeq)
    }

    @Test
    fun facadeCompositionKeepsCheckpointReadAndPrimaryCallInsideSharedOperationFence() {
        val source = java.io.File(
            "src/main/java/me/rerere/rikkahub/learning/runtime/LearningRuntimeFacade.kt",
        ).readText()
        val dependencyGraph = java.io.File(
            "src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt",
        ).readText()
        val sweep = source.substringAfter("override suspend fun sweepOnce(")
            .substringBefore("/** P1 shadow retrieval")
        val withDatabase = sweep.indexOf("withDatabase {")
        val freeze = sweep.indexOf("freezeDerivedOutboxConsumerCheckpointOrNull(")
        val prune = sweep.indexOf("prunePrimaryOutboxFromFrozenCheckpoint(")
        val receipt = sweep.indexOf("toMaintenanceReceipt(request.batchSize, outboxResult)")

        assertTrue(source.contains("private val mutex = LearningRuntimeOperationFence()"))
        assertTrue(
            dependencyGraph.contains(
                "single<me.rerere.rikkahub.learning.retention." +
                    "LearningPrimaryOutboxRetentionPort>",
            ),
        )
        assertTrue(dependencyGraph.contains("primaryOutboxRetention = get()"))
        val restore = source.substringAfter("suspend fun beginRestore(): Long")
            .substringBefore("suspend fun remainClosedAfterRestore()")
        val erase = source.substringAfter("suspend fun eraseDerivedScope(")
            .substringBefore("private fun markFatalLocked")
        assertTrue(restore.contains("mutex.withLock"))
        assertTrue(erase.contains("mutex.withLock"))
        assertTrue(withDatabase >= 0)
        assertTrue(freeze > withDatabase)
        assertTrue(prune > freeze)
        assertTrue(receipt > prune)
    }

    private fun checkpoint(
        replayGeneration: Long = 7L,
        contiguous: Long = 90L,
        bootstrapHead: Long = 80L,
    ) = LearningStreamCheckpointEntity(
        streamId = STREAM,
        lastContiguousSeq = contiguous,
        lastSeenHeadSeq = contiguous,
        replayGeneration = replayGeneration,
        resetReason = null,
        bootstrapState = LearningBootstrapState.COMPLETE.name,
        bootstrapHeadSeq = bootstrapHead,
        coverageStartMs = 1L,
        commandCoverageStartMs = 1L,
        executionCoverageStartMs = 1L,
        updatedAtMs = 2L,
    )

    private companion object {
        const val STREAM = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    }
}
