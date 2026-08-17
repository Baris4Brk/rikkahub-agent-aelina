package me.rerere.rikkahub.learning.storage.curator

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import me.rerere.rikkahub.learning.curator.CURATOR_REDACTED_WIRE
import me.rerere.rikkahub.learning.curator.CuratorSourcePolicyKey

@Dao
interface CuratorDeltaDao {
    /**
     * Full derived-timeline reset only. Deleting the candidate roots also cascades every Curator
     * revision and lineage row, so a restored/rewound authority stream cannot retain proposals
     * from the previous replay generation.
     */
    @Query("DELETE FROM curator_delta_candidates")
    suspend fun deleteAllCandidatesForDerivedReset(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCandidateIgnore(entity: CuratorDeltaCandidateEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(entity: CuratorDeltaRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLineage(entities: List<CuratorDeltaLineageEntity>)

    @Query("SELECT * FROM curator_delta_candidates WHERE id = :id LIMIT 1")
    suspend fun find(id: String): CuratorDeltaCandidateEntity?

    /**
     * Current authoritative evidence fence for production Curator planning. A count other than one
     * is ambiguous/stale and must fail closed; no evidence prose is selected.
     */
    @Query(
        "SELECT COUNT(*) FROM learning_source_validity WHERE scope_kind = :scopeKind " +
            "AND scope_id = :scopeId AND source_id = :evidenceId " +
            "AND source_revision = :sourceRevision AND state = 'VALID' " +
            "AND integrity_sha256 = :integritySha256",
    )
    suspend fun countExactValidEvidenceFence(
        evidenceId: String,
        scopeKind: String,
        scopeId: String,
        sourceRevision: Long,
        integritySha256: String,
    ): Int

    @Query(
        "SELECT * FROM curator_delta_revisions WHERE candidate_id = :candidateId " +
            "AND state_version = :stateVersion LIMIT 1",
    )
    suspend fun findRevision(
        candidateId: String,
        stateVersion: Long,
    ): CuratorDeltaRevisionEntity?

    @Query(
        "SELECT * FROM curator_delta_candidates WHERE scope_kind = :scopeKind " +
            "AND scope_id = :scopeId AND (updated_at_ms < :beforeUpdatedAtMs OR " +
            "(updated_at_ms = :beforeUpdatedAtMs AND id < :beforeId)) " +
            "ORDER BY updated_at_ms DESC, id DESC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 100 THEN :limit ELSE 0 END",
    )
    suspend fun listScopePage(
        scopeKind: String,
        scopeId: String,
        beforeUpdatedAtMs: Long,
        beforeId: String,
        limit: Int,
    ): List<CuratorDeltaCandidateEntity>

    /** Only retention-eligible nonterminal/conflict states; reviewed/applied rows never appear. */
    @Query(
        "SELECT * FROM curator_delta_candidates WHERE state IN " +
            "('PROPOSED','REJECTED','APPLY_CONFLICT','ROLLBACK_CONFLICT') " +
            "AND updated_at_ms < :cutoffMs AND " +
            "(updated_at_ms > :afterUpdatedAtMs OR " +
            "(updated_at_ms = :afterUpdatedAtMs AND id > :afterId)) " +
            "ORDER BY updated_at_ms ASC, id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 128 THEN :limit ELSE 0 END",
    )
    suspend fun listRetentionArchivablePage(
        cutoffMs: Long,
        afterUpdatedAtMs: Long,
        afterId: String,
        limit: Int,
    ): List<CuratorDeltaCandidateEntity>

    @Query(
        "SELECT * FROM curator_delta_revisions WHERE candidate_id = :candidateId " +
            "AND state_version < :beforeStateVersion ORDER BY state_version DESC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 100 THEN :limit ELSE 0 END",
    )
    suspend fun listRevisionPage(
        candidateId: String,
        beforeStateVersion: Long,
        limit: Int,
    ): List<CuratorDeltaRevisionEntity>

    @Query(
        "SELECT * FROM curator_delta_lineage WHERE " +
            "(parent_policy_id = :policyId OR child_policy_id = :policyId) AND " +
            "(updated_at_ms > :afterUpdatedAtMs OR " +
            "(updated_at_ms = :afterUpdatedAtMs AND candidate_id > :afterCandidateId)) " +
            "ORDER BY updated_at_ms ASC, candidate_id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 100 THEN :limit ELSE 0 END",
    )
    suspend fun listPolicyLineagePage(
        policyId: String,
        afterUpdatedAtMs: Long,
        afterCandidateId: String,
        limit: Int,
    ): List<CuratorDeltaLineageEntity>

    @Query(
        "SELECT * FROM curator_delta_lineage WHERE candidate_id = :candidateId AND " +
            "(parent_policy_id > :afterParentPolicyId OR " +
            "(parent_policy_id = :afterParentPolicyId AND child_policy_id > :afterChildPolicyId) " +
            "OR (parent_policy_id = :afterParentPolicyId AND " +
            "child_policy_id = :afterChildPolicyId AND relation_type > :afterRelationType)) " +
            "ORDER BY parent_policy_id ASC, child_policy_id ASC, relation_type ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 100 THEN :limit ELSE 0 END",
    )
    suspend fun listLineagePage(
        candidateId: String,
        afterParentPolicyId: String,
        afterChildPolicyId: String,
        afterRelationType: String,
        limit: Int,
    ): List<CuratorDeltaLineageEntity>

    @Query(
        "SELECT * FROM curator_delta_candidates WHERE " +
            "instr(source_policy_ids_key, :sourceToken) > 0 AND state != 'REDACTED_SOURCE' " +
            "AND (updated_at_ms > :afterUpdatedAtMs OR " +
            "(updated_at_ms = :afterUpdatedAtMs AND id > :afterId)) " +
            "ORDER BY updated_at_ms ASC, id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 128 THEN :limit ELSE 0 END",
    )
    suspend fun listBySourcePolicyTokenRaw(
        sourceToken: String,
        afterUpdatedAtMs: Long,
        afterId: String,
        limit: Int,
    ): List<CuratorDeltaCandidateEntity>

    @Query(
        "SELECT * FROM curator_delta_candidates WHERE scope_kind = :scopeKind " +
            "AND scope_id = :scopeId AND state != 'REDACTED_SOURCE' " +
            "AND (updated_at_ms > :afterUpdatedAtMs OR " +
            "(updated_at_ms = :afterUpdatedAtMs AND id > :afterId)) " +
            "ORDER BY updated_at_ms ASC, id ASC " +
            "LIMIT CASE WHEN :limit BETWEEN 1 AND 128 THEN :limit ELSE 0 END",
    )
    suspend fun listUnredactedScopePageRaw(
        scopeKind: String,
        scopeId: String,
        afterUpdatedAtMs: Long,
        afterId: String,
        limit: Int,
    ): List<CuratorDeltaCandidateEntity>

    @Query(
        "UPDATE curator_delta_candidates SET state_version = :nextStateVersion, " +
            "state = :nextState, apply_plan_id = :nextApplyPlanId, " +
            "apply_plan_wire = :nextApplyPlanWire, apply_plan_sha256 = :nextApplyPlanSha256, " +
            "conflict_code = :nextConflictCode, updated_at_ms = :updatedAtMs " +
            "WHERE id = :id AND state_version = :expectedStateVersion " +
            "AND state = :expectedState AND candidate_sha256 = :expectedCandidateSha256 " +
            "AND apply_plan_id IS :expectedApplyPlanId " +
            "AND apply_plan_wire IS :expectedApplyPlanWire " +
            "AND apply_plan_sha256 IS :expectedApplyPlanSha256 " +
            "AND ((:nextState IN ('APPLY_CONFLICT','ROLLBACK_CONFLICT') " +
            "AND :nextConflictCode IS NOT NULL) OR " +
            "(:nextState NOT IN ('APPLY_CONFLICT','ROLLBACK_CONFLICT') " +
            "AND :nextConflictCode IS NULL)) " +
            "AND :nextStateVersion = :expectedStateVersion + 1 " +
            "AND :updatedAtMs >= updated_at_ms",
    )
    suspend fun transitionFencedRaw(
        id: String,
        expectedStateVersion: Long,
        expectedState: String,
        expectedCandidateSha256: String,
        expectedApplyPlanId: String?,
        expectedApplyPlanWire: String?,
        expectedApplyPlanSha256: String?,
        nextStateVersion: Long,
        nextState: String,
        nextApplyPlanId: String?,
        nextApplyPlanWire: String?,
        nextApplyPlanSha256: String?,
        nextConflictCode: String?,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE curator_delta_candidates SET state_version = :nextStateVersion, " +
            "state = 'APPLIED', conflict_code = NULL, updated_at_ms = :updatedAtMs " +
            "WHERE id = :id AND state_version = :expectedStateVersion " +
            "AND state = 'APPLYING' AND candidate_sha256 = :expectedCandidateSha256 " +
            "AND apply_plan_id = :expectedApplyPlanId " +
            "AND apply_plan_wire = :expectedApplyPlanWire " +
            "AND apply_plan_sha256 = :expectedApplyPlanSha256 " +
            "AND :nextStateVersion = :expectedStateVersion + 1 " +
            "AND :updatedAtMs >= updated_at_ms",
    )
    suspend fun markAppliedFencedRaw(
        id: String,
        expectedStateVersion: Long,
        expectedCandidateSha256: String,
        expectedApplyPlanId: String,
        expectedApplyPlanWire: String,
        expectedApplyPlanSha256: String,
        nextStateVersion: Long,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE curator_delta_candidates SET state_version = :nextStateVersion, " +
            "state = 'REDACTED_SOURCE', source_policy_ids_key = '$CURATOR_REDACTED_WIRE', " +
            "candidate_wire = '$CURATOR_REDACTED_WIRE', " +
            "apply_plan_wire = CASE WHEN apply_plan_id IS NULL THEN NULL " +
            "ELSE '$CURATOR_REDACTED_WIRE' END, conflict_code = NULL, " +
            "redacted_at_ms = :redactedAtMs, updated_at_ms = :redactedAtMs " +
            "WHERE id = :id AND state_version = :expectedStateVersion " +
            "AND state = :expectedState AND candidate_sha256 = :expectedCandidateSha256 " +
            "AND source_policy_ids_key = :expectedSourcePolicyIdsKey " +
            "AND instr(source_policy_ids_key, :sourceToken) > 0 " +
            "AND apply_plan_id IS :expectedApplyPlanId " +
            "AND apply_plan_wire IS :expectedApplyPlanWire " +
            "AND apply_plan_sha256 IS :expectedApplyPlanSha256 " +
            "AND :nextStateVersion = :expectedStateVersion + 1 " +
            "AND :redactedAtMs >= updated_at_ms",
    )
    suspend fun redactFencedRaw(
        id: String,
        expectedStateVersion: Long,
        expectedState: String,
        expectedCandidateSha256: String,
        expectedSourcePolicyIdsKey: String,
        sourceToken: String,
        expectedApplyPlanId: String?,
        expectedApplyPlanWire: String?,
        expectedApplyPlanSha256: String?,
        nextStateVersion: Long,
        redactedAtMs: Long,
    ): Int

    @Query(
        "UPDATE curator_delta_candidates SET state_version = :nextStateVersion, " +
            "state = 'REDACTED_SOURCE', source_policy_ids_key = '$CURATOR_REDACTED_WIRE', " +
            "candidate_wire = '$CURATOR_REDACTED_WIRE', " +
            "apply_plan_wire = CASE WHEN apply_plan_id IS NULL THEN NULL " +
            "ELSE '$CURATOR_REDACTED_WIRE' END, conflict_code = NULL, " +
            "redacted_at_ms = :redactedAtMs, updated_at_ms = :redactedAtMs " +
            "WHERE id = :id AND scope_kind = :expectedScopeKind AND scope_id = :expectedScopeId " +
            "AND state_version = :expectedStateVersion AND state = :expectedState " +
            "AND candidate_sha256 = :expectedCandidateSha256 " +
            "AND source_policy_ids_key = :expectedSourcePolicyIdsKey " +
            "AND apply_plan_id IS :expectedApplyPlanId " +
            "AND apply_plan_wire IS :expectedApplyPlanWire " +
            "AND apply_plan_sha256 IS :expectedApplyPlanSha256 " +
            "AND :nextStateVersion = :expectedStateVersion + 1 " +
            "AND :redactedAtMs >= updated_at_ms",
    )
    suspend fun redactScopeFencedRaw(
        id: String,
        expectedScopeKind: String,
        expectedScopeId: String,
        expectedStateVersion: Long,
        expectedState: String,
        expectedCandidateSha256: String,
        expectedSourcePolicyIdsKey: String,
        expectedApplyPlanId: String?,
        expectedApplyPlanWire: String?,
        expectedApplyPlanSha256: String?,
        nextStateVersion: Long,
        redactedAtMs: Long,
    ): Int

    @Query(
        "SELECT COUNT(*) FROM curator_delta_lineage WHERE candidate_id = :candidateId " +
            "AND apply_plan_id = :applyPlanId AND active = 1",
    )
    suspend fun countActiveLineage(candidateId: String, applyPlanId: String): Int

    @Query(
        "UPDATE curator_delta_lineage SET active = 0, state_version = state_version + 1, " +
            "updated_at_ms = :updatedAtMs WHERE candidate_id = :candidateId " +
            "AND apply_plan_id = :applyPlanId AND active = 1 AND state_version = 1",
    )
    suspend fun deactivateLineageFencedRaw(
        candidateId: String,
        applyPlanId: String,
        updatedAtMs: Long,
    ): Int

    @Query(
        "UPDATE curator_delta_lineage SET active = 0, state_version = state_version + 1, " +
            "updated_at_ms = :updatedAtMs WHERE candidate_id = :candidateId AND active = 1",
    )
    suspend fun deactivateAllLineageRaw(candidateId: String, updatedAtMs: Long): Int

    @Transaction
    suspend fun insertProposed(
        entity: CuratorDeltaCandidateEntity,
        actor: CuratorDeltaRevisionActor = CuratorDeltaRevisionActor.CURATOR_MODEL,
    ): CuratorDeltaInsertResult {
        require(entity.state == CuratorDeltaStoredState.PROPOSED.name)
        require(entity.stateVersion == 1L && entity.applyPlanId == null)
        require(actor == CuratorDeltaRevisionActor.CURATOR_MODEL ||
            actor == CuratorDeltaRevisionActor.USER)
        if (insertCandidateIgnore(entity) == -1L) {
            val current = find(entity.id)
            return if (current != null && current.candidateSha256 == entity.candidateSha256 &&
                current.inputSetSha256 == entity.inputSetSha256 &&
                current.producerIdentitySha256 == entity.producerIdentitySha256
            ) {
                CuratorDeltaInsertResult.Duplicate(current.stateVersion, current.state)
            } else {
                CuratorDeltaInsertResult.Conflict(current?.stateVersion, current?.state)
            }
        }
        insertRevision(
            entity.toRevisionEntity(
                CuratorDeltaRevisionReason.CREATED,
                actor,
            ),
        )
        check(findRevision(entity.id, 1L) != null)
        return CuratorDeltaInsertResult.Inserted
    }

    /** Generic lifecycle CAS. Apply commit and rollback commit use their stronger methods below. */
    @Transaction
    suspend fun transitionFenced(
        expected: CuratorDeltaCandidateEntity,
        next: CuratorDeltaCandidateEntity,
        reason: CuratorDeltaRevisionReason,
        actor: CuratorDeltaRevisionActor,
    ): CuratorDeltaMutationResult {
        require(next.state !in setOf(
            CuratorDeltaStoredState.APPLIED.name,
            CuratorDeltaStoredState.ROLLED_BACK.name,
        )) { "Use the atomic lineage-aware terminal transition" }
        CuratorDeltaStateMachine.requireTransition(expected, next, reason, actor)
        return transitionAndAppend(expected, next, reason, actor)
    }

    /** Marks apply committed and appends exact revision-fenced lineage in one Room transaction. */
    @Transaction
    suspend fun markAppliedWithLineageFenced(
        expected: CuratorDeltaCandidateEntity,
        next: CuratorDeltaCandidateEntity,
    ): CuratorDeltaMutationResult {
        CuratorDeltaStateMachine.requireTransition(
            expected,
            next,
            CuratorDeltaRevisionReason.APPLY_COMMITTED,
            CuratorDeltaRevisionActor.APPLY_ENGINE,
        )
        require(next.state == CuratorDeltaStoredState.APPLIED.name)
        val plan = requireNotNull(expected.decodeApplyPlanOrNull())
        val lineage = plan.toLineageEntities(next.updatedAtMs)
        val planId = requireNotNull(next.applyPlanId)
        // A caller may race a stale apply after another plan materialized lineage. Detect it before
        // the candidate CAS so returning Conflict cannot commit any new row.
        if (countActiveLineage(next.id, planId) != 0) {
            return CuratorDeltaMutationResult.Conflict(expected.stateVersion, expected.state)
        }
        val affected = markAppliedFencedRaw(
            id = expected.id,
            expectedStateVersion = expected.stateVersion,
            expectedCandidateSha256 = expected.candidateSha256,
            expectedApplyPlanId = planId,
            expectedApplyPlanWire = requireNotNull(expected.applyPlanWire),
            expectedApplyPlanSha256 = requireNotNull(expected.applyPlanSha256),
            nextStateVersion = next.stateVersion,
            updatedAtMs = next.updatedAtMs,
        )
        if (affected != 1) {
            val current = find(expected.id)
            return CuratorDeltaMutationResult.Conflict(current?.stateVersion, current?.state)
        }
        // The candidate CAS comes first. Any constraint/cardinality failure below throws and Room
        // rolls the whole @Transaction back, including that CAS. UPDATE has an empty lineage and is
        // therefore a valid terminal apply rather than being rejected by a parent-lineage EXISTS.
        if (lineage.isNotEmpty()) insertLineage(lineage)
        check(countActiveLineage(next.id, planId) == lineage.size) {
            "Curator apply lineage cardinality changed"
        }
        insertRevision(
            next.toRevisionEntity(
                CuratorDeltaRevisionReason.APPLY_COMMITTED,
                CuratorDeltaRevisionActor.APPLY_ENGINE,
            ),
        )
        return CuratorDeltaMutationResult.Applied(next.stateVersion, next.state)
    }

    /** Rolls back only when the exact plan lineage cardinality is still active. */
    @Transaction
    suspend fun markRolledBackWithLineageFenced(
        expected: CuratorDeltaCandidateEntity,
        next: CuratorDeltaCandidateEntity,
    ): CuratorDeltaMutationResult {
        CuratorDeltaStateMachine.requireTransition(
            expected,
            next,
            CuratorDeltaRevisionReason.ROLLBACK_COMMITTED,
            CuratorDeltaRevisionActor.ROLLBACK_ENGINE,
        )
        require(next.state == CuratorDeltaStoredState.ROLLED_BACK.name)
        val plan = requireNotNull(expected.decodeApplyPlanOrNull())
        val planId = requireNotNull(expected.applyPlanId)
        val expectedLineageCount = plan.lineage.size
        if (countActiveLineage(expected.id, planId) != expectedLineageCount) {
            val current = find(expected.id)
            return CuratorDeltaMutationResult.Conflict(current?.stateVersion, current?.state)
        }
        val result = transitionAndAppend(
            expected,
            next,
            CuratorDeltaRevisionReason.ROLLBACK_COMMITTED,
            CuratorDeltaRevisionActor.ROLLBACK_ENGINE,
        )
        if (result !is CuratorDeltaMutationResult.Applied) return result
        val deactivated = deactivateLineageFencedRaw(expected.id, planId, next.updatedAtMs)
        check(deactivated == expectedLineageCount)
        return result
    }

    /**
     * Bounded exact-source privacy operation. It archives the candidate into a fixed redacted
     * terminal state, destroys both content wires, and deactivates materialized lineage.
     */
    @Transaction
    suspend fun redactByPolicySource(
        policyId: String,
        redactedAtMs: Long,
        limit: Int,
    ): CuratorDeltaRedactionResult {
        require(limit in 1..128)
        require(redactedAtMs >= 0L)
        val token = CuratorSourcePolicyKey.token(policyId)
        val matches = listBySourcePolicyTokenRaw(
            sourceToken = token,
            afterUpdatedAtMs = -1L,
            afterId = "",
            limit = limit,
        )
        var redacted = 0
        matches.forEach { expected ->
            if (!CuratorSourcePolicyKey.contains(expected.sourcePolicyIdsKey, policyId) ||
                expected.stateVersion == Long.MAX_VALUE
            ) error("Curator privacy redaction fence cannot advance")
            val effectiveRedactedAtMs = maxOf(redactedAtMs, expected.updatedAtMs)
            val affected = redactFencedRaw(
                id = expected.id,
                expectedStateVersion = expected.stateVersion,
                expectedState = expected.state,
                expectedCandidateSha256 = expected.candidateSha256,
                expectedSourcePolicyIdsKey = expected.sourcePolicyIdsKey,
                sourceToken = token,
                expectedApplyPlanId = expected.applyPlanId,
                expectedApplyPlanWire = expected.applyPlanWire,
                expectedApplyPlanSha256 = expected.applyPlanSha256,
                nextStateVersion = expected.stateVersion + 1L,
                redactedAtMs = effectiveRedactedAtMs,
            )
            check(affected == 1) { "Curator privacy redaction CAS conflict" }
            deactivateAllLineageRaw(expected.id, effectiveRedactedAtMs)
            val current = requireNotNull(find(expected.id))
            insertRevision(
                current.toRevisionEntity(
                    CuratorDeltaRevisionReason.SOURCE_REDACTED,
                    CuratorDeltaRevisionActor.PRIVACY,
                ),
            )
            redacted += 1
        }
        check(redacted == matches.size) { "Incomplete Curator source redaction batch" }
        return CuratorDeltaRedactionResult(
            scanned = matches.size,
            redacted = redacted,
            hasMore = matches.size == limit,
        )
    }

    /** Required before canonical Policy rows for one privacy-erased scope are deleted. */
    @Transaction
    suspend fun redactScopeBeforeErase(
        scopeKind: String,
        scopeId: String,
        redactedAtMs: Long,
        limit: Int,
    ): CuratorDeltaRedactionResult {
        require(limit in 1..128)
        require(redactedAtMs >= 0L)
        val matches = listUnredactedScopePageRaw(
            scopeKind,
            scopeId,
            -1L,
            "",
            limit,
        )
        var redacted = 0
        matches.forEach { expected ->
            check(expected.stateVersion < Long.MAX_VALUE) {
                "Curator privacy redaction fence cannot advance"
            }
            val effectiveRedactedAtMs = maxOf(redactedAtMs, expected.updatedAtMs)
            val affected = redactScopeFencedRaw(
                id = expected.id,
                expectedScopeKind = scopeKind,
                expectedScopeId = scopeId,
                expectedStateVersion = expected.stateVersion,
                expectedState = expected.state,
                expectedCandidateSha256 = expected.candidateSha256,
                expectedSourcePolicyIdsKey = expected.sourcePolicyIdsKey,
                expectedApplyPlanId = expected.applyPlanId,
                expectedApplyPlanWire = expected.applyPlanWire,
                expectedApplyPlanSha256 = expected.applyPlanSha256,
                nextStateVersion = expected.stateVersion + 1L,
                redactedAtMs = effectiveRedactedAtMs,
            )
            check(affected == 1) { "Curator privacy scope-redaction CAS conflict" }
            deactivateAllLineageRaw(expected.id, effectiveRedactedAtMs)
            insertRevision(
                requireNotNull(find(expected.id)).toRevisionEntity(
                    CuratorDeltaRevisionReason.SOURCE_REDACTED,
                    CuratorDeltaRevisionActor.PRIVACY,
                ),
            )
            redacted += 1
        }
        check(redacted == matches.size) { "Incomplete Curator scope redaction batch" }
        return CuratorDeltaRedactionResult(
            scanned = matches.size,
            redacted = redacted,
            hasMore = matches.size == limit,
        )
    }

}

private suspend fun CuratorDeltaDao.transitionAndAppend(
    expected: CuratorDeltaCandidateEntity,
    next: CuratorDeltaCandidateEntity,
    reason: CuratorDeltaRevisionReason,
    actor: CuratorDeltaRevisionActor,
): CuratorDeltaMutationResult {
    val affected = transitionFencedRaw(
        id = expected.id,
        expectedStateVersion = expected.stateVersion,
        expectedState = expected.state,
        expectedCandidateSha256 = expected.candidateSha256,
        expectedApplyPlanId = expected.applyPlanId,
        expectedApplyPlanWire = expected.applyPlanWire,
        expectedApplyPlanSha256 = expected.applyPlanSha256,
        nextStateVersion = next.stateVersion,
        nextState = next.state,
        nextApplyPlanId = next.applyPlanId,
        nextApplyPlanWire = next.applyPlanWire,
        nextApplyPlanSha256 = next.applyPlanSha256,
        nextConflictCode = next.conflictCode,
        updatedAtMs = next.updatedAtMs,
    )
    if (affected != 1) {
        val current = find(expected.id)
        return CuratorDeltaMutationResult.Conflict(current?.stateVersion, current?.state)
    }
    insertRevision(next.toRevisionEntity(reason, actor))
    check(findRevision(next.id, next.stateVersion) != null)
    return CuratorDeltaMutationResult.Applied(next.stateVersion, next.state)
}

sealed interface CuratorDeltaInsertResult {
    data object Inserted : CuratorDeltaInsertResult
    data class Duplicate(val currentStateVersion: Long, val currentState: String) :
        CuratorDeltaInsertResult
    data class Conflict(val currentStateVersion: Long?, val currentState: String?) :
        CuratorDeltaInsertResult
}

sealed interface CuratorDeltaMutationResult {
    data class Applied(val stateVersion: Long, val state: String) : CuratorDeltaMutationResult
    data class Conflict(val currentStateVersion: Long?, val currentState: String?) :
        CuratorDeltaMutationResult
}

data class CuratorDeltaRedactionResult(
    val scanned: Int,
    val redacted: Int,
    val hasMore: Boolean,
) {
    init {
        require(scanned in 0..128 && redacted in 0..scanned)
        require(scanned == redacted)
        require(!hasMore || scanned > 0)
    }
}
