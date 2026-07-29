package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.PendingChatCommandEntity

@Dao
interface PendingChatCommandDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(command: PendingChatCommandEntity): Long

    @Query("SELECT * FROM pending_chat_commands WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PendingChatCommandEntity?

    @Query("SELECT * FROM pending_chat_commands WHERE id = :id LIMIT 1")
    fun observeById(id: String): kotlinx.coroutines.flow.Flow<PendingChatCommandEntity?>

    @Query("SELECT * FROM pending_chat_commands WHERE idempotencyKey = :key LIMIT 1")
    suspend fun findByIdempotencyKey(key: String): PendingChatCommandEntity?

    @Query(
        "SELECT * FROM pending_chat_commands WHERE conversationId = :conversationId " +
            "AND state IN ('PENDING', 'INTERRUPTED', 'WAITING_APPROVAL') " +
            "AND (expiresAt IS NULL OR expiresAt > :now) " +
            "ORDER BY priority DESC, sequence ASC LIMIT :limit"
    )
    suspend fun findPending(conversationId: String, now: Long, limit: Int): List<PendingChatCommandEntity>

    @Query(
        "SELECT * FROM pending_chat_commands WHERE state IN ('PENDING', 'INTERRUPTED', 'WAITING_APPROVAL') " +
            "AND (expiresAt IS NULL OR expiresAt > :now) " +
            "ORDER BY priority DESC, sequence ASC LIMIT :limit"
    )
    suspend fun findPendingGlobally(now: Long, limit: Int): List<PendingChatCommandEntity>

    @Query(
        "SELECT * FROM pending_chat_commands WHERE conversationId = :conversationId " +
            "AND dedupeKey = :dedupeKey AND state IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL') " +
            "ORDER BY sequence ASC LIMIT 1"
    )
    suspend fun findActiveByDedupeKey(conversationId: String, dedupeKey: String): PendingChatCommandEntity?

    @Query("SELECT * FROM pending_chat_commands WHERE conversationId = :conversationId ORDER BY sequence ASC")
    fun observe(conversationId: String): Flow<List<PendingChatCommandEntity>>

    @Query("SELECT * FROM pending_chat_commands WHERE state IN ('PENDING', 'INTERRUPTED', 'WAITING_APPROVAL') ORDER BY sequence ASC")
    fun observePending(): Flow<List<PendingChatCommandEntity>>

    @Query("SELECT * FROM pending_chat_commands WHERE state IN ('PENDING', 'INTERRUPTED', 'WAITING_APPROVAL', 'RUNNING')")
    suspend fun listActive(): List<PendingChatCommandEntity>

    @Query(
        "UPDATE pending_chat_commands SET type = :type, payloadJson = :payloadJson " +
            "WHERE id = :id AND state = 'PENDING'"
    )
    suspend fun rewritePendingCommand(id: String, type: String, payloadJson: String): Int

    @Query(
        "UPDATE pending_chat_commands SET state = 'RUNNING', claimedBy = :workerId, " +
            "leaseUntil = :leaseUntil, startedAt = COALESCE(startedAt, :now), attempt = attempt + 1 " +
            "WHERE id = :id AND state IN ('PENDING', 'INTERRUPTED') " +
            "AND (expiresAt IS NULL OR expiresAt > :now)"
    )
    suspend fun claim(id: String, workerId: String, leaseUntil: Long, now: Long): Int

    @Query(
        "UPDATE pending_chat_commands SET leaseUntil = :leaseUntil " +
            "WHERE id = :id AND state = 'RUNNING' AND claimedBy = :workerId"
    )
    suspend fun renewLease(id: String, workerId: String, leaseUntil: Long): Int

    @Query(
        "UPDATE pending_chat_commands SET state = 'INTERRUPTED', claimedBy = NULL, leaseUntil = NULL, " +
            "lastErrorCode = 'LEASE_EXPIRED', lastErrorMessage = :message " +
            "WHERE state = 'RUNNING' AND leaseUntil IS NOT NULL AND leaseUntil < :now"
    )
    suspend fun interruptExpired(now: Long, message: String = "Worker lease expired"): Int

    @Query(
        "UPDATE pending_chat_commands SET state = :state, finishedAt = :finishedAt, " +
            "claimedBy = NULL, leaseUntil = NULL, lastErrorCode = :errorCode, lastErrorMessage = :errorMessage " +
            "WHERE id = :id AND state = :expectedState"
    )
    suspend fun finish(
        id: String,
        state: String,
        finishedAt: Long,
        expectedState: String = "RUNNING",
        errorCode: String? = null,
        errorMessage: String? = null,
    ): Int

    @Query(
        "UPDATE pending_chat_commands SET state = :state, finishedAt = :finishedAt, " +
            "lastErrorCode = :errorCode, lastErrorMessage = :errorMessage " +
            "WHERE id = :id AND state IN ('PENDING', 'INTERRUPTED', 'WAITING_APPROVAL', 'RUNNING')"
    )
    suspend fun resolvePending(
        id: String,
        state: String,
        finishedAt: Long,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM pending_chat_commands WHERE conversationId = :conversationId " +
            "AND state IN ('PENDING', 'INTERRUPTED', 'RUNNING', 'WAITING_APPROVAL')"
    )
    suspend fun countActive(conversationId: String): Int

    @Query("DELETE FROM pending_chat_commands WHERE conversationId = :conversationId AND state = 'PENDING'")
    suspend fun clearPending(conversationId: String): Int
}
