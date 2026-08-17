package me.rerere.rikkahub.learning.retention

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.learning.handoff.LearningOutboxHealthException
import me.rerere.rikkahub.learning.handoff.validateLearningOutboxSentinels
import me.rerere.rikkahub.learning.handoff.validateLearningOutboxStreams

const val MAX_OUTBOX_RETENTION_BATCH_SIZE: Int = 128

data class LearningOutboxRetentionRequest(
    val checkpoints: List<LearningDurableConsumerCheckpoint>,
    val frozenNowMs: Long,
    val batchSize: Int = MAX_OUTBOX_RETENTION_BATCH_SIZE,
    val minimumAgeMs: Long = DEFAULT_OUTBOX_MINIMUM_AGE_MS,
    val safetyFloorRows: Long = DEFAULT_OUTBOX_SAFETY_FLOOR_ROWS,
) {
    init {
        require(frozenNowMs >= 0L)
        require(batchSize in 1..MAX_OUTBOX_RETENTION_BATCH_SIZE)
    }
}

sealed interface LearningOutboxRetentionResult {
    data class Completed(
        val deletedRows: Int,
        val workMayRemain: Boolean,
    ) : LearningOutboxRetentionResult {
        init {
            require(deletedRows in 0..MAX_OUTBOX_RETENTION_BATCH_SIZE)
            require(!workMayRemain || deletedRows > 0)
        }
    }

    data class Unavailable(val reason: LearningOutboxPruneUnavailableReason) :
        LearningOutboxRetentionResult

    /** Malformed or changing authority is retried without deleting any row. */
    data object AuthorityUnavailable : LearningOutboxRetentionResult
}

fun interface LearningPrimaryOutboxRetentionPort {
    suspend fun pruneOnce(request: LearningOutboxRetentionRequest): LearningOutboxRetentionResult
}

/**
 * Main-database half of P5 outbox retention. Every authority read, the pure three-gate decision,
 * and the bounded DELETE share one AppDatabase transaction. STREAM_INIT is excluded again by the
 * DAO SQL, so neither a stale caller nor a planner defect can delete the lineage sentinel.
 *
 * The caller must hold the Learning runtime's reset/restore mutex while supplying its checkpoint
 * snapshot. That makes a derived reset unable to race between checkpoint validation and deletion.
 */
class RoomLearningPrimaryOutboxRetentionPort(
    private val database: AppDatabase,
) : LearningPrimaryOutboxRetentionPort {
    override suspend fun pruneOnce(
        request: LearningOutboxRetentionRequest,
    ): LearningOutboxRetentionResult = try {
        database.withTransaction {
            val dao = database.learningOutboxDao()
            val sentinels = dao.listStreamSentinels()
            val streamId = validateLearningOutboxSentinels(sentinels).toString()
            validateLearningOutboxStreams(
                sentinelStreamId = kotlin.uuid.Uuid.parse(streamId),
                distinctStreamIds = dao.listDistinctStreamIds(),
            )
            val head = dao.headSequence(streamId)
                ?.takeIf { it >= sentinels.single().seq && it > 0L }
                ?: return@withTransaction LearningOutboxRetentionResult.AuthorityUnavailable
            when (val decision = LearningOutboxRetentionPlanner.plan(
                LearningOutboxRetentionInput(
                    streamId = streamId,
                    authoritativeHeadSequence = head,
                    frozenNowMs = request.frozenNowMs,
                    checkpoints = request.checkpoints,
                    minimumAgeMs = request.minimumAgeMs,
                    safetyFloorRows = request.safetyFloorRows,
                ),
            )) {
                is LearningOutboxPruneDecision.Unavailable ->
                    LearningOutboxRetentionResult.Unavailable(decision.reason)

                is LearningOutboxPruneDecision.Ready -> {
                    val plan = decision.plan
                    val deleted = dao.deletePrunablePage(
                        streamId = plan.streamId,
                        throughMinConsumerSeq = plan.throughMinConsumerSequence,
                        createdBeforeMs = plan.createdBeforeMs,
                        keepFromSeq = plan.keepFromSequence,
                        limit = request.batchSize,
                    )
                    LearningOutboxRetentionResult.Completed(
                        deletedRows = deleted,
                        workMayRemain = deleted == request.batchSize,
                    )
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: LearningOutboxHealthException) {
        LearningOutboxRetentionResult.AuthorityUnavailable
    } catch (_: IllegalArgumentException) {
        LearningOutboxRetentionResult.AuthorityUnavailable
    } catch (_: IllegalStateException) {
        LearningOutboxRetentionResult.AuthorityUnavailable
    }
}
