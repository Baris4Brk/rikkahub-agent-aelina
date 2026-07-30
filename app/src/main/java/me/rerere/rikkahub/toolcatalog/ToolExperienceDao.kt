package me.rerere.rikkahub.toolcatalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolExperienceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertExperience(entity: ToolExperienceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvidence(entity: ToolExperienceEvidenceEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(entity: ToolExperienceRevisionEntity)

    @Query("SELECT * FROM tool_experiences WHERE experience_id = :id LIMIT 1")
    suspend fun get(id: String): ToolExperienceEntity?

    @Query(
        "SELECT * FROM tool_experiences WHERE authority_subject_id = :subjectId " +
            "AND primary_tool_name = :toolName AND schema_fingerprint = :fingerprint LIMIT 1",
    )
    suspend fun getBySignature(
        subjectId: String,
        toolName: String,
        fingerprint: String,
    ): ToolExperienceEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM tool_experience_evidence WHERE execution_id = :executionId)")
    suspend fun hasEvidenceForExecution(executionId: String): Boolean

    @Query(
        "SELECT * FROM tool_experiences WHERE authority_subject_id = :subjectId " +
            "AND primary_tool_name IN (:toolNames) AND state = 'ACTIVE' " +
            "ORDER BY CASE confidence WHEN 'VERIFIED' THEN 0 ELSE 1 END, updated_at_ms DESC LIMIT :limit",
    )
    suspend fun findActiveForTools(
        subjectId: String,
        toolNames: List<String>,
        limit: Int,
    ): List<ToolExperienceEntity>

    @Query(
        "SELECT * FROM tool_experiences WHERE authority_subject_id = :subjectId " +
            "AND state != 'SOFT_DELETED' ORDER BY updated_at_ms DESC LIMIT :limit",
    )
    fun observeLibrary(subjectId: String, limit: Int): Flow<List<ToolExperienceEntity>>

    @Query("SELECT * FROM tool_experience_revisions WHERE experience_id = :experienceId ORDER BY revision DESC")
    fun observeRevisions(experienceId: String): Flow<List<ToolExperienceRevisionEntity>>

    @Query("SELECT * FROM tool_experience_evidence WHERE experience_id = :experienceId ORDER BY created_at_ms DESC")
    fun observeEvidence(experienceId: String): Flow<List<ToolExperienceEvidenceEntity>>

    @Query(
        "SELECT * FROM tool_experiences WHERE authority_subject_id = :subjectId " +
            "ORDER BY updated_at_ms DESC LIMIT :limit",
    )
    suspend fun listForDiagnostics(subjectId: String, limit: Int): List<ToolExperienceEntity>

    @Query(
        "UPDATE tool_experiences SET title = :title, body = :body, tags_json = :tagsJson, " +
            "updated_at_ms = :nowMs, state_version = state_version + 1 " +
            "WHERE experience_id = :id AND authority_subject_id = :subjectId " +
            "AND state IN ('ACTIVE', 'DISABLED', 'STALE_SCHEMA') AND state_version = :expectedVersion",
    )
    suspend fun updateEditable(
        id: String,
        subjectId: String,
        expectedVersion: Long,
        title: String,
        body: String,
        tagsJson: String,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE tool_experiences SET confidence = :confidence, last_observed_at_ms = :observedAtMs, " +
            "last_verified_at_ms = :verifiedAtMs, updated_at_ms = :nowMs, " +
            "state_version = state_version + 1 WHERE experience_id = :id AND state_version = :expectedVersion",
    )
    suspend fun touchSuccess(
        id: String,
        expectedVersion: Long,
        confidence: String,
        observedAtMs: Long,
        verifiedAtMs: Long?,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE tool_experiences SET state = 'ACTIVE', confidence = :confidence, " +
            "last_observed_at_ms = :observedAtMs, last_verified_at_ms = :verifiedAtMs, " +
            "deleted_at_ms = NULL, updated_at_ms = :nowMs, state_version = state_version + 1 " +
            "WHERE experience_id = :id AND state_version = :expectedVersion",
    )
    suspend fun reactivate(
        id: String,
        expectedVersion: Long,
        confidence: String,
        observedAtMs: Long,
        verifiedAtMs: Long?,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE tool_experiences SET state = :state, deleted_at_ms = :deletedAtMs, " +
            "updated_at_ms = :nowMs, state_version = state_version + 1 " +
            "WHERE experience_id = :id AND authority_subject_id = :subjectId AND state_version = :expectedVersion",
    )
    suspend fun setState(
        id: String,
        subjectId: String,
        expectedVersion: Long,
        state: String,
        deletedAtMs: Long?,
        nowMs: Long,
    ): Int

    @Query(
        "UPDATE tool_experiences SET state = 'STALE_AUTHORITY', updated_at_ms = :nowMs, " +
            "state_version = state_version + 1 WHERE authority_subject_id != :currentSubject " +
            "AND state IN ('ACTIVE', 'DISABLED', 'STALE_SCHEMA')",
    )
    suspend fun staleOldAuthorities(currentSubject: String, nowMs: Long): Int

    @Query(
        "UPDATE tool_experiences SET state = 'STALE_AUTHORITY', updated_at_ms = :nowMs, " +
            "state_version = state_version + 1 WHERE authority_subject_id IN (:subjectIds) " +
            "AND state IN ('ACTIVE', 'DISABLED', 'STALE_SCHEMA')",
    )
    suspend fun staleAuthoritySubjects(subjectIds: List<String>, nowMs: Long): Int

    @Query(
        "UPDATE tool_experiences SET state = 'STALE_SCHEMA', updated_at_ms = :nowMs, " +
            "state_version = state_version + 1 WHERE authority_subject_id = :subjectId " +
            "AND primary_tool_name = :toolName AND schema_fingerprint != :fingerprint " +
            "AND state IN ('ACTIVE', 'DISABLED')",
    )
    suspend fun staleOtherFingerprints(
        subjectId: String,
        toolName: String,
        fingerprint: String,
        nowMs: Long,
    ): Int

    @Query("DELETE FROM tool_experience_revisions WHERE experience_id = :experienceId AND revision <= :throughRevision")
    suspend fun deleteRevisionsThrough(experienceId: String, throughRevision: Long): Int

    @Query(
        "SELECT revision FROM tool_experience_revisions WHERE experience_id = :experienceId " +
            "ORDER BY revision DESC LIMIT 1 OFFSET :offset",
    )
    suspend fun revisionAtOffset(experienceId: String, offset: Int): Long?

    @Query("DELETE FROM tool_experiences WHERE state = 'SOFT_DELETED' AND deleted_at_ms IS NOT NULL AND deleted_at_ms <= :cutoffMs")
    suspend fun purgeSoftDeleted(cutoffMs: Long): Int
}
