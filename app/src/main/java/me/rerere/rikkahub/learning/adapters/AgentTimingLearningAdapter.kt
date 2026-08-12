package me.rerere.rikkahub.learning.adapters

import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingEventKind
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingStore
import me.rerere.rikkahub.diagnostics.agenttiming.AgentTimingTraceSnapshot
import me.rerere.rikkahub.learning.api.AgentTimingLearningAggregate
import me.rerere.rikkahub.learning.api.AgentTimingLearningResult
import me.rerere.rikkahub.learning.api.AgentTimingLearningUnknownReason
import me.rerere.rikkahub.learning.api.LearningObservedCount
import me.rerere.rikkahub.learning.api.LearningTimingDuration
import me.rerere.rikkahub.learning.api.LearningTimingMetricUnknownReason
import me.rerere.rikkahub.learning.api.MAX_LEARNING_OBSERVED_COUNT
import me.rerere.rikkahub.learning.api.MAX_LEARNING_TIMING_DURATION_NS

private const val DEFAULT_AGENT_TIMING_READ_TIMEOUT_MS = 50L
private const val MAX_AGENT_TIMING_READ_TIMEOUT_MS = 5_000L

/** Scope-bearing IDs are lookup-only and are always redacted from diagnostics. */
class AgentTimingLookup(
    val conversationId: Uuid,
    val assistantMessageId: Uuid,
) {
    override fun toString(): String = "AgentTimingLookup(ids=<redacted>)"
}

/**
 * Reads one final-or-partial process snapshot and immediately removes every identifier and
 * content-bearing collection from it.
 */
internal class AgentTimingStoreSnapshotSource(
    private val store: AgentTimingStore,
) : AgentTimingSnapshotSource {
    override suspend fun read(lookup: AgentTimingLookup): AgentTimingContentFreeSnapshot? =
        store.snapshotForMessage(
            conversationId = lookup.conversationId,
            messageId = lookup.assistantMessageId,
        )?.toContentFreeSnapshot()
}

/** Test seam and strict boundary: implementations may return fixed numbers only, never content. */
internal fun interface AgentTimingSnapshotSource {
    suspend fun read(lookup: AgentTimingLookup): AgentTimingContentFreeSnapshot?
}

internal data class AgentTimingContentFreeSnapshot(
    val submittedAtNs: Long? = null,
    val durableAdmittedAtNs: Long? = null,
    val runtimeDequeuedAtNs: Long? = null,
    val runStartedAtNs: Long? = null,
    val runEndedAtNs: Long? = null,
    val memoryRetrievalStartedAtNs: Long? = null,
    val memoryRetrievalFinishedAtNs: Long? = null,
    val providerDispatchAtNs: Long? = null,
    val providerFirstProgressAtNs: Long? = null,
    val finalSaveStartedAtNs: Long? = null,
    val finalSaveFinishedAtNs: Long? = null,
    val firstVisibleDrawAtNs: Long? = null,
    val finishedAtNs: Long? = null,
    val observedRoundCount: Long = 0L,
    val observedToolTimingCount: Long = 0L,
    val observedEventCount: Long = 0L,
    val droppedRoundCount: Long = 0L,
    val droppedToolTimingCount: Long = 0L,
    val droppedEventCount: Long = 0L,
)

/**
 * Failure-isolated adapter for optional AgentTiming features.
 *
 * A timeout or source bug becomes an explicit UNKNOWN result. Cooperative cancellation from the
 * caller is always rethrown, so this diagnostic adapter cannot accidentally defeat job shutdown.
 */
