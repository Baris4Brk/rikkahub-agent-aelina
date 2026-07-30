package me.rerere.rikkahub.toolcatalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolShortcutDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ToolShortcutEntity): Long

    @Query("SELECT * FROM tool_shortcuts WHERE shortcut_id = :id LIMIT 1")
    suspend fun get(id: String): ToolShortcutEntity?

    @Query(
        "SELECT * FROM tool_shortcuts WHERE authority_subject_id = :subjectId " +
            "AND tool_name = :toolName AND schema_fingerprint = :fingerprint LIMIT 1",
    )
    suspend fun getBySignature(
        subjectId: String,
        toolName: String,
        fingerprint: String,
    ): ToolShortcutEntity?

    @Query(
        "SELECT * FROM tool_shortcuts WHERE authority_subject_id = :subjectId " +
            "AND state = 'ACTIVE' ORDER BY last_used_at_ms DESC, model_confirmed_at_ms DESC, " +
            "use_count DESC, tool_name ASC LIMIT :limit",
    )
    suspend fun listActive(subjectId: String, limit: Int): List<ToolShortcutEntity>

    @Query(
        "SELECT * FROM tool_shortcuts WHERE authority_subject_id = :subjectId " +
            "ORDER BY updated_at_ms DESC, tool_name ASC LIMIT :limit",
    )
    fun observeLibrary(subjectId: String, limit: Int): Flow<List<ToolShortcutEntity>>

    @Query(
        "SELECT * FROM tool_shortcuts WHERE authority_subject_id = :subjectId " +
            "ORDER BY updated_at_ms DESC LIMIT :limit",
    )
    suspend fun listForDiagnostics(subjectId: String, limit: Int): List<ToolShortcutEntity>

    @Query(
        "UPDATE tool_shortcuts SET state = :state, updated_at_ms = :nowMs, " +
            "state_version = state_version + 1 WHERE shortcut_id = :id " +
            "AND authority_subject_id = :subjectId AND state_version = :expectedVersion",
    )
    suspend fun setState(
        id: String,
        subjectId: String,
        expectedVersion: Long,
        state: String,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE tool_shortcuts SET last_used_at_ms = :nowMs, use_count = use_count + 1, " +
            "updated_at_ms = :nowMs, state_version = state_version + 1 " +
            "WHERE shortcut_id = :id AND authority_subject_id = :subjectId " +
            "AND state = 'ACTIVE' AND state_version = :expectedVersion",
    )
    suspend fun markUsed(
        id: String,
        subjectId: String,
        expectedVersion: Long,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE tool_shortcuts SET state = 'STALE_SCHEMA', updated_at_ms = :nowMs, " +
            "state_version = state_version + 1 WHERE shortcut_id = :id AND state = 'ACTIVE' " +
            "AND state_version = :expectedVersion",
    )
    suspend fun markStaleSchema(id: String, expectedVersion: Long, nowMs: Long): Int

    @Query(
        "UPDATE tool_shortcuts SET state = 'STALE_AUTHORITY', updated_at_ms = :nowMs, " +
            "state_version = state_version + 1 WHERE authority_subject_id IN (:subjectIds) " +
            "AND state IN ('ACTIVE', 'DISABLED', 'STALE_SCHEMA')",
    )
    suspend fun staleAuthoritySubjects(subjectIds: List<String>, nowMs: Long): Int
}
