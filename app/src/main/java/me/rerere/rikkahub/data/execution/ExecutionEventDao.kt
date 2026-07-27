package me.rerere.rikkahub.data.execution

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ExecutionEventDao {
    @Insert
    suspend fun insert(event: ExecutionEventRecord)

    @Query("SELECT * FROM execution_events WHERE event_id = :eventId LIMIT 1")
    suspend fun getById(eventId: String): ExecutionEventRecord?

    @Query(
        "SELECT * FROM execution_events WHERE execution_id = :executionId " +
            "ORDER BY sequence DESC LIMIT :limit",
    )
    suspend fun getEvents(executionId: String, limit: Int): List<ExecutionEventRecord>

    @Query(
        "DELETE FROM execution_events WHERE execution_id = :executionId AND event_id NOT IN " +
            "(SELECT event_id FROM execution_events WHERE execution_id = :executionId " +
            "ORDER BY sequence DESC LIMIT :keep)",
    )
    suspend fun trimForExecution(executionId: String, keep: Int): Int
}