class AgentTimingLearningAdapter internal constructor(
    private val source: AgentTimingSnapshotSource,
    private val readTimeoutMs: Long = DEFAULT_AGENT_TIMING_READ_TIMEOUT_MS,
) {
    init {
        require(readTimeoutMs in 1L..MAX_AGENT_TIMING_READ_TIMEOUT_MS) {
            "Unsafe AgentTiming read timeout"
        }
    }

    constructor(
        store: AgentTimingStore,
        readTimeoutMs: Long = DEFAULT_AGENT_TIMING_READ_TIMEOUT_MS,
    ) : this(AgentTimingStoreSnapshotSource(store), readTimeoutMs)

    suspend fun read(lookup: AgentTimingLookup): AgentTimingLearningResult {
        val completed = try {
            withTimeoutOrNull(readTimeoutMs) {
                CompletedRead(source.read(lookup))
            } ?: return AgentTimingLearningResult.Unknown(
                AgentTimingLearningUnknownReason.TIMEOUT,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return AgentTimingLearningResult.Unknown(
                AgentTimingLearningUnknownReason.SOURCE_FAILURE,
            )
        }
        val snapshot = completed.snapshot
            ?: return AgentTimingLearningResult.Unknown(
                AgentTimingLearningUnknownReason.NOT_OBSERVED,
            )
        if (!snapshot.isValid()) {
            return AgentTimingLearningResult.Unknown(
                AgentTimingLearningUnknownReason.INVALID_SNAPSHOT,
            )
        }
        return AgentTimingLearningResult.Available(snapshot.toAggregate())
    }

    override fun toString(): String = "AgentTimingLearningAdapter(timeoutMs=$readTimeoutMs)"
}

private class CompletedRead(val snapshot: AgentTimingContentFreeSnapshot?)

private fun AgentTimingTraceSnapshot.toContentFreeSnapshot(): AgentTimingContentFreeSnapshot =
    AgentTimingContentFreeSnapshot(
        submittedAtNs = submittedAtNs,
        durableAdmittedAtNs = at(AgentTimingEventKind.DURABLE_ADMITTED),
        runtimeDequeuedAtNs = at(AgentTimingEventKind.RUNTIME_DEQUEUED),
        runStartedAtNs = at(AgentTimingEventKind.RUN_STARTED),
        runEndedAtNs = at(AgentTimingEventKind.RUN_ENDED),
        memoryRetrievalStartedAtNs = at(AgentTimingEventKind.MEMORY_RETRIEVAL_STARTED),
        memoryRetrievalFinishedAtNs = at(AgentTimingEventKind.MEMORY_RETRIEVAL_FINISHED),
        providerDispatchAtNs = at(AgentTimingEventKind.APP_PROVIDER_DISPATCH),
        providerFirstProgressAtNs = at(AgentTimingEventKind.PROVIDER_FIRST_PROGRESS),
        finalSaveStartedAtNs = at(AgentTimingEventKind.FINAL_SAVE_STARTED),
        finalSaveFinishedAtNs = at(AgentTimingEventKind.FINAL_SAVE_FINISHED),
        firstVisibleDrawAtNs = firstVisibleDrawAtNs,
        finishedAtNs = finishedAtNs,
        observedRoundCount = saturatingAdd(rounds.size.toLong(), droppedRoundCount),
        observedToolTimingCount = saturatingAdd(tools.size.toLong(), droppedToolCount),
        observedEventCount = saturatingAdd(events.size.toLong(), droppedEventCount),
        droppedRoundCount = droppedRoundCount,
        droppedToolTimingCount = droppedToolCount,
        droppedEventCount = droppedEventCount,
    )

private fun AgentTimingContentFreeSnapshot.isValid(): Boolean {
    val timestamps = listOfNotNull(
        submittedAtNs,
        durableAdmittedAtNs,
        runtimeDequeuedAtNs,
        runStartedAtNs,
        runEndedAtNs,
        memoryRetrievalStartedAtNs,
        memoryRetrievalFinishedAtNs,
        providerDispatchAtNs,
        providerFirstProgressAtNs,
        finalSaveStartedAtNs,
        finalSaveFinishedAtNs,
        firstVisibleDrawAtNs,
        finishedAtNs,
    )
    val counts = listOf(
        observedRoundCount,
        observedToolTimingCount,
        observedEventCount,
        droppedRoundCount,
        droppedToolTimingCount,
        droppedEventCount,
    )
    return timestamps.all { it >= 0L } && counts.all { it >= 0L }
}

private fun AgentTimingContentFreeSnapshot.toAggregate(): AgentTimingLearningAggregate =
    AgentTimingLearningAggregate(
        submissionToDurableAdmission = duration(submittedAtNs, durableAdmittedAtNs),
        durableQueueWait = duration(durableAdmittedAtNs, runtimeDequeuedAtNs),
        runtimeExecution = duration(runStartedAtNs, runEndedAtNs),
        memoryRetrieval = duration(memoryRetrievalStartedAtNs, memoryRetrievalFinishedAtNs),
        providerDispatchToFirstProgress = duration(
            providerDispatchAtNs,
            providerFirstProgressAtNs,
        ),
        finalSave = duration(finalSaveStartedAtNs, finalSaveFinishedAtNs),
        submissionToFirstVisibleDraw = duration(submittedAtNs, firstVisibleDrawAtNs),
        finishedWallTime = duration(submittedAtNs, finishedAtNs),
        observedRoundCount = boundedCount(observedRoundCount),
        observedToolTimingCount = boundedCount(observedToolTimingCount),
        observedEventCount = boundedCount(observedEventCount),
        droppedRoundCount = boundedCount(droppedRoundCount),
        droppedToolTimingCount = boundedCount(droppedToolTimingCount),
        droppedEventCount = boundedCount(droppedEventCount),
    )

private fun duration(startNs: Long?, endNs: Long?): LearningTimingDuration {
    if (startNs == null || endNs == null) {
        return LearningTimingDuration.Unknown(
            LearningTimingMetricUnknownReason.MILESTONE_NOT_OBSERVED,
        )
    }
    if (endNs < startNs) {
        return LearningTimingDuration.Unknown(LearningTimingMetricUnknownReason.INVALID_ORDER)
    }
    val duration = endNs - startNs
    if (duration > MAX_LEARNING_TIMING_DURATION_NS) {
        return LearningTimingDuration.Unknown(LearningTimingMetricUnknownReason.OUT_OF_RANGE)
    }
    return LearningTimingDuration.Known(duration)
}

private fun boundedCount(value: Long): LearningObservedCount = if (
    value > MAX_LEARNING_OBSERVED_COUNT.toLong()
) {
    LearningObservedCount(MAX_LEARNING_OBSERVED_COUNT, saturated = true)
} else {
    LearningObservedCount(value.toInt(), saturated = false)
}

private fun saturatingAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
