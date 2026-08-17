package me.rerere.rikkahub.learning.authority

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.assistant.SecondUserDerivedAuthorityInvalidationBatch
import me.rerere.rikkahub.assistant.SecondUserDerivedAuthorityInvalidationPort
import me.rerere.rikkahub.assistant.SecondUserDerivedAuthorityInvalidationRequest
import me.rerere.rikkahub.assistant.SecondUserDerivedAuthorityInvalidationResult
import me.rerere.rikkahub.learning.model.LearningCanonicalId
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.policy.PolicyLifecycleEvidenceKind
import me.rerere.rikkahub.learning.policy.PolicyLifecycleEvidenceRecord
import me.rerere.rikkahub.learning.policy.PolicyLifecycleReason
import me.rerere.rikkahub.learning.policy.PolicyMutationActor
import me.rerere.rikkahub.learning.policy.PolicyMutationFence
import me.rerere.rikkahub.learning.policy.PolicyMutationRequest
import me.rerere.rikkahub.learning.policy.PolicyMutationResult
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.RoomPolicyLifecycleMutationStore
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionActor
import me.rerere.rikkahub.learning.storage.entity.LearnedWorkflowCandidateRevisionReason
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState

/**
 * LearningDatabase half of second-user authority revocation.
 *
 * It uses narrow raw ID reads so the authority maintenance port does not widen production DAOs or
 * leak a Room handle. Every actual mutation still passes through the canonical lifecycle CAS and
 * append-only revision writer in the same LearningDatabase transaction.
 */
class RoomSecondUserDerivedAuthorityInvalidationPort(
    private val database: LearningDatabase,
) : SecondUserDerivedAuthorityInvalidationPort {
    override suspend fun invalidateExactAuthorityBatch(
        request: SecondUserDerivedAuthorityInvalidationRequest,
    ): SecondUserDerivedAuthorityInvalidationResult = try {
        database.withTransaction {
            val policyIds = database.queryExactIds(
                sql = TRANSITIONABLE_POLICY_IDS_SQL,
                authoritySubjectId = request.authoritySubjectId,
                assistantId = null,
                limit = request.limit,
            )
            val workflowIds = database.queryExactIds(
                sql = TRANSITIONABLE_WORKFLOW_IDS_SQL,
                authoritySubjectId = request.authoritySubjectId,
                assistantId = request.fence.assistantId.toString(),
                limit = request.limit,
            )

            var stalePolicies = 0
            val policyDao = database.policyDao()
            val mutationStore = RoomPolicyLifecycleMutationStore(database)
            val scope = LearningScope.AuthoritySubject(request.authoritySubjectId)
            for (policyId in policyIds) {
                val policy = policyDao.findPolicy(policyId)
                    ?: throw SecondUserDerivedAuthorityInvalidationInvariantException()
                if (policy.scopeKind != scope.kind.name || policy.scopeId != scope.storageId ||
                    policy.status !in TRANSITIONABLE_POLICY_STATES
                ) {
                    throw SecondUserDerivedAuthorityInvalidationInvariantException()
                }
                val transitionAtMs = maxOf(request.fence.frozenNowMs, policy.updatedAtMs)
                val fence = PolicyMutationFence(
                    policyId = policy.id,
                    scope = scope,
                    expectedRevision = policy.stateVersion,
                    expectedContentRevision = policy.contentRevision,
                    expectedArtifactHash = policy.artifactSha256,
                )
                val evidence = PolicyLifecycleEvidenceRecord(
                    fence = fence,
                    target = LearningPolicyStatus.STALE_AUTHORITY,
                    reason = PolicyLifecycleReason.AUTHORITY_CHANGED,
                    evidenceKind = PolicyLifecycleEvidenceKind.AUTHORITY_DRIFT,
                    evidenceContractVersion = AUTHORITY_EVIDENCE_CONTRACT_VERSION,
                    evidenceDigest = LearningCanonicalId.digest(
                        domainVersion = AUTHORITY_EVIDENCE_DOMAIN,
                        fields = listOf(
                            request.fence.assistantId.toString(),
                            request.fence.conversationId.toString(),
                            request.fence.authorityEpoch.toString(),
                            request.authoritySubjectId,
                            policy.id,
                            policy.stateVersion.toString(),
                            policy.contentRevision.toString(),
                            policy.artifactSha256,
                        ),
                    ),
                    observedAtMs = transitionAtMs,
                )
                when (
                    mutationStore.mutateInOpenTransaction(
                        PolicyMutationRequest.Transition(
                            fence = fence,
                            target = LearningPolicyStatus.STALE_AUTHORITY,
                            reason = PolicyLifecycleReason.AUTHORITY_CHANGED,
                            frozenNowMs = transitionAtMs,
                            actor = PolicyMutationActor.AUTHORITY_RECONCILER,
                            lifecycleEvidence = evidence,
                        ),
                    )
                ) {
                    is PolicyMutationResult.Applied -> stalePolicies += 1
                    is PolicyMutationResult.Duplicate -> Unit
                    is PolicyMutationResult.Conflict ->
                        throw SecondUserDerivedAuthorityInvalidationInvariantException()
                }
            }

            var staleWorkflows = 0
            val workflowDao = database.learnedWorkflowCandidateDao()
            for (candidateId in workflowIds) {
                val current = workflowDao.find(candidateId)
                    ?: throw SecondUserDerivedAuthorityInvalidationInvariantException()
                if (current.assistantId != request.fence.assistantId.toString() ||
                    current.authoritySubjectId != request.authoritySubjectId ||
                    current.state !in TRANSITIONABLE_WORKFLOW_STATES ||
                    current.stateVersion == Long.MAX_VALUE
                ) {
                    throw SecondUserDerivedAuthorityInvalidationInvariantException()
                }
                val next = current.copy(
                    state = LearnedWorkflowCandidateState.STALE_AUTHORITY.name,
                    stateVersion = current.stateVersion + 1L,
                    updatedAtMs = maxOf(request.fence.frozenNowMs, current.updatedAtMs),
                )
                if (!workflowDao.transitionFenced(
                        expected = current,
                        next = next,
                        reason = LearnedWorkflowCandidateRevisionReason.AUTHORITY_DRIFT,
                        actor = LearnedWorkflowCandidateRevisionActor.AUTHORITY_RECONCILER,
                    )
                ) {
                    throw SecondUserDerivedAuthorityInvalidationInvariantException()
                }
                staleWorkflows += 1
            }

            val complete = database.queryExactIds(
                sql = TRANSITIONABLE_POLICY_IDS_SQL,
                authoritySubjectId = request.authoritySubjectId,
                assistantId = null,
                limit = 1,
            ).isEmpty() && database.queryExactIds(
                sql = TRANSITIONABLE_WORKFLOW_IDS_SQL,
                authoritySubjectId = request.authoritySubjectId,
                assistantId = request.fence.assistantId.toString(),
                limit = 1,
            ).isEmpty()

            SecondUserDerivedAuthorityInvalidationResult.Ready(
                SecondUserDerivedAuthorityInvalidationBatch(
                    policiesMadeStale = stalePolicies,
                    workflowCandidatesMadeStale = staleWorkflows,
                    complete = complete,
                ),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        SecondUserDerivedAuthorityInvalidationResult.Unavailable
    }
}

private fun LearningDatabase.queryExactIds(
    sql: String,
    authoritySubjectId: String,
    assistantId: String?,
    limit: Int,
): List<String> {
    val arguments = if (assistantId == null) {
        arrayOf<Any>(authoritySubjectId, limit)
    } else {
        arrayOf<Any>(assistantId, authoritySubjectId, limit)
    }
    return openHelper.writableDatabase.query(SimpleSQLiteQuery(sql, arguments)).use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow("id")
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(idColumn))
        }
    }
}

