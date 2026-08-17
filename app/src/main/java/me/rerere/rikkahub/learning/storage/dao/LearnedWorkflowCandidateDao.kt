package me.rerere.rikkahub.learning.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateEntity
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionActor
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionEntity
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionReason
import me.rerere.rikkahub.learning.storage.entity.toRevisionEntity

@Dao
interface LearnedWorkflowCandidateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCandidate(entity: LearnedWorkflowCandidateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(entity: LearnedWorkflowCandidateRevisionEntity)

    @Query("SELECT * FROM learned_workflow_candidates WHERE id = :id LIMIT 1")
    suspend fun find(id: String): LearnedWorkflowCandidateEntity?

    @Query(
        "SELECT * FROM learned_workflow_candidate_revisions WHERE candidate_id = :candidateId " +
            "AND state_version = :stateVersion LIMIT 1",
    )
    suspend fun findRevision(
        candidateId: String,
        stateVersion: Long,
    ): LearnedWorkflowCandidateRevisionEntity?

    /**
     * Source invalidation is an execution barrier, not merely background maintenance. A terminal
     * DEAD_LETTER is blocking too: only the same-transaction DONE receipt proves that the derived
     * source/Policy/candidate invalidation committed for this replay.
     */
    @Query(
        "SELECT COUNT(*) FROM learning_jobs WHERE stream_id = :streamId " +
            "AND replay_generation = :replayGeneration " +
            "AND job_type = 'INVALIDATE_SOURCE_V1' AND state != 'DONE'",
    )
    suspend fun countBlockingSourceInvalidationJobs(
        streamId: String,
        replayGeneration: Long,
    ): Long

    /** Rows are repeatedly drained in one outer transaction, so no unbounded ID list is held. */
    @Query(
        "SELECT * FROM learned_workflow_candidates WHERE source_policy_id = :sourcePolicyId " +
            "AND state IN ('PROPOSED','VALIDATING','VERIFIED','PROMOTING'," +
            "'PROMOTED_DISABLED') ORDER BY id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 128 THEN :limit ELSE 0 END",
    )
    suspend fun listSourceInvalidationCandidates(
        sourcePolicyId: String,
        limit: Int,
    ): List<LearnedWorkflowCandidateEntity>

    @Query(
        "SELECT * FROM learned_workflow_candidates WHERE assistant_id = :assistantId " +
            "AND authority_subject_id IS :authoritySubjectId " +
            "AND (updated_at_ms < :beforeUpdatedAtMs OR " +
            "(updated_at_ms = :beforeUpdatedAtMs AND id < :beforeId)) " +
            "ORDER BY updated_at_ms DESC, id DESC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 100 THEN :limit ELSE 0 END",
    )
    suspend fun listScopePage(
        assistantId: String,
        authoritySubjectId: String?,
        beforeUpdatedAtMs: Long,
        beforeId: String,
        limit: Int,
    ): List<LearnedWorkflowCandidateEntity>

    /** Review UI spans Assistant and AuthoritySubject candidates authored by this Assistant. */
    @Query(
        "SELECT * FROM learned_workflow_candidates WHERE assistant_id = :assistantId " +
            "AND (updated_at_ms < :beforeUpdatedAtMs OR " +
            "(updated_at_ms = :beforeUpdatedAtMs AND id < :beforeId)) " +
            "ORDER BY updated_at_ms DESC, id DESC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 100 THEN :limit ELSE 0 END",
    )
    suspend fun listAssistantPage(
        assistantId: String,
        beforeUpdatedAtMs: Long,
        beforeId: String,
        limit: Int,
    ): List<LearnedWorkflowCandidateEntity>

    @Query(
        "SELECT * FROM learned_workflow_candidate_revisions WHERE candidate_id = :candidateId " +
            "AND state_version < :beforeStateVersion ORDER BY state_version DESC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 100 THEN :limit ELSE 0 END",
    )
    suspend fun listRevisionPage(
        candidateId: String,
        beforeStateVersion: Long,
        limit: Int,
    ): List<LearnedWorkflowCandidateRevisionEntity>

    /** Any artifact edit creates a new candidate version and invalidates every old verifier bit. */
    @Query(
        "UPDATE learned_workflow_candidates SET " +
            "candidate_version = :nextCandidateVersion, state_version = :nextStateVersion, " +
            "state = 'PROPOSED', positive_anchor_evidence_id = :positiveAnchorEvidenceId, " +
            "evidence_ids_wire = :evidenceIdsWire, " +
            "canonical_template_json = :canonicalTemplateJson, typed_slots_wire = :typedSlotsWire, " +
            "capability_snapshot_wire = :capabilitySnapshotWire, " +
            "tool_schema_fingerprints_wire = :toolSchemaFingerprintsWire, " +
            "max_output_utf8_bytes = :maxOutputUtf8Bytes, artifact_sha256 = :artifactSha256, " +
            "verification_report_wire = NULL, verified_at_ms = NULL, archived_at_ms = NULL, " +
            "updated_at_ms = :updatedAtMs WHERE id = :id " +
            "AND candidate_version = :expectedCandidateVersion " +
            "AND state_version = :expectedStateVersion " +
            "AND artifact_sha256 = :expectedArtifactSha256 " +
            "AND :nextCandidateVersion = :expectedCandidateVersion + 1 " +
            "AND :nextStateVersion = :expectedStateVersion + 1 " +
            "AND state IN ('PROPOSED','VALIDATING','VERIFIED','REJECTED','STALE_SCHEMA'," +
            "'STALE_AUTHORITY','ARCHIVED') AND :updatedAtMs >= updated_at_ms",
    )
    suspend fun editArtifactFencedRaw(
        id: String,
        expectedCandidateVersion: Long,
        expectedStateVersion: Long,
        expectedArtifactSha256: String,
        nextCandidateVersion: Long,
        nextStateVersion: Long,
        positiveAnchorEvidenceId: String,
        evidenceIdsWire: String,
        canonicalTemplateJson: String,
        typedSlotsWire: String,
        capabilitySnapshotWire: String,
        toolSchemaFingerprintsWire: String,
        maxOutputUtf8Bytes: Int,
        artifactSha256: String,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE learned_workflow_candidates SET state = :nextState, " +
            "state_version = :nextStateVersion, verification_report_wire = :verificationReportWire, " +
            "verified_at_ms = :verifiedAtMs, archived_at_ms = :archivedAtMs, " +
            "updated_at_ms = :updatedAtMs WHERE id = :id " +
            "AND candidate_version = :expectedCandidateVersion " +
            "AND state_version = :expectedStateVersion AND state = :expectedState " +
            "AND artifact_sha256 = :expectedArtifactSha256 " +
            "AND source_grant_digest = :expectedSourceGrantDigest " +
            "AND tool_schema_fingerprints_wire = :expectedToolSchemaFingerprintsWire " +
            "AND verifier_version = :expectedVerifierVersion " +
            "AND assistant_id = :expectedAssistantId " +
            "AND authority_subject_id IS :expectedAuthoritySubjectId " +
            "AND :nextStateVersion = :expectedStateVersion + 1 " +
            "AND :updatedAtMs >= updated_at_ms",
    )
    suspend fun transitionFencedRaw(
        id: String,
        expectedCandidateVersion: Long,
        expectedStateVersion: Long,
        expectedState: String,
        expectedArtifactSha256: String,
        expectedSourceGrantDigest: String,
        expectedToolSchemaFingerprintsWire: String,
        expectedVerifierVersion: String,
        expectedAssistantId: String,
        expectedAuthoritySubjectId: String?,
        nextState: String,
        nextStateVersion: Long,
        verificationReportWire: String?,
        verifiedAtMs: Long?,
        archivedAtMs: Long?,
        updatedAtMs: Long,
    ): Int

    @Transaction
    suspend fun insertCompiled(entity: LearnedWorkflowCandidateEntity) {
        require(entity.candidateVersion == 1L && entity.stateVersion == 1L)
        insertCandidate(entity)
        insertRevision(
            entity.toRevisionEntity(
                previousArtifactSha256 = null,
                reason = LearnedWorkflowCandidateRevisionReason.CREATED,
                actor = LearnedWorkflowCandidateRevisionActor.COMPILER,
            ),
        )
        check(find(entity.id) == entity && findRevision(entity.id, 1L) != null)
    }

    /** Compare-and-set an edited artifact and append its audit receipt in one transaction. */
    @Transaction
    suspend fun editArtifactFenced(
        expected: LearnedWorkflowCandidateEntity,
        next: LearnedWorkflowCandidateEntity,
    ): Boolean {
        require(next.id == expected.id)
        require(next.sourcePolicyId == expected.sourcePolicyId)
        require(next.sourcePolicyRevision == expected.sourcePolicyRevision)
        require(next.sourcePolicyArtifactSha256 == expected.sourcePolicyArtifactSha256)
        require(next.sourceGrantDigest == expected.sourceGrantDigest)
        require(next.assistantId == expected.assistantId)
        require(next.authoritySubjectId == expected.authoritySubjectId)
        require(next.candidateVersion == expected.candidateVersion + 1L)
        require(next.stateVersion == expected.stateVersion + 1L)
        require(next.state == "PROPOSED")
        require(next.verificationReportWire == null && next.verifiedAtMs == null)
        val affected = editArtifactFencedRaw(
            id = expected.id,
            expectedCandidateVersion = expected.candidateVersion,
            expectedStateVersion = expected.stateVersion,
            expectedArtifactSha256 = expected.artifactSha256,
            nextCandidateVersion = next.candidateVersion,
            nextStateVersion = next.stateVersion,
            positiveAnchorEvidenceId = next.positiveAnchorEvidenceId,
            evidenceIdsWire = next.evidenceIdsWire,
            canonicalTemplateJson = next.canonicalTemplateJson,
            typedSlotsWire = next.typedSlotsWire,
            capabilitySnapshotWire = next.capabilitySnapshotWire,
            toolSchemaFingerprintsWire = next.toolSchemaFingerprintsWire,
            maxOutputUtf8Bytes = next.maxOutputUtf8Bytes,
            artifactSha256 = next.artifactSha256,
            updatedAtMs = next.updatedAtMs,
        )
        if (affected != 1) return false
        insertRevision(
            next.toRevisionEntity(
                previousArtifactSha256 = expected.artifactSha256,
                reason = LearnedWorkflowCandidateRevisionReason.USER_EDITED,
                actor = LearnedWorkflowCandidateRevisionActor.USER,
            ),
        )
        check(find(next.id) == next && findRevision(next.id, next.stateVersion) != null)
        return true
    }

    /** Compare-and-set one lifecycle transition and append the matching content-free receipt. */
    @Transaction
    suspend fun transitionFenced(
        expected: LearnedWorkflowCandidateEntity,
        next: LearnedWorkflowCandidateEntity,
        reason: LearnedWorkflowCandidateRevisionReason,
        actor: LearnedWorkflowCandidateRevisionActor,
    ): Boolean {
        require(next.id == expected.id)
        require(next.candidateVersion == expected.candidateVersion)
        require(next.stateVersion == expected.stateVersion + 1L)
        require(next.artifactSha256 == expected.artifactSha256)
        require(next.copy(
            stateVersion = expected.stateVersion,
            state = expected.state,
            verificationReportWire = expected.verificationReportWire,
            verifiedAtMs = expected.verifiedAtMs,
            archivedAtMs = expected.archivedAtMs,
            updatedAtMs = expected.updatedAtMs,
        ) == expected) { "Lifecycle transition changed immutable candidate content" }
        require(isAllowedTransition(expected.state, next.state))
        require(reason.matchesTransition(next.state))
        require(
            (reason == LearnedWorkflowCandidateRevisionReason.RETENTION_EXPIRED) ==
                (actor == LearnedWorkflowCandidateRevisionActor.RETENTION),
        ) { "Retention archive requires its exact reason and actor" }
        val affected = transitionFencedRaw(
            id = expected.id,
            expectedCandidateVersion = expected.candidateVersion,
            expectedStateVersion = expected.stateVersion,
            expectedState = expected.state,
            expectedArtifactSha256 = expected.artifactSha256,
            expectedSourceGrantDigest = expected.sourceGrantDigest,
            expectedToolSchemaFingerprintsWire = expected.toolSchemaFingerprintsWire,
            expectedVerifierVersion = expected.verifierVersion,
            expectedAssistantId = expected.assistantId,
            expectedAuthoritySubjectId = expected.authoritySubjectId,
            nextState = next.state,
            nextStateVersion = next.stateVersion,
            verificationReportWire = next.verificationReportWire,
            verifiedAtMs = next.verifiedAtMs,
            archivedAtMs = next.archivedAtMs,
            updatedAtMs = next.updatedAtMs,
        )
        if (affected != 1) return false
        val revision = next.toRevisionEntity(
            previousArtifactSha256 = expected.artifactSha256,
            reason = reason,
            actor = actor,
        )
        insertRevision(revision)
        check(find(next.id) == next && findRevision(next.id, next.stateVersion) == revision)
        return true
    }

    @Query(
        "DELETE FROM learned_workflow_candidates WHERE assistant_id = :assistantId " +
            "AND authority_subject_id IS :authoritySubjectId",
    )
    suspend fun deleteScope(assistantId: String, authoritySubjectId: String?): Int

    @Query(
        "DELETE FROM learned_workflow_candidates WHERE assistant_id = :assistantId " +
            "AND authority_subject_id IS NULL",
    )
    suspend fun deleteAssistantScope(assistantId: String): Int

    @Query(
        "DELETE FROM learned_workflow_candidates WHERE authority_subject_id = :authoritySubjectId",
    )
    suspend fun deleteAuthoritySubjectScope(authoritySubjectId: String): Int

    /**
     * Exact Assistant-scope candidate ids for the cross-database erase saga. AuthoritySubject
     * candidates authored by the same assistant are deliberately excluded.
     */
    @Query(
        "SELECT id FROM learned_workflow_candidates WHERE assistant_id = :assistantId " +
            "AND authority_subject_id IS NULL AND id > :afterIdExclusive " +
            "ORDER BY id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 128 THEN :limit ELSE 0 END",
    )
    suspend fun listAssistantScopeIdsForErase(
        assistantId: String,
        afterIdExclusive: String,
        limit: Int,
    ): List<String>

    /** AuthoritySubject erase spans consuming assistants but never widens beyond the exact id. */
    @Query(
        "SELECT id FROM learned_workflow_candidates WHERE authority_subject_id = :authoritySubjectId " +
            "AND id > :afterIdExclusive ORDER BY id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 128 THEN :limit ELSE 0 END",
    )
    suspend fun listAuthoritySubjectScopeIdsForErase(
        authoritySubjectId: String,
        afterIdExclusive: String,
        limit: Int,
    ): List<String>

    /** All candidate ids for the global derived-reset cross-database promotion fence. */
    @Query(
        "SELECT id FROM learned_workflow_candidates WHERE id > :afterIdExclusive " +
            "ORDER BY id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 128 THEN :limit ELSE 0 END",
    )
    suspend fun listAllIdsForDerivedReset(
        afterIdExclusive: String,
        limit: Int,
    ): List<String>

    /**
     * Stable candidates for a later exact lifecycle CAS. Maintenance never deletes candidate or
     * review history: each returned row must move through [transitionFenced] to ARCHIVED.
     */
    @Query(
        "SELECT * FROM learned_workflow_candidates WHERE updated_at_ms < :cutoffMs " +
            "AND state IN ('PROPOSED','VERIFIED','REJECTED','STALE_SCHEMA','STALE_SOURCE'," +
            "'STALE_AUTHORITY') " +
            "ORDER BY updated_at_ms ASC, id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 128 THEN :limit ELSE 0 END",
    )
    suspend fun listExpiredArchivable(
        cutoffMs: Long,
        limit: Int,
    ): List<LearnedWorkflowCandidateEntity>

    /**
     * Revision TTL applies only to superseded machine receipts. The current head and every USER
     * review/edit receipt remain durable audit evidence regardless of age.
     */
    @Query(
        "DELETE FROM learned_workflow_candidate_revisions WHERE rowid IN (" +
            "SELECT r.rowid FROM learned_workflow_candidate_revisions r " +
            "JOIN learned_workflow_candidates c ON c.id = r.candidate_id " +
            "WHERE r.created_at_ms < :cutoffMs AND r.state_version != c.state_version " +
            "AND r.actor != 'USER' " +
            "ORDER BY r.created_at_ms ASC, r.candidate_id ASC, r.state_version ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 128 THEN :limit ELSE 0 END)",
    )
    suspend fun deleteExpiredSupersededMachineRevisions(
        cutoffMs: Long,
        limit: Int,
    ): Int

    @Query("DELETE FROM learned_workflow_candidates")
    suspend fun deleteAll(): Int
}

