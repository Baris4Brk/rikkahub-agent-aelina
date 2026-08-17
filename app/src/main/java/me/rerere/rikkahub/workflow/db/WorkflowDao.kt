package me.rerere.rikkahub.workflow.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkflowDao {

    @Query("SELECT * FROM workflows ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<WorkflowEntity>>

    @Query("SELECT * FROM workflows ORDER BY name COLLATE NOCASE ASC")
    suspend fun listAll(): List<WorkflowEntity>

    @Query("SELECT * FROM workflows WHERE enabled = 1 ORDER BY name COLLATE NOCASE ASC")
    suspend fun listEnabled(): List<WorkflowEntity>

    @Query("SELECT * FROM workflows WHERE id = :id")
    suspend fun getById(id: String): WorkflowEntity?

    /**
     * Bounded privacy scan over LEARNED definitions that still require quarantine. A tombstone is
     * excluded only when every content-bearing/projection field is already in the canonical
     * sanitized state; a marker alone is never accepted as proof of redaction. USER rows never
     * enter this authority path.
     */
    @Query("""
        SELECT * FROM workflows
        WHERE origin = 'LEARNED'
          AND id > :afterIdExclusive
          AND (
            staleReason IS NULL OR staleReason NOT IN (
              'learning_scope_erased_definition_v1',
              'learning_scope_erased_claim_v1'
            ) OR enabled != 0
              OR name != 'Erased learned workflow'
              OR description IS NOT NULL
              OR definitionJson != '{}'
              OR createdAtMs != 0
              OR updatedAtMs < 0
              OR lastRunAtMs IS NOT NULL
              OR lastRunStatus IS NOT NULL
              OR lastRunError IS NOT NULL
              OR runsTodayCount != 0
              OR runsTodayDate != ''
              OR sourceArtifactHash IS NOT NULL
              OR grantDigest IS NOT NULL
              OR authoringAssistantId IS NOT NULL
              OR capabilitySnapshotJson != '[]'
              OR toolSchemaFingerprintsJson != '[]'
          )
        ORDER BY id ASC
        LIMIT CASE WHEN :limit BETWEEN 1 AND 128 THEN :limit ELSE 0 END
    """)
    suspend fun listLiveLearnedPrivacyPage(
        afterIdExclusive: String,
        limit: Int,
    ): List<WorkflowEntity>

    @Query("SELECT * FROM workflows WHERE id = :id")
    fun observeById(id: String): Flow<WorkflowEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: WorkflowEntity)

    /** Generic body update: optimistic CAS and deliberately does not modify enabled. */
    @Query("""
        UPDATE workflows
        SET name = :name,
            description = :description,
            definitionJson = :definitionJson,
            updatedAtMs = :updatedAtMs,
            origin = :origin,
            sourceCandidateId = :sourceCandidateId,
            sourceArtifactHash = :sourceArtifactHash,
            grantDigest = :grantDigest,
            authoringAssistantId = :authoringAssistantId,
            capabilitySnapshotJson = :capabilitySnapshotJson,
            toolSchemaFingerprintsJson = :toolSchemaFingerprintsJson,
            staleReason = NULL,
            stateVersion = stateVersion + 1
        WHERE id = :id AND stateVersion = :expectedStateVersion
          AND (staleReason IS NULL OR staleReason NOT IN (
            'learning_scope_erased_definition_v1',
            'learning_scope_erased_claim_v1'
          ))
    """)
    suspend fun updateDefinitionCas(
        id: String,
        expectedStateVersion: Long,
        name: String,
        description: String?,
        definitionJson: String,
        updatedAtMs: Long,
        origin: String,
        sourceCandidateId: String?,
        sourceArtifactHash: String?,
        grantDigest: String?,
        authoringAssistantId: String?,
        capabilitySnapshotJson: String,
        toolSchemaFingerprintsJson: String,
    ): Int

    /** Exact-scope erase tombstones are permanent cross-database fences, not UI definitions. */
    @Query("""
        DELETE FROM workflows
        WHERE id = :id
          AND (origin != 'LEARNED' OR staleReason IS NULL OR staleReason NOT IN (
            'learning_scope_erased_definition_v1',
            'learning_scope_erased_claim_v1'
          ))
    """)
    suspend fun deleteById(id: String): Int

    /**
     * Privacy-only redaction. The exact LEARNED provenance and state version are both fenced so a
     * USER row or unrelated learned row can never be rewritten by an erase request.
     */
    @Query("""
        UPDATE workflows
        SET name = :name,
            description = NULL,
            enabled = 0,
            definitionJson = :definitionJson,
            createdAtMs = 0,
            updatedAtMs = :updatedAtMs,
            lastRunAtMs = NULL,
            lastRunStatus = NULL,
            lastRunError = NULL,
            runsTodayCount = 0,
            runsTodayDate = '',
            stateVersion = stateVersion + 1,
            sourceArtifactHash = NULL,
            grantDigest = NULL,
            authoringAssistantId = NULL,
            capabilitySnapshotJson = '[]',
            toolSchemaFingerprintsJson = '[]',
            staleReason = :staleReason
        WHERE id = :id
          AND origin = 'LEARNED'
          AND sourceCandidateId = :candidateId
          AND stateVersion = :expectedStateVersion
    """)
    suspend fun redactExactLearnedForScopeErase(
        id: String,
        candidateId: String,
        expectedStateVersion: Long,
        name: String,
        definitionJson: String,
        staleReason: String,
        updatedAtMs: Long,
    ): Int

    /**
     * Privacy-only fail-closed redaction used by durable scope scans and derived resets. Unlike
     * [redactExactLearnedForScopeErase], this path intentionally accepts corrupt/missing
     * candidate projections because such a row has already lost the provenance needed for an
     * exact comparison. The id/state/origin CAS still prevents rewriting USER authority.
     */
    @Query("""
        UPDATE workflows
        SET name = :name,
            description = NULL,
            enabled = 0,
            definitionJson = :definitionJson,
            createdAtMs = 0,
            updatedAtMs = :updatedAtMs,
            lastRunAtMs = NULL,
            lastRunStatus = NULL,
            lastRunError = NULL,
            runsTodayCount = 0,
            runsTodayDate = '',
            stateVersion = stateVersion + 1,
            sourceArtifactHash = NULL,
            grantDigest = NULL,
            authoringAssistantId = NULL,
            capabilitySnapshotJson = '[]',
            toolSchemaFingerprintsJson = '[]',
            staleReason = :staleReason
        WHERE id = :id
          AND origin = 'LEARNED'
          AND stateVersion = :expectedStateVersion
          AND (
            staleReason IS NULL OR staleReason NOT IN (
              'learning_scope_erased_definition_v1',
              'learning_scope_erased_claim_v1'
            ) OR enabled != 0
              OR name != 'Erased learned workflow'
              OR description IS NOT NULL
              OR definitionJson != '{}'
              OR createdAtMs != 0
              OR updatedAtMs < 0
              OR lastRunAtMs IS NOT NULL
              OR lastRunStatus IS NOT NULL
              OR lastRunError IS NOT NULL
              OR runsTodayCount != 0
              OR runsTodayDate != ''
              OR sourceArtifactHash IS NOT NULL
              OR grantDigest IS NOT NULL
              OR authoringAssistantId IS NOT NULL
              OR capabilitySnapshotJson != '[]'
              OR toolSchemaFingerprintsJson != '[]'
          )
    """)
    suspend fun redactLearnedForPrivacyQuarantineCas(
        id: String,
        expectedStateVersion: Long,
        name: String,
        definitionJson: String,
        staleReason: String,
        updatedAtMs: Long,
    ): Int

    @Query("""
        UPDATE workflows
        SET enabled = :enabled,
            definitionJson = :definitionJson,
            updatedAtMs = :updatedAtMs,
            staleReason = CASE WHEN :enabled = 1 THEN NULL ELSE staleReason END,
            stateVersion = stateVersion + 1
        WHERE id = :id AND stateVersion = :expectedStateVersion
          AND (staleReason IS NULL OR staleReason NOT IN (
            'learning_scope_erased_definition_v1',
            'learning_scope_erased_claim_v1'
          ))
    """)
    suspend fun setEnabledCas(
        id: String,
        expectedStateVersion: Long,
        enabled: Boolean,
        definitionJson: String,
        updatedAtMs: Long,
    ): Int

    @Query("""
        UPDATE workflows
        SET enabled = 0,
            definitionJson = :definitionJson,
            updatedAtMs = :updatedAtMs,
            staleReason = :reason,
            stateVersion = stateVersion + 1
        WHERE id = :id AND stateVersion = :expectedStateVersion AND origin = 'LEARNED'
          AND (staleReason IS NULL OR staleReason NOT IN (
            'learning_scope_erased_definition_v1',
            'learning_scope_erased_claim_v1'
          ))
    """)
    suspend fun disableLearnedAsStaleCas(
        id: String,
        expectedStateVersion: Long,
        definitionJson: String,
        reason: String,
        updatedAtMs: Long,
    ): Int

    /** Corrupt/missing JSON snapshots cannot be safely re-encoded; the trigger projection is
     * still atomically disabled under CAS so no executable surface can observe the row. */
    @Query("""
        UPDATE workflows
        SET enabled = 0,
            updatedAtMs = :updatedAtMs,
            staleReason = :reason,
            stateVersion = stateVersion + 1
        WHERE id = :id AND stateVersion = :expectedStateVersion AND origin = 'LEARNED'
          AND (staleReason IS NULL OR staleReason NOT IN (
            'learning_scope_erased_definition_v1',
            'learning_scope_erased_claim_v1'
          ))
    """)
    suspend fun disableInvalidLearnedCas(
        id: String,
        expectedStateVersion: Long,
        reason: String,
        updatedAtMs: Long,
    ): Int

    @Query("""
        UPDATE workflows
        SET lastRunAtMs = :firedAtMs,
            lastRunStatus = :status,
            lastRunError = :errorMessage,
            runsTodayCount = :runsTodayCount,
            runsTodayDate = :runsTodayDate
        WHERE id = :id
          AND (staleReason IS NULL OR staleReason NOT IN (
            'learning_scope_erased_definition_v1',
            'learning_scope_erased_claim_v1'
          ))
    """)
    suspend fun recordFire(
        id: String,
        firedAtMs: Long,
        status: String,
        errorMessage: String?,
        runsTodayCount: Int,
        runsTodayDate: String,
    ): Int
}