private const val TRANSITIONABLE_POLICY_IDS_SQL =
    "SELECT id FROM learning_policies WHERE scope_kind = 'AUTHORITY_SUBJECT' " +
        "AND scope_id = ? AND status IN " +
        "('CANDIDATE','SHADOW','PROBATION','ACTIVE','SUSPENDED','SUSPENDED_PENDING_REVIEW') " +
        "ORDER BY id ASC LIMIT ?"

private const val TRANSITIONABLE_WORKFLOW_IDS_SQL =
    "SELECT id FROM learned_workflow_candidates WHERE assistant_id = ? " +
        "AND authority_subject_id = ? AND state IN " +
        "('PROPOSED','VALIDATING','VERIFIED','PROMOTING','PROMOTED_DISABLED') " +
        "ORDER BY id ASC LIMIT ?"

private val TRANSITIONABLE_POLICY_STATES = setOf(
    "CANDIDATE",
    "SHADOW",
    "PROBATION",
    "ACTIVE",
    "SUSPENDED",
    "SUSPENDED_PENDING_REVIEW",
)

private val TRANSITIONABLE_WORKFLOW_STATES = setOf(
    "PROPOSED",
    "VALIDATING",
    "VERIFIED",
    "PROMOTING",
    "PROMOTED_DISABLED",
)

private const val AUTHORITY_EVIDENCE_CONTRACT_VERSION = 1
private const val AUTHORITY_EVIDENCE_DOMAIN = "second-user-authority-drift-evidence-v1"

private class SecondUserDerivedAuthorityInvalidationInvariantException : IllegalStateException()