private fun isAllowedTransition(from: String, to: String): Boolean = when (from) {
    "PROPOSED" -> to in setOf(
        "VALIDATING", "REJECTED", "STALE_SOURCE", "STALE_AUTHORITY", "ARCHIVED",
    )
    "VALIDATING" -> to in setOf(
        "VERIFIED", "REJECTED", "STALE_SCHEMA", "STALE_SOURCE", "STALE_AUTHORITY", "ARCHIVED",
    )
    "VERIFIED" -> to in setOf(
        "PROMOTING", "REJECTED", "STALE_SCHEMA", "STALE_SOURCE", "STALE_AUTHORITY", "ARCHIVED",
    )
    "PROMOTING" -> to in setOf(
        "PROMOTED_DISABLED", "REJECTED", "STALE_SCHEMA", "STALE_SOURCE", "STALE_AUTHORITY",
        "ARCHIVED",
    )
    "PROMOTED_DISABLED" -> to in setOf("STALE_SOURCE", "STALE_AUTHORITY", "ARCHIVED")
    "REJECTED", "STALE_SCHEMA", "STALE_SOURCE", "STALE_AUTHORITY" -> to == "ARCHIVED"
    else -> false
}

private fun LearnedWorkflowCandidateRevisionReason.matchesTransition(to: String): Boolean =
    when (to) {
        "VALIDATING" -> this == LearnedWorkflowCandidateRevisionReason.VALIDATION_STARTED
        "VERIFIED" -> this == LearnedWorkflowCandidateRevisionReason.VALIDATION_PASSED
        "PROMOTING" -> this == LearnedWorkflowCandidateRevisionReason.PROMOTION_STARTED
        "PROMOTED_DISABLED" -> this == LearnedWorkflowCandidateRevisionReason.PROMOTED_DISABLED
        "REJECTED" -> this in setOf(
            LearnedWorkflowCandidateRevisionReason.VALIDATION_FAILED,
            LearnedWorkflowCandidateRevisionReason.REJECTED,
        )
        "STALE_SCHEMA" -> this == LearnedWorkflowCandidateRevisionReason.SCHEMA_DRIFT
        "STALE_SOURCE" -> this == LearnedWorkflowCandidateRevisionReason.SOURCE_INVALIDATED
        "STALE_AUTHORITY" -> this == LearnedWorkflowCandidateRevisionReason.AUTHORITY_DRIFT
        "ARCHIVED" -> this in setOf(
            LearnedWorkflowCandidateRevisionReason.ARCHIVED,
            LearnedWorkflowCandidateRevisionReason.RETENTION_EXPIRED,
        )
        else -> false
    }
