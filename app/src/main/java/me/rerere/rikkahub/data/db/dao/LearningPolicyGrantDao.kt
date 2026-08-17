package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantEntity
import me.rerere.rikkahub.data.db.entity.LearningPolicyGrantRevisionEntity

/**
 * Main-database policy-grant authority surface.
 *
 * No method uses REPLACE. Callers append the matching revision in the same AppDatabase
 * transaction after an insert or a successful one-row CAS.
 */
@Dao
interface LearningPolicyGrantDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHead(entity: LearningPolicyGrantEntity)

    @Query("SELECT * FROM learning_policy_grants WHERE grant_id = :grantId LIMIT 1")
    suspend fun findHead(grantId: String): LearningPolicyGrantEntity?

    @Query(
        "SELECT * FROM learning_policy_grants WHERE source_stream_id = :sourceStreamId " +
            "AND scope_kind = :scopeKind " +
            "AND scope_id = :scopeId AND consuming_assistant_id = :consumingAssistantId " +
            "AND policy_id = :policyId " +
            "AND policy_revision = :policyRevision AND artifact_sha256 = :artifactSha256 LIMIT 1",
    )
    suspend fun findExactHead(
        sourceStreamId: String,
        scopeKind: String,
        scopeId: String,
        consumingAssistantId: String,
        policyId: String,
        policyRevision: Long,
        artifactSha256: String,
    ): LearningPolicyGrantEntity?

    /** All consuming Assistants are considered before a shared Policy can be archived. */
    @Query(
        "SELECT COUNT(*) FROM learning_policy_grants WHERE source_stream_id = :sourceStreamId " +
            "AND scope_kind = :scopeKind AND scope_id = :scopeId AND policy_id = :policyId " +
            "AND policy_revision = :policyRevision AND artifact_sha256 = :artifactSha256 " +
            "AND state = 'GRANTED'",
    )
    suspend fun countExactGrantedConsumers(
        sourceStreamId: String,
        scopeKind: String,
        scopeId: String,
        policyId: String,
        policyRevision: Long,
        artifactSha256: String,
    ): Long

    /** Stable keyset paging; every page remains bounded even while another reviewer writes. */
    @Query(
        "SELECT * FROM learning_policy_grants WHERE source_stream_id = :sourceStreamId " +
            "AND scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND consuming_assistant_id = :consumingAssistantId " +
            "AND (updated_at_ms > :afterUpdatedAtMs OR " +
            "(updated_at_ms = :afterUpdatedAtMs AND grant_id > :afterGrantId)) " +
            "ORDER BY updated_at_ms ASC, grant_id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 200 THEN :limit ELSE 0 END",
    )
    suspend fun listScopePage(
        sourceStreamId: String,
        scopeKind: String,
        scopeId: String,
        consumingAssistantId: String,
        afterUpdatedAtMs: Long,
        afterGrantId: String,
        limit: Int,
    ): List<LearningPolicyGrantEntity>

    /** Global maintenance scan across scopes/consumers; lifecycle state is intentionally unfiltered. */
    @Query(
        "SELECT * FROM learning_policy_grants WHERE " +
            "(updated_at_ms > :afterUpdatedAtMs OR " +
            "(updated_at_ms = :afterUpdatedAtMs AND grant_id > :afterGrantId)) " +
            "ORDER BY updated_at_ms ASC, grant_id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 200 THEN :limit ELSE 0 END",
    )
    suspend fun listCurrentPage(
        afterUpdatedAtMs: Long,
        afterGrantId: String,
        limit: Int,
    ): List<LearningPolicyGrantEntity>

    @Query(
        "SELECT * FROM learning_policy_grants WHERE scope_kind = :scopeKind " +
            "AND scope_id = :scopeId AND state = 'GRANTED' " +
            "AND (updated_at_ms > :afterUpdatedAtMs OR " +
            "(updated_at_ms = :afterUpdatedAtMs AND grant_id > :afterGrantId)) " +
            "ORDER BY updated_at_ms ASC, grant_id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 200 THEN :limit ELSE 0 END",
    )
    suspend fun listGrantedScopePage(
        scopeKind: String,
        scopeId: String,
        afterUpdatedAtMs: Long,
        afterGrantId: String,
        limit: Int,
    ): List<LearningPolicyGrantEntity>

    /**
     * Immutable-key replay page for one destroyed second-user epoch. State and timestamps are
     * intentionally unfiltered: an already-revoked head must remain visible after a cross-DB
     * crash so Learning projections can resume.
     */
    @Query(
        "SELECT * FROM learning_policy_grants WHERE scope_kind = 'AUTHORITY_SUBJECT' " +
            "AND scope_id = :authoritySubjectId " +
            "AND consuming_assistant_id = :consumingAssistantId AND grant_id > :afterGrantId " +
            "ORDER BY grant_id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 64 THEN :limit ELSE 0 END",
    )
    suspend fun listSecondUserAuthorityRevocationPage(
        authoritySubjectId: String,
        consumingAssistantId: String,
        afterGrantId: String,
        limit: Int,
    ): List<LearningPolicyGrantEntity>

    @Query(
        "UPDATE learning_policy_grants SET policy_revision = :policyRevision, " +
            "artifact_sha256 = :artifactSha256, actor = 'USER_REVIEW', state = 'GRANTED', " +
            "state_version = :nextStateVersion, granted_at_ms = :grantedAtMs, " +
            "revoked_at_ms = NULL, reason_code = :reasonCode, updated_at_ms = :updatedAtMs " +
            "WHERE grant_id = :grantId AND source_stream_id = :sourceStreamId " +
            "AND scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND consuming_assistant_id = :consumingAssistantId AND policy_id = :policyId " +
            "AND state = 'REVOKED' AND state_version = :expectedStateVersion " +
            "AND :nextStateVersion = :expectedStateVersion + 1 AND :policyRevision > 0 " +
            "AND length(:artifactSha256) = 64 " +
            "AND :artifactSha256 NOT GLOB '*[^0-9a-f]*' " +
            "AND length(:reasonCode) BETWEEN 1 AND 64 " +
            "AND :reasonCode NOT GLOB '*[^A-Z0-9_]*' " +
            "AND :updatedAtMs >= :grantedAtMs AND :grantedAtMs >= created_at_ms",
    )
    suspend fun grantFenced(
        grantId: String,
        sourceStreamId: String,
        scopeKind: String,
        scopeId: String,
        consumingAssistantId: String,
        policyId: String,
        expectedStateVersion: Long,
        nextStateVersion: Long,
        policyRevision: Long,
        artifactSha256: String,
        grantedAtMs: Long,
        reasonCode: String,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE learning_policy_grants SET actor = 'USER_REVIEW', state = 'REVOKED', " +
            "state_version = :nextStateVersion, revoked_at_ms = :revokedAtMs, " +
            "reason_code = :reasonCode, updated_at_ms = :updatedAtMs " +
            "WHERE grant_id = :grantId AND source_stream_id = :sourceStreamId " +
            "AND scope_kind = :scopeKind AND scope_id = :scopeId " +
            "AND consuming_assistant_id = :consumingAssistantId AND policy_id = :policyId " +
            "AND state = 'GRANTED' AND state_version = :expectedStateVersion " +
            "AND :nextStateVersion = :expectedStateVersion + 1 " +
            "AND :revokedAtMs >= granted_at_ms AND :updatedAtMs = :revokedAtMs " +
            "AND length(:reasonCode) BETWEEN 1 AND 64 " +
            "AND :reasonCode NOT GLOB '*[^A-Z0-9_]*'",
    )
    suspend fun revokeFenced(
        grantId: String,
        sourceStreamId: String,
        scopeKind: String,
        scopeId: String,
        consumingAssistantId: String,
        policyId: String,
        expectedStateVersion: Long,
        nextStateVersion: Long,
        revokedAtMs: Long,
        reasonCode: String,
        updatedAtMs: Long,
    ): Int

    /** System-only CAS used by the durable second-user REVOKING saga. */
    @Query(
        "UPDATE learning_policy_grants SET actor = 'AUTHORITY_REVOCATION', " +
            "state = 'REVOKED', state_version = :nextStateVersion, " +
            "revoked_at_ms = :revokedAtMs, reason_code = 'SECOND_USER_AUTHORITY_REVOKED', " +
            "updated_at_ms = :revokedAtMs WHERE grant_id = :grantId " +
            "AND scope_kind = 'AUTHORITY_SUBJECT' AND scope_id = :authoritySubjectId " +
            "AND consuming_assistant_id = :consumingAssistantId AND state = 'GRANTED' " +
            "AND state_version = :expectedStateVersion " +
            "AND :nextStateVersion = :expectedStateVersion + 1 " +
            "AND :revokedAtMs >= granted_at_ms AND :revokedAtMs >= updated_at_ms",
    )
    suspend fun revokeSecondUserAuthorityFenced(
        grantId: String,
        authoritySubjectId: String,
        consumingAssistantId: String,
        expectedStateVersion: Long,
        nextStateVersion: Long,
        revokedAtMs: Long,
    ): Int

    @Query(
        "UPDATE learning_policy_grants SET policy_revision = :policyRevision, " +
            "artifact_sha256 = :artifactSha256, actor = 'USER_REVIEW', " +
            "state_version = :nextStateVersion, reason_code = :reasonCode, " +
            "updated_at_ms = :updatedAtMs WHERE grant_id = :grantId " +
            "AND source_stream_id = :sourceStreamId AND scope_kind = :scopeKind " +
            "AND scope_id = :scopeId AND consuming_assistant_id = :consumingAssistantId " +
            "AND policy_id = :policyId AND state = 'GRANTED' " +
            "AND state_version = :expectedStateVersion " +
            "AND :nextStateVersion = :expectedStateVersion + 1 AND :policyRevision > 0 " +
            "AND length(:artifactSha256) = 64 " +
            "AND :artifactSha256 NOT GLOB '*[^0-9a-f]*' " +
            "AND length(:reasonCode) BETWEEN 1 AND 64 " +
            "AND :reasonCode NOT GLOB '*[^A-Z0-9_]*' " +
            "AND :updatedAtMs >= granted_at_ms",
    )
    suspend fun updateGrantedPolicyFenced(
        grantId: String,
        sourceStreamId: String,
        scopeKind: String,
        scopeId: String,
        consumingAssistantId: String,
        policyId: String,
        expectedStateVersion: Long,
        nextStateVersion: Long,
        policyRevision: Long,
        artifactSha256: String,
        reasonCode: String,
        updatedAtMs: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(entity: LearningPolicyGrantRevisionEntity)

    @Query(
        "SELECT * FROM learning_policy_grant_revisions WHERE grant_id = :grantId " +
            "AND state_version = :stateVersion LIMIT 1",
    )
    suspend fun findRevision(
        grantId: String,
        stateVersion: Long,
    ): LearningPolicyGrantRevisionEntity?

    @Query(
        "SELECT * FROM learning_policy_grant_revisions WHERE grant_id = :grantId " +
            "AND state_version > :afterStateVersion ORDER BY state_version ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 200 THEN :limit ELSE 0 END",
    )
    suspend fun listRevisionPage(
        grantId: String,
        afterStateVersion: Long,
        limit: Int,
    ): List<LearningPolicyGrantRevisionEntity>
}
