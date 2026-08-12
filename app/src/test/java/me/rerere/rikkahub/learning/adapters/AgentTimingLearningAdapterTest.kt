package me.rerere.rikkahub.learning.adapters

import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingClock
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingEventKind
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingStore
import me.rerere.rikkahub.learning.api.AgentTimingLearningResult
import me.rerere.rikkahub.learning.api.AgentTimingLearningUnknownReason
import me.rerere.rikkahub.learning.api.LearningObservedCount
import me.rerere.rikkahub.learning.api.LearningTimingDuration
import me.rerere.rikkahub.learning.api.LearningTimingMetricUnknownReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentTimingLearningAdapterTest {
    @Test
    fun absentAfterDisableEvictionOrRestartIsUnknownNotZero() = runBlocking {
        val result = AgentTimingLearningAdapter(
            source = AgentTimingSnapshotSource { null },
        ).read(lookup())

        assertEquals(
            AgentTimingLearningResult.Unknown(AgentTimingLearningUnknownReason.NOT_OBSERVED),
            result,
        )
    }

    @Test
    fun sourceExceptionIsIsolatedAsContentFreeUnknown() = runBlocking {
        val result = AgentTimingLearningAdapter(
            source = AgentTimingSnapshotSource { error("prompt=must-not-escape") },
        ).read(lookup())

        assertEquals(
            AgentTimingLearningResult.Unknown(AgentTimingLearningUnknownReason.SOURCE_FAILURE),
            result,
        )
        assertFalse(result.toString().contains("must-not-escape"))
    }

    @Test
    fun sourceTimeoutIsIsolated() = runBlocking {
        val result = AgentTimingLearningAdapter(
            source = AgentTimingSnapshotSource {
                delay(250)
                AgentTimingContentFreeSnapshot()
            },
            readTimeoutMs = 10,
        ).read(lookup())

        assertEquals(
            AgentTimingLearningResult.Unknown(AgentTimingLearningUnknownReason.TIMEOUT),
            result,
        )
    }

    @Test
    fun cooperativeCancellationIsNeverConvertedToUnknown() = runBlocking {
        val marker = CancellationException("caller stopped")
        val adapter = AgentTimingLearningAdapter(
            source = AgentTimingSnapshotSource { throw marker },
        )

        try {
            adapter.read(lookup())
            fail("CancellationException must be rethrown")
        } catch (actual: CancellationException) {
            assertFalse(actual is TimeoutCancellationException)
        }
    }

    @Test
    fun partialStoreSnapshotKeepsKnownDurationsAndMarksMissingOnesUnknown() = runBlocking {
        val clock = MutableClock(100L)
        val store = AgentTimingStore(clock)
        store.setEnabled(true)
        val lookup = lookup()
        val handle = checkNotNull(store.beginSubmission(lookup.conversationId)).handle
        assertTrue(handle.bindAssistantMessage(lookup.assistantMessageId))
        clock.now.set(110L)
        assertTrue(handle.mark(AgentTimingEventKind.DURABLE_ADMITTED))
        clock.now.set(120L)
        assertTrue(handle.mark(AgentTimingEventKind.MEMORY_RETRIEVAL_STARTED))
        clock.now.set(150L)
        assertTrue(handle.mark(AgentTimingEventKind.MEMORY_RETRIEVAL_FINISHED))

        val result = AgentTimingLearningAdapter(store).read(lookup)
            as AgentTimingLearningResult.Available
        assertEquals(
            LearningTimingDuration.Known(10L),
            result.aggregate.submissionToDurableAdmission,
        )
        assertEquals(
            LearningTimingDuration.Known(30L),
            result.aggregate.memoryRetrieval,
        )
        assertEquals(
            LearningTimingDuration.Unknown(
                LearningTimingMetricUnknownReason.MILESTONE_NOT_OBSERVED,
            ),
            result.aggregate.durableQueueWait,
        )
        assertEquals(
            LearningTimingDuration.Unknown(
                LearningTimingMetricUnknownReason.MILESTONE_NOT_OBSERVED,
            ),
            result.aggregate.finishedWallTime,
        )
        assertEquals(LearningObservedCount(4, saturated = false), result.aggregate.observedEventCount)
    }

    @Test
    fun invalidOrOversizedValuesFailClosedOrRemainBounded() = runBlocking {
        val invalid = AgentTimingLearningAdapter(
            source = AgentTimingSnapshotSource {
                AgentTimingContentFreeSnapshot(submittedAtNs = -1L)
            },
        ).read(lookup())
        assertEquals(
            AgentTimingLearningResult.Unknown(AgentTimingLearningUnknownReason.INVALID_SNAPSHOT),
            invalid,
        )

        val available = AgentTimingLearningAdapter(
            source = AgentTimingSnapshotSource {
                AgentTimingContentFreeSnapshot(
                    submittedAtNs = 1L,
                    finishedAtNs = Long.MAX_VALUE,
                    observedEventCount = Long.MAX_VALUE,
                )
            },
        ).read(lookup()) as AgentTimingLearningResult.Available
        assertEquals(
            LearningTimingDuration.Unknown(LearningTimingMetricUnknownReason.OUT_OF_RANGE),
            available.aggregate.finishedWallTime,
        )
        assertTrue(available.aggregate.observedEventCount.saturated)
    }

    @Test
    fun lookupAndResultToStringNeverContainConversationOrMessageIds() = runBlocking {
        val lookup = lookup()
        val result = AgentTimingLearningAdapter(
            source = AgentTimingSnapshotSource {
                AgentTimingContentFreeSnapshot(
                    submittedAtNs = 1L,
                    finishedAtNs = 2L,
                )
            },
        ).read(lookup)
        val rendered = lookup.toString() + result.toString()
        assertFalse(rendered.contains(lookup.conversationId.toString()))
        assertFalse(rendered.contains(lookup.assistantMessageId.toString()))
    }

    private fun lookup() = AgentTimingLookup(CONVERSATION_ID, MESSAGE_ID)

    private class MutableClock(initial: Long) : AgentTimingClock {
        val now = AtomicLong(initial)

        override fun elapsedRealtimeNanos(): Long = now.get()
    }

    private companion object {
        val CONVERSATION_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000101")
        val MESSAGE_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-000000000202")
    }
}
