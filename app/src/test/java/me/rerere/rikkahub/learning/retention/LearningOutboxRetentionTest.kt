package me.rerere.rikkahub.learning.retention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningOutboxRetentionTest {
    @Test
    fun requiresEveryRegisteredConsumerExactlyOnce() {
        assertEquals(
            LearningOutboxPruneDecision.Unavailable(
                LearningOutboxPruneUnavailableReason.CONSUMER_MISSING,
            ),
            LearningOutboxRetentionPlanner.plan(input(checkpoints = emptyList())),
        )
        val duplicate = checkpoint()
        assertEquals(
            LearningOutboxPruneDecision.Unavailable(
                LearningOutboxPruneUnavailableReason.CONSUMER_DUPLICATE,
            ),
            LearningOutboxRetentionPlanner.plan(
                input(checkpoints = listOf(duplicate, duplicate)),
            ),
        )
    }

    @Test
    fun rejectsIncompleteForeignOrImpossibleCheckpoint() {
        assertUnavailable(
            LearningOutboxPruneUnavailableReason.CONSUMER_NOT_BOOTSTRAPPED,
            checkpoint().copy(bootstrapComplete = false),
        )
        assertUnavailable(
            LearningOutboxPruneUnavailableReason.STREAM_MISMATCH,
            checkpoint().copy(streamId = OTHER_STREAM),
        )
        assertUnavailable(
            LearningOutboxPruneUnavailableReason.CHECKPOINT_AHEAD_OF_AUTHORITY,
            checkpoint().copy(lastContiguousSequence = 10_001L),
        )
    }

    @Test
    fun freezesMinimumCheckpointAgeAndHeadRelativeSafetyFloor() {
        val ready = LearningOutboxRetentionPlanner.plan(
            input(checkpoints = listOf(checkpoint())),
        ) as LearningOutboxPruneDecision.Ready
        assertEquals(9_000L, ready.plan.throughMinConsumerSequence)
        assertEquals(NOW - AGE, ready.plan.createdBeforeMs)
        assertEquals(8_977L, ready.plan.keepFromSequence)
    }

    @Test
    fun safetyFloorNeverCrossesStreamInitAndYoungDatabasePrunesNothingByAge() {
        val ready = LearningOutboxRetentionPlanner.plan(
            input(
                checkpoints = listOf(checkpoint(last = 1L)),
                head = 3L,
                now = AGE - 1L,
                safetyRows = 1_024L,
            ),
        ) as LearningOutboxPruneDecision.Ready
        assertEquals(1L, ready.plan.keepFromSequence)
        assertEquals(0L, ready.plan.createdBeforeMs)
    }

    @Test
    fun daoContractPinsAllThreeGatesAndStreamInitExclusion() {
        val source = java.io.File(
            "src/main/java/me/rerere/rikkahub/data/db/dao/LearningOutboxDao.kt",
        ).readText()
        val method = source.substringAfter("suspend fun deletePrunablePage")
        assertTrue(source.contains("event_type != 'STREAM_INIT'"))
        assertTrue(source.contains("seq <= :throughMinConsumerSeq"))
        assertTrue(source.contains("seq < :keepFromSeq"))
        assertTrue(source.contains("created_at_ms < :createdBeforeMs"))
        assertTrue(method.contains("limit: Int"))
    }

    private fun assertUnavailable(
        expected: LearningOutboxPruneUnavailableReason,
        checkpoint: LearningDurableConsumerCheckpoint,
    ) {
        assertEquals(
            LearningOutboxPruneDecision.Unavailable(expected),
            LearningOutboxRetentionPlanner.plan(input(checkpoints = listOf(checkpoint))),
        )
    }

    private fun input(
        checkpoints: List<LearningDurableConsumerCheckpoint>,
        head: Long = 10_000L,
        now: Long = NOW,
        safetyRows: Long = 1_024L,
    ) = LearningOutboxRetentionInput(
        streamId = STREAM,
        authoritativeHeadSequence = head,
        frozenNowMs = now,
        checkpoints = checkpoints,
        minimumAgeMs = AGE,
        safetyFloorRows = safetyRows,
    )

    private fun checkpoint(last: Long = 9_000L) = LearningDurableConsumerCheckpoint(
        consumerId = LearningDurableConsumerId.LEARNING_DERIVED_RUNTIME,
        streamId = STREAM,
        replayGeneration = 3L,
        lastContiguousSequence = last,
        bootstrapComplete = true,
    )

    private companion object {
        const val STREAM = "11111111-1111-4111-8111-111111111111"
        const val OTHER_STREAM = "22222222-2222-4222-8222-222222222222"
        const val NOW = 10_000_000_000L
        const val AGE = 1_000_000L
    }
}
