package me.rerere.rikkahub.data.execution

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ExecutionRecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(record: ExecutionRecord): Long

    @Update
    suspend fun update(record: ExecutionRecord)

    @Query("SELECT * FROM execution_records WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ExecutionRecord?

    @Query(
        "SELECT * FROM execution_records WHERE status NOT IN " +
            "('succeeded', 'failed', 'cancelled', 'timed_out', 'orphaned', 'unknown') " +
            "ORDER BY updated_at_ms ASC",
    )
    suspend fun getInFlight(): List<ExecutionRecord>

    @Query(
        "SELECT * FROM execution_records WHERE idempotency_key = :idempotencyKey " +
            "ORDER BY updated_at_ms DESC LIMIT 1",
    )
    suspend fun getLatestByIdempotencyKey(idempotencyKey: String): ExecutionRecord?

    @Query("SELECT * FROM execution_records ORDER BY updated_at_ms DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ExecutionRecord>>

    @Query("SELECT * FROM execution_records ORDER BY updated_at_ms DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ExecutionRecord>
}
