package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.LearningOutboxEntity

@Dao
interface LearningOutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(event: LearningOutboxEntity): Long

    @Query("SELECT * FROM learning_outbox WHERE event_id = :eventId LIMIT 1")
    suspend fun findByEventId(eventId: String): LearningOutboxEntity?

    @Query(
        "SELECT * FROM learning_outbox WHERE stream_id = :streamId AND seq > :afterSeq " +
            "ORDER BY seq ASC LIMIT :limit",
    )
    suspend fun listAfter(streamId: String, afterSeq: Long, limit: Int): List<LearningOutboxEntity>

    @Query(
        "SELECT * FROM learning_outbox WHERE stream_id = :streamId " +
            "AND seq > :afterSeq AND seq <= :throughSeq ORDER BY seq ASC LIMIT :limit",
    )
    suspend fun listAfterThrough(
        streamId: String,
        afterSeq: Long,
        throughSeq: Long,
        limit: Int,
    ): List<LearningOutboxEntity>

    @Query("SELECT MAX(seq) FROM learning_outbox WHERE stream_id = :streamId")
    suspend fun headSequence(streamId: String): Long?

    @Query("SELECT * FROM learning_outbox WHERE event_type = 'STREAM_INIT' LIMIT 2")
    suspend fun listStreamSentinels(): List<LearningOutboxEntity>

    /** Two values are enough to prove that the append-only log has mixed database lineages. */
    @Query("SELECT DISTINCT stream_id FROM learning_outbox LIMIT 2")
    suspend fun listDistinctStreamIds(): List<String>

    /**
     * P5 bounded three-gate pruning. The caller supplies the minimum contiguous position across
     * every registered durable consumer, an age cutoff and a head-relative safety floor. Keeping
     * the STREAM_INIT sentinel is also enforced in SQL so a faulty caller cannot remove lineage.
     */
    @Query(
        "DELETE FROM learning_outbox WHERE seq IN (" +
            "SELECT seq FROM learning_outbox WHERE stream_id = :streamId " +
            "AND event_type != 'STREAM_INIT' AND seq <= :throughMinConsumerSeq " +
            "AND seq < :keepFromSeq AND created_at_ms < :createdBeforeMs " +
            "ORDER BY seq ASC LIMIT :limit)",
    )
    suspend fun deletePrunablePage(
        streamId: String,
        throughMinConsumerSeq: Long,
        createdBeforeMs: Long,
        keepFromSeq: Long,
        limit: Int,
    ): Int
}
