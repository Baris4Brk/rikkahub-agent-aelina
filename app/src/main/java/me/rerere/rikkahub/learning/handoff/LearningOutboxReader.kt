package me.rerere.rikkahub.learning.handoff

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.LearningOutboxDao
import me.rerere.rikkahub.data.db.entity.LearningOutboxEntity
import me.rerere.rikkahub.learning.model.LearningEventType
import kotlin.uuid.Uuid

private const val MAX_OUTBOX_BATCH = 64

data class LearningOutboxDescriptor(
    val streamId: Uuid,
    val headSequence: Long,
) {
    init {
        require(headSequence > 0L) { "Outbox descriptor requires a positive head" }
    }
}

enum class LearningOutboxHealthError {
    MISSING_STREAM_SENTINEL,
    MULTIPLE_STREAM_SENTINELS,
    MIXED_STREAMS,
    INVALID_STREAM_SENTINEL,
    HEAD_REWIND,
    MALFORMED_EVENT,
}

class LearningOutboxHealthException(
    val errorCode: LearningOutboxHealthError,
) : IllegalStateException("Learning outbox is not safe to consume: $errorCode")

interface LearningOutboxReader {
    suspend fun inspect(): LearningOutboxDescriptor

    suspend fun readAfterThrough(
        descriptor: LearningOutboxDescriptor,
        afterSequence: Long,
        limit: Int = MAX_OUTBOX_BATCH,
    ): List<LearningHandoffEvent>
}

/** Read-only adapter; authority writes remain in their owning transactions. */
class RoomLearningOutboxReader(
    private val database: AppDatabase,
    private val dao: LearningOutboxDao = database.learningOutboxDao(),
) : LearningOutboxReader {
    override suspend fun inspect(): LearningOutboxDescriptor = database.withTransaction {
        inspectInCurrentSnapshot()
    }

    override suspend fun readAfterThrough(
        descriptor: LearningOutboxDescriptor,
        afterSequence: Long,
        limit: Int,
    ): List<LearningHandoffEvent> {
        require(afterSequence >= 0L) { "Negative outbox checkpoint" }
        require(limit in 1..MAX_OUTBOX_BATCH) { "Unsafe outbox batch size" }
        return database.withTransaction {
            val current = inspectInCurrentSnapshot()
            if (current.streamId != descriptor.streamId) {
                throw LearningOutboxHealthException(LearningOutboxHealthError.MIXED_STREAMS)
            }
            if (current.headSequence < descriptor.headSequence) {
                throw LearningOutboxHealthException(LearningOutboxHealthError.HEAD_REWIND)
            }
            if (afterSequence > descriptor.headSequence) {
                throw LearningOutboxHealthException(LearningOutboxHealthError.HEAD_REWIND)
            }
            decodeRows(
                dao.listAfterThrough(
                    streamId = descriptor.streamId.toString(),
                    afterSeq = afterSequence,
                    throughSeq = descriptor.headSequence,
                    limit = limit,
                ),
                expectedStreamId = descriptor.streamId,
                afterSequence = afterSequence,
                throughSequence = descriptor.headSequence,
            )
        }
    }

    private suspend fun inspectInCurrentSnapshot(): LearningOutboxDescriptor {
        val sentinels = try {
            dao.listStreamSentinels()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            throw LearningOutboxHealthException(LearningOutboxHealthError.INVALID_STREAM_SENTINEL)
        }
        val streamId = validateLearningOutboxSentinels(sentinels)
        val distinctStreams = try {
            dao.listDistinctStreamIds()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            throw LearningOutboxHealthException(LearningOutboxHealthError.MIXED_STREAMS)
        }
        validateLearningOutboxStreams(streamId, distinctStreams)
        val head = dao.headSequence(streamId.toString())
            ?: throw LearningOutboxHealthException(
                LearningOutboxHealthError.INVALID_STREAM_SENTINEL,
            )
        if (head <= 0L || head < sentinels.single().seq) {
            throw LearningOutboxHealthException(LearningOutboxHealthError.INVALID_STREAM_SENTINEL)
        }
        return LearningOutboxDescriptor(streamId, head)
    }
}

internal fun validateLearningOutboxSentinels(
    sentinels: List<LearningOutboxEntity>,
): Uuid {
    if (sentinels.isEmpty()) {
        throw LearningOutboxHealthException(LearningOutboxHealthError.MISSING_STREAM_SENTINEL)
    }
    if (sentinels.size != 1) {
        throw LearningOutboxHealthException(LearningOutboxHealthError.MULTIPLE_STREAM_SENTINELS)
    }
    val decoded = LearningOutboxRowDecoder.decode(sentinels.single())
    val sentinel = (decoded as? LearningOutboxDecodeResult.Valid)?.event
        ?: throw LearningOutboxHealthException(LearningOutboxHealthError.INVALID_STREAM_SENTINEL)
    if (sentinel.eventCode.knownType != LearningEventType.STREAM_INIT) {
        throw LearningOutboxHealthException(LearningOutboxHealthError.INVALID_STREAM_SENTINEL)
    }
    return sentinel.streamId
}

internal fun validateLearningOutboxStreams(
    sentinelStreamId: Uuid,
    distinctStreamIds: List<String>,
) {
    val onlyStream = distinctStreamIds.singleOrNull()
        ?: throw LearningOutboxHealthException(LearningOutboxHealthError.MIXED_STREAMS)
    if (onlyStream != sentinelStreamId.toString()) {
        throw LearningOutboxHealthException(LearningOutboxHealthError.MIXED_STREAMS)
    }
}

internal fun decodeRows(
    rows: List<LearningOutboxEntity>,
    expectedStreamId: Uuid,
    afterSequence: Long,
    throughSequence: Long,
): List<LearningHandoffEvent> {
    var previousSequence = afterSequence
    return rows.map { row ->
        if (
            row.streamId != expectedStreamId.toString() ||
            row.seq <= previousSequence ||
            row.seq > throughSequence
        ) {
            throw LearningOutboxHealthException(LearningOutboxHealthError.MALFORMED_EVENT)
        }
        when (val decoded = LearningOutboxRowDecoder.decode(row)) {
            is LearningOutboxDecodeResult.Valid -> decoded.event.also { event ->
                if (event.streamId != expectedStreamId || event.outboxSeq != row.seq) {
                    throw LearningOutboxHealthException(LearningOutboxHealthError.MALFORMED_EVENT)
                }
                previousSequence = event.outboxSeq
            }
            is LearningOutboxDecodeResult.Invalid ->
                throw LearningOutboxHealthException(LearningOutboxHealthError.MALFORMED_EVENT)
        }
    }
}
