package me.rerere.rikkahub.learning.handoff

import me.rerere.rikkahub.learning.storage.LearningBootstrapState
import me.rerere.rikkahub.learning.storage.LearningStreamCheckpointEntity
import me.rerere.rikkahub.learning.storage.LearningStreamResetReason
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningStreamResetPlannerTest {
    private val stream = Uuid.parse("00000000-0000-0000-0000-000000000041")

    @Test
    fun `missing derived checkpoint requires a rebuild reset`() {
        val result = LearningStreamResetPlanner.plan(stream, 1L, emptyList())

        assertEquals(
            LearningStreamResetReason.DERIVED_DATABASE_RECREATED,
            assertIs<LearningStreamPlan.Reset>(result).reason,
        )
    }

    @Test
    fun `multiple checkpoints fail closed as corruption`() {
        val result = LearningStreamResetPlanner.plan(
            stream,
            10L,
            listOf(checkpoint(), checkpoint(streamId = "00000000-0000-0000-0000-000000000042")),
        )

        assertEquals(
            LearningStreamResetReason.CORRUPTION,
            assertIs<LearningStreamPlan.Reset>(result).reason,
        )
    }

    @Test
    fun `same stream partial page rewind compares last seen head`() {
        val result = LearningStreamResetPlanner.plan(
            stream,
            headSequence = 80L,
            checkpoints = listOf(
                checkpoint(lastContiguousSeq = 60L, lastSeenHeadSeq = 100L),
            ),
        )

        assertEquals(
            LearningStreamResetReason.HEAD_REWIND,
            assertIs<LearningStreamPlan.Reset>(result).reason,
        )
    }

    @Test
    fun `same stream rewind to exactly consumed watermark still resets`() {
        val result = LearningStreamResetPlanner.plan(
            stream,
            headSequence = 60L,
            checkpoints = listOf(
                checkpoint(lastContiguousSeq = 60L, lastSeenHeadSeq = 100L),
            ),
        )

        assertEquals(
            LearningStreamResetReason.HEAD_REWIND,
            assertIs<LearningStreamPlan.Reset>(result).reason,
        )
    }

    @Test
    fun `rewind below fixed bootstrap head resets before bootstrap resumes`() {
        val result = LearningStreamResetPlanner.plan(
            stream,
            headSequence = 90L,
            checkpoints = listOf(
                checkpoint(
                    lastContiguousSeq = 40L,
                    lastSeenHeadSeq = 90L,
                    bootstrapState = LearningBootstrapState.RUNNING,
                    bootstrapHeadSeq = 100L,
                ),
            ),
        )

        assertEquals(
            LearningStreamResetReason.HEAD_REWIND,
            assertIs<LearningStreamPlan.Reset>(result).reason,
        )
    }

    @Test
    fun `incomplete bootstrap is returned before ordinary consumption`() {
        val result = LearningStreamResetPlanner.plan(
            stream,
            headSequence = 100L,
            checkpoints = listOf(
                checkpoint(
                    lastContiguousSeq = 40L,
                    lastSeenHeadSeq = 100L,
                    bootstrapState = LearningBootstrapState.REQUIRED,
                    bootstrapHeadSeq = 100L,
                ),
            ),
        )

        assertEquals(100L, assertIs<LearningStreamPlan.Bootstrap>(result).headSequence)
    }

    @Test
    fun `bootstrap retry keeps persisted H0 when the live head advances`() {
        val result = LearningStreamResetPlanner.plan(
            stream,
            headSequence = 140L,
            checkpoints = listOf(
                checkpoint(
                    lastContiguousSeq = 60L,
                    lastSeenHeadSeq = 100L,
                    bootstrapState = LearningBootstrapState.DEGRADED,
                    bootstrapHeadSeq = 100L,
                ),
            ),
        )

        assertEquals(100L, assertIs<LearningStreamPlan.Bootstrap>(result).headSequence)
    }

    @Test
    fun `running bootstrap without persisted H0 fails closed as corruption`() {
        val result = LearningStreamResetPlanner.plan(
            stream,
            headSequence = 100L,
            checkpoints = listOf(
                checkpoint(
                    bootstrapState = LearningBootstrapState.RUNNING,
                    bootstrapHeadSeq = null,
                ),
            ),
        )

        assertEquals(
            LearningStreamResetReason.CORRUPTION,
            assertIs<LearningStreamPlan.Reset>(result).reason,
        )
    }

    @Test
    fun `incomplete bootstrap cannot have consumed beyond H0`() {
        val result = LearningStreamResetPlanner.plan(
            stream,
            headSequence = 100L,
            checkpoints = listOf(
                checkpoint(
                    lastContiguousSeq = 90L,
                    lastSeenHeadSeq = 100L,
                    bootstrapState = LearningBootstrapState.DEGRADED,
                    bootstrapHeadSeq = 80L,
                ),
            ),
        )

        assertEquals(
            LearningStreamResetReason.CORRUPTION,
            assertIs<LearningStreamPlan.Reset>(result).reason,
        )
    }

    @Test
    fun `complete checkpoint chooses idle or consume`() {
        assertIs<LearningStreamPlan.Idle>(
            LearningStreamResetPlanner.plan(
                stream,
                100L,
                listOf(checkpoint(lastContiguousSeq = 100L, lastSeenHeadSeq = 100L)),
            ),
        )
        assertIs<LearningStreamPlan.Consume>(
            LearningStreamResetPlanner.plan(
                stream,
                101L,
                listOf(checkpoint(lastContiguousSeq = 100L, lastSeenHeadSeq = 100L)),
            ),
        )
    }

    private fun checkpoint(
        streamId: String = stream.toString(),
        lastContiguousSeq: Long = 0L,
        lastSeenHeadSeq: Long = 0L,
        bootstrapState: LearningBootstrapState = LearningBootstrapState.COMPLETE,
        bootstrapHeadSeq: Long? = null,
    ) = LearningStreamCheckpointEntity(
        streamId = streamId,
        lastContiguousSeq = lastContiguousSeq,
        lastSeenHeadSeq = lastSeenHeadSeq,
        replayGeneration = 0L,
        resetReason = null,
        bootstrapState = bootstrapState.name,
        bootstrapHeadSeq = bootstrapHeadSeq,
        coverageStartMs = null,
        commandCoverageStartMs = null,
        executionCoverageStartMs = null,
        updatedAtMs = 0L,
    )
}

private inline fun <reified T> assertIs(value: Any?): T {
    assertTrue("Expected ${T::class.java.name}, got ${value?.javaClass?.name}", value is T)
    return value as T
}
