package me.rerere.rikkahub.learning.handoff

import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningStreamResetReason

private const val DEFAULT_CONSUMER_BATCH_LIMIT = 64
private const val DEFAULT_CONSUMER_BATCH_ELAPSED_MS = 5_000L

sealed interface LearningConsumeResult {
    data class ResetRequired(val reason: LearningStreamResetReason) : LearningConsumeResult

    data class BootstrapRequired(val headSequence: Long) : LearningConsumeResult

    data class Consumed(val result: LearningIngestResult) : LearningConsumeResult

    /** This consumer's own bounded work budget elapsed; parent cancellation still propagates. */
    data object BudgetExhausted : LearningConsumeResult

    data object Idle : LearningConsumeResult
}

/** One bounded handoff iteration. Scheduling and retries are owned by a later WorkManager adapter. */
class LearningHandoffConsumer(
    private val database: LearningDatabase,
    private val outboxReader: LearningOutboxReader,
    private val batchLimit: Int = DEFAULT_CONSUMER_BATCH_LIMIT,
    private val maxBatchElapsedMs: Long = DEFAULT_CONSUMER_BATCH_ELAPSED_MS,
    private val learnedWorkflowErasePort:
        me.rerere.rikkahub.learning.privacy.ExactScopeLearnedWorkflowErasePort? = null,
    private val durableLearnedWorkflowPrivacyPort:
        me.rerere.rikkahub.learning.privacy.DurableLearnedWorkflowPrivacyPort? = null,
) {
    init {
        require(batchLimit in 1..DEFAULT_CONSUMER_BATCH_LIMIT) {
            "Unsafe consumer batch limit"
        }
        require(maxBatchElapsedMs in 1L..DEFAULT_CONSUMER_BATCH_ELAPSED_MS) {
            "Unsafe consumer elapsed-time limit"
        }
    }

    /** A single cooperative attempt has hard count and elapsed-time bounds. */
    suspend fun consumeOnce(frozenNowMs: Long): LearningConsumeResult {
        require(frozenNowMs >= 0L) { "Negative consumer clock" }
        return withTimeoutOrNull(maxBatchElapsedMs) {
            consumeBoundedBatch(frozenNowMs)
        } ?: LearningConsumeResult.BudgetExhausted
    }

    private suspend fun consumeBoundedBatch(frozenNowMs: Long): LearningConsumeResult {
        val descriptor = outboxReader.inspect()
        val checkpoints = database.checkpointDao().listAll()
        when (
            val plan = LearningStreamResetPlanner.plan(
                streamId = descriptor.streamId,
                headSequence = descriptor.headSequence,
                checkpoints = checkpoints,
            )
        ) {
            is LearningStreamPlan.Reset -> {
                resetterOrThrow().reset(
                    streamId = descriptor.streamId,
                    observedHeadSeq = descriptor.headSequence,
                    reason = plan.reason,
                    frozenNowMs = frozenNowMs,
                )
                return LearningConsumeResult.ResetRequired(plan.reason)
            }

            is LearningStreamPlan.Bootstrap ->
                return LearningConsumeResult.BootstrapRequired(plan.headSequence)

            LearningStreamPlan.Idle -> return LearningConsumeResult.Idle
            LearningStreamPlan.Consume -> Unit
        }
        val checkpoint = checkpoints.single()
        val events = outboxReader.readAfterThrough(
            descriptor = descriptor,
            afterSequence = checkpoint.lastContiguousSeq,
            limit = batchLimit,
        )
        // The two databases cannot share a transaction. Re-inspecting immediately before the
        // derived commit closes the important restore/stream-swap window; the process-wide restore
        // gate supplies the stronger quiescence guarantee.
        val verifiedDescriptor = outboxReader.inspect()
        val resetReason = when {
            verifiedDescriptor.streamId != descriptor.streamId -> LearningStreamResetReason.NEW_STREAM
            verifiedDescriptor.headSequence < descriptor.headSequence ->
                LearningStreamResetReason.HEAD_REWIND
            else -> null
        }
        if (resetReason != null) {
            resetterOrThrow().reset(
                streamId = verifiedDescriptor.streamId,
                observedHeadSeq = verifiedDescriptor.headSequence,
                reason = resetReason,
                frozenNowMs = frozenNowMs,
            )
            return LearningConsumeResult.ResetRequired(resetReason)
        }
        return LearningConsumeResult.Consumed(
            LearningInboxBatchStore(database).ingest(
                LearningIngestBatch(
                    streamId = descriptor.streamId,
                    replayGeneration = checkpoint.replayGeneration,
                    expectedPreviousSeq = checkpoint.lastContiguousSeq,
                    observedHeadSeq = descriptor.headSequence,
                    events = events,
                    ingestedAtMs = frozenNowMs,
                ),
            ),
        )
    }

    /** Resolve both AppDatabase fences before either port can mutate durable state. */
    private fun resetterOrThrow(): LearningDerivedStateResetter = LearningDerivedStateResetter(
        database = database,
        learnedWorkflowErasePort = checkNotNull(learnedWorkflowErasePort) {
            "derived_reset_candidate_fence_unconfigured"
        },
        durableLearnedWorkflowPrivacyPort = checkNotNull(durableLearnedWorkflowPrivacyPort) {
            "derived_reset_orphan_quarantine_unconfigured"
        },
    )
}
