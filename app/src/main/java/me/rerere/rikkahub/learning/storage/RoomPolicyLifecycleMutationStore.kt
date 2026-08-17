package me.rerere.rikkahub.learning.storage

import androidx.room.withTransaction
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.policy.PolicyLifecycle
import me.rerere.rikkahub.learning.policy.PolicyLifecycleFailure
import me.rerere.rikkahub.learning.policy.PolicyLifecycleReason
import me.rerere.rikkahub.learning.policy.PolicyLifecycleResult
import me.rerere.rikkahub.learning.policy.PolicyLifecycleState
import me.rerere.rikkahub.learning.policy.PolicyMutationConflict
import me.rerere.rikkahub.learning.policy.PolicyMutationRequest
import me.rerere.rikkahub.learning.policy.PolicyMutationResult
import me.rerere.rikkahub.learning.policy.PolicyMutationStore
import me.rerere.rikkahub.learning.policy.PolicyMutationTransaction
import me.rerere.rikkahub.learning.policy.ValidatingPolicyMutationStore

/**
 * Canonical Room lifecycle boundary. Lookup, exact tuple validation, lifecycle-only CAS and the
 * append-only audit revision commit in one LearningDatabase transaction. AppDatabase grant
 * authority is deliberately represented only by the already-revalidated, content-free proof on
 * the request; this transaction never pretends that two independent databases are atomic.
 */
class RoomPolicyLifecycleMutationStore(
    private val database: LearningDatabase,
) : PolicyMutationStore {
    override suspend fun mutate(request: PolicyMutationRequest): PolicyMutationResult =
        database.withTransaction {
            mutateInOpenTransaction(request)
        }

    /**
     * Same canonical validator/write path for a caller which already owns the LearningDatabase
     * transaction. Stage-D shadow admission uses this so lifecycle CAS and its observation either
     * both commit or both roll back.
     */
    internal suspend fun mutateInOpenTransaction(
        request: PolicyMutationRequest,
    ): PolicyMutationResult = ValidatingPolicyMutationStore(
        PolicyMutationTransaction(::applyInOpenTransaction),
    ).mutate(request)

    private suspend fun applyInOpenTransaction(
        request: PolicyMutationRequest,
    ): PolicyMutationResult {
        if (request !is PolicyMutationRequest.Transition) {
            // Candidate creation stays job-bound: evidence, lineage and DONE must commit together.
            return PolicyMutationResult.Conflict(PolicyMutationConflict.INVALID_TRANSITION)
        }
        val fence = request.fence
        val dao = database.policyDao()
        val current = dao.findPolicy(fence.policyId)
            ?: return PolicyMutationResult.Conflict(PolicyMutationConflict.IDENTITY_CONFLICT)
        if (current.scopeKind != fence.scope.kind.name || current.scopeId != fence.scope.storageId) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.IDENTITY_CONFLICT)
        }

        exactCommittedDuplicate(current, request, dao)?.let { return it }
        if (current.stateVersion != fence.expectedRevision) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.REVISION_CONFLICT)
        }
        if (current.contentRevision != fence.expectedContentRevision) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.CONTENT_REVISION_CONFLICT)
        }
        if (current.artifactSha256 != fence.expectedArtifactHash) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.ARTIFACT_CONFLICT)
        }
        // A same-state command using the current revision is not a replay. Only the exact
        // predecessor fence + committed audit row accepted above has duplicate semantics.
        if (current.status == request.target.name) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.INVALID_TRANSITION)
        }
        if (request.target.requiresValidSource() && !current.sourceValid) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.SOURCE_STALE)
        }
        if (request.target.requiresValidSource() && !current.schemaValid) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.SCHEMA_STALE)
        }
        val currentAudit = dao.findRevision(current.id, current.stateVersion)
            ?: return PolicyMutationResult.Conflict(PolicyMutationConflict.IDENTITY_CONFLICT)
        if (currentAudit.afterArtifactSha256 != current.artifactSha256) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.IDENTITY_CONFLICT)
        }
        val priorLifecycleSnapshot = currentAudit.afterSnapshot.takeIf {
            it.startsWith(POLICY_LIFECYCLE_SNAPSHOT_HEADER)
        }
        if (priorLifecycleSnapshot != null &&
            priorLifecycleSnapshot.withoutLifecycleEvidence() != current.lifecycleAuditSnapshot()
        ) {
            return PolicyMutationResult.Conflict(PolicyMutationConflict.IDENTITY_CONFLICT)
        }
        val beforeLifecycleSnapshot = priorLifecycleSnapshot ?: current.lifecycleAuditSnapshot()

        val currentStatus = runCatching { LearningPolicyStatus.valueOf(current.status) }
            .getOrElse {
                return PolicyMutationResult.Conflict(PolicyMutationConflict.INVALID_TRANSITION)
            }
        val lifecycle = PolicyLifecycle.transition(
            current = PolicyLifecycleState(
                status = currentStatus,
                revision = current.stateVersion,
                contentRevision = current.contentRevision,
                artifactHash = current.artifactSha256,
                reason = current.lifecycleReason(currentStatus),
                staleReason = current.staleLifecycleReason(currentStatus),
                updatedAtMs = current.updatedAtMs,
                usageCount = current.usageCount,
                lastUsedAtMs = current.lastUsedAtMs,
                observedUtilityDelta = current.observedUtilityDelta,
                utilityUncertainty = current.utilityUncertainty,
            ),
            expectedRevision = fence.expectedRevision,
            expectedContentRevision = fence.expectedContentRevision,
            expectedArtifactHash = fence.expectedArtifactHash,
            target = request.target,
            reason = request.reason,
            frozenNowMs = request.frozenNowMs,
        )
        val nextState = when (lifecycle) {
            is PolicyLifecycleResult.Applied -> lifecycle.state
            is PolicyLifecycleResult.Duplicate -> return PolicyMutationResult.Duplicate(
                current.id,
                lifecycle.state.revision,
            )
            is PolicyLifecycleResult.Rejected -> return PolicyMutationResult.Conflict(
                lifecycle.failure.toMutationConflict(),
            )
        }
        val validity = current.validityAfter(request.target)
        val changed = dao.updatePolicyLifecycleIfCurrent(
            policyId = current.id,
            expectedStateVersion = current.stateVersion,
            expectedContentRevision = current.contentRevision,
            expectedArtifactSha256 = current.artifactSha256,
            expectedApplicableToolSchemasWire = current.applicableToolSchemasWire,
            expectedApplicableModelIdentityWire = current.applicableModelIdentityWire,
            expectedApplicableProviderIdentityWire = current.applicableProviderIdentityWire,
            expectedApplicableTemplateIdentity = current.applicableTemplateIdentity,
            expectedApplicableConfigurationIdentity = current.applicableConfigurationIdentity,
            expectedApplicableConfigurationGeneration =
                current.applicableConfigurationGeneration,
            expectedApplicableCapabilityDigest = current.applicableCapabilityDigest,
            expectedApplicableAuthorityDigest = current.applicableAuthorityDigest,
            status = nextState.status.name,
            sourceValid = validity.sourceValid,
            schemaValid = validity.schemaValid,
            staleReason = nextState.staleReason?.name,
            updatedAtMs = nextState.updatedAtMs,
        )
        if (changed != 1) {
            return currentCasConflict(dao.findPolicy(current.id), fence)
        }
        val transitioned = current.copy(
            stateVersion = nextState.revision,
            status = nextState.status.name,
            sourceValid = validity.sourceValid,
            schemaValid = validity.schemaValid,
            staleReason = nextState.staleReason?.name,
            updatedAtMs = nextState.updatedAtMs,
        )
        dao.insertRevision(
            PolicyRevisionEntity(
                policyId = transitioned.id,
                revision = transitioned.stateVersion,
                beforeSnapshot = beforeLifecycleSnapshot,
                afterSnapshot = transitioned.lifecycleAuditSnapshot(request.lifecycleEvidence),
                beforeArtifactSha256 = current.artifactSha256,
                afterArtifactSha256 = transitioned.artifactSha256,
                reasonCode = request.reason.name,
                actor = request.actor.name,
                createdAtMs = request.frozenNowMs,
            ),
        )
        check(dao.findPolicy(transitioned.id) == transitioned) {
            "Policy lifecycle CAS result changed"
        }
        return PolicyMutationResult.Applied(
            policyId = transitioned.id,
            revision = transitioned.stateVersion,
            status = nextState.status,
        )
    }
}

private suspend fun exactCommittedDuplicate(
    current: LearningPolicyEntity,
    request: PolicyMutationRequest.Transition,
    dao: LearningPolicyDao,
): PolicyMutationResult.Duplicate? {
    val expectedNext = request.fence.expectedRevision.takeIf { it < Long.MAX_VALUE }?.plus(1L)
        ?: return null
    if (current.stateVersion != expectedNext ||
        current.contentRevision != request.fence.expectedContentRevision ||
        current.artifactSha256 != request.fence.expectedArtifactHash ||
        current.status != request.target.name ||
        current.updatedAtMs != request.frozenNowMs
    ) {
        return null
    }
    val expectedValidity = current.validityAfter(request.target)
    if (current.sourceValid != expectedValidity.sourceValid ||
        current.schemaValid != expectedValidity.schemaValid ||
        current.staleReason != request.target.staleReasonFor(request.reason)
    ) {
        return null
    }
    val audit = dao.findRevision(current.id, current.stateVersion) ?: return null
    if (audit.revision != current.stateVersion ||
        audit.reasonCode != request.reason.name ||
        audit.actor != request.actor.name ||
        audit.createdAtMs != request.frozenNowMs ||
        (
            audit.beforeSnapshot?.withoutLifecycleEvidence() !in
                current.possibleExactPredecessorSnapshots(request)
            ) ||
        audit.beforeArtifactSha256 != request.fence.expectedArtifactHash ||
        audit.afterArtifactSha256 != request.fence.expectedArtifactHash ||
        audit.afterSnapshot != current.lifecycleAuditSnapshot(request.lifecycleEvidence)
    ) {
        return null
    }
    return PolicyMutationResult.Duplicate(current.id, current.stateVersion)
}

private fun LearningPolicyEntity.possibleExactPredecessorSnapshots(
    request: PolicyMutationRequest.Transition,
): Set<String> {
    val validityCandidates = when (request.target) {
        LearningPolicyStatus.STALE_SOURCE -> listOf(
            PolicyValidity(false, schemaValid),
            PolicyValidity(true, schemaValid),
        )
        LearningPolicyStatus.STALE_SCHEMA -> listOf(
            PolicyValidity(sourceValid, false),
            PolicyValidity(sourceValid, true),
        )
        else -> listOf(PolicyValidity(sourceValid, schemaValid))
    }
    return buildSet {
        for (status in request.possiblePredecessorStatuses()) {
            for (validity in validityCandidates) {
                for (storedReason in status.possibleStoredReasons(request)) {
                    runCatching {
                        copy(
                            stateVersion = request.fence.expectedRevision,
                            status = status.name,
                            sourceValid = validity.sourceValid,
                            schemaValid = validity.schemaValid,
                            staleReason = storedReason,
                        ).lifecycleAuditSnapshot()
                    }.getOrNull()?.let(::add)
                }
            }
        }
    }
}

private fun PolicyMutationRequest.Transition.possiblePredecessorStatuses(): Set<LearningPolicyStatus> =
    when (target) {
        LearningPolicyStatus.CANDIDATE -> emptySet()
        LearningPolicyStatus.SHADOW -> when (reason) {
            PolicyLifecycleReason.SHADOW_ELIGIBLE -> setOf(LearningPolicyStatus.CANDIDATE)
            PolicyLifecycleReason.USER_RESTORED_REVISION -> setOf(LearningPolicyStatus.ARCHIVED)
            else -> emptySet()
        }
        LearningPolicyStatus.PROBATION -> setOf(LearningPolicyStatus.SHADOW)
        LearningPolicyStatus.ACTIVE -> setOf(LearningPolicyStatus.PROBATION)
        LearningPolicyStatus.SUSPENDED -> setOf(LearningPolicyStatus.ACTIVE)
        LearningPolicyStatus.SUSPENDED_PENDING_REVIEW -> setOf(
            LearningPolicyStatus.PROBATION,
            LearningPolicyStatus.ACTIVE,
        )
        LearningPolicyStatus.STALE_SOURCE,
        LearningPolicyStatus.STALE_SCHEMA,
        LearningPolicyStatus.STALE_AUTHORITY,
        -> LIVE_POLICY_STATUSES_FOR_AUDIT
        LearningPolicyStatus.ARCHIVED -> LearningPolicyStatus.entries
            .filterTo(linkedSetOf()) { it != LearningPolicyStatus.ARCHIVED }
    }

private fun LearningPolicyStatus.possibleStoredReasons(
    request: PolicyMutationRequest.Transition,
): Set<String?> = when (this) {
    LearningPolicyStatus.SUSPENDED -> setOf(PolicyLifecycleReason.USER_SUSPENDED.name)
    LearningPolicyStatus.SUSPENDED_PENDING_REVIEW ->
        setOf(PolicyLifecycleReason.SAFETY_REVIEW_REQUIRED.name)
    LearningPolicyStatus.STALE_SOURCE -> setOf(PolicyLifecycleReason.SOURCE_INVALIDATED.name)
    LearningPolicyStatus.STALE_SCHEMA -> buildSet {
        add(PolicyLifecycleReason.TOOL_SCHEMA_CHANGED.name)
        add(PolicyLifecycleReason.CAPABILITY_CHANGED.name)
        if (request.target == LearningPolicyStatus.STALE_SCHEMA) add(request.reason.name)
    }
    LearningPolicyStatus.STALE_AUTHORITY -> setOf(PolicyLifecycleReason.AUTHORITY_CHANGED.name)
    else -> setOf(null)
}

private val LIVE_POLICY_STATUSES_FOR_AUDIT = setOf(
    LearningPolicyStatus.CANDIDATE,
    LearningPolicyStatus.SHADOW,
    LearningPolicyStatus.PROBATION,
    LearningPolicyStatus.ACTIVE,
    LearningPolicyStatus.SUSPENDED,
    LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
)

private fun LearningPolicyStatus.requiresValidSource(): Boolean = this in setOf(
    LearningPolicyStatus.SHADOW,
    LearningPolicyStatus.PROBATION,
    LearningPolicyStatus.ACTIVE,
)

private data class PolicyValidity(
    val sourceValid: Boolean,
    val schemaValid: Boolean,
)

private fun LearningPolicyEntity.validityAfter(target: LearningPolicyStatus): PolicyValidity =
    when (target) {
        LearningPolicyStatus.STALE_SOURCE -> PolicyValidity(false, schemaValid)
        LearningPolicyStatus.STALE_SCHEMA -> PolicyValidity(sourceValid, false)
        else -> PolicyValidity(sourceValid, schemaValid)
    }

private fun LearningPolicyStatus.staleReasonFor(reason: PolicyLifecycleReason): String? =
    if (this in POLICY_REASON_REQUIRED_STATUSES) reason.name else null

private val POLICY_REASON_REQUIRED_STATUSES = setOf(
    LearningPolicyStatus.SUSPENDED,
    LearningPolicyStatus.SUSPENDED_PENDING_REVIEW,
    LearningPolicyStatus.STALE_SCHEMA,
    LearningPolicyStatus.STALE_SOURCE,
    LearningPolicyStatus.STALE_AUTHORITY,
)

private fun LearningPolicyEntity.lifecycleReason(
    status: LearningPolicyStatus,
): PolicyLifecycleReason = staleLifecycleReason(status) ?: when (status) {
    LearningPolicyStatus.CANDIDATE -> PolicyLifecycleReason.CREATED_FROM_VALIDATED_DRAFT
    LearningPolicyStatus.SHADOW -> PolicyLifecycleReason.SHADOW_ELIGIBLE
    LearningPolicyStatus.PROBATION,
    LearningPolicyStatus.ACTIVE -> PolicyLifecycleReason.USER_APPROVED_CONTEXTUAL_ADVICE
    LearningPolicyStatus.ARCHIVED -> PolicyLifecycleReason.USER_ARCHIVED
    LearningPolicyStatus.SUSPENDED -> PolicyLifecycleReason.USER_SUSPENDED
    LearningPolicyStatus.SUSPENDED_PENDING_REVIEW -> PolicyLifecycleReason.SAFETY_REVIEW_REQUIRED
    LearningPolicyStatus.STALE_SOURCE -> PolicyLifecycleReason.SOURCE_INVALIDATED
    LearningPolicyStatus.STALE_SCHEMA -> PolicyLifecycleReason.TOOL_SCHEMA_CHANGED
    LearningPolicyStatus.STALE_AUTHORITY -> PolicyLifecycleReason.AUTHORITY_CHANGED
}

private fun LearningPolicyEntity.staleLifecycleReason(
    status: LearningPolicyStatus,
): PolicyLifecycleReason? {
    if (status !in POLICY_REASON_REQUIRED_STATUSES) return null
    return staleReason?.let { encoded ->
        runCatching { PolicyLifecycleReason.valueOf(encoded) }.getOrNull()
    } ?: when (status) {
        LearningPolicyStatus.SUSPENDED -> PolicyLifecycleReason.USER_SUSPENDED
        LearningPolicyStatus.SUSPENDED_PENDING_REVIEW -> PolicyLifecycleReason.SAFETY_REVIEW_REQUIRED
        LearningPolicyStatus.STALE_SOURCE -> PolicyLifecycleReason.SOURCE_INVALIDATED
        LearningPolicyStatus.STALE_SCHEMA -> PolicyLifecycleReason.TOOL_SCHEMA_CHANGED
        LearningPolicyStatus.STALE_AUTHORITY -> PolicyLifecycleReason.AUTHORITY_CHANGED
        else -> null
    }
}

private fun PolicyLifecycleFailure.toMutationConflict(): PolicyMutationConflict = when (this) {
    PolicyLifecycleFailure.REVISION_CONFLICT -> PolicyMutationConflict.REVISION_CONFLICT
    PolicyLifecycleFailure.CONTENT_REVISION_CONFLICT ->
        PolicyMutationConflict.CONTENT_REVISION_CONFLICT
    PolicyLifecycleFailure.ARTIFACT_CONFLICT -> PolicyMutationConflict.ARTIFACT_CONFLICT
    PolicyLifecycleFailure.INVALID_TRANSITION,
    PolicyLifecycleFailure.CLOCK_REGRESSION -> PolicyMutationConflict.INVALID_TRANSITION
}

private fun currentCasConflict(
    current: LearningPolicyEntity?,
    fence: me.rerere.rikkahub.learning.policy.PolicyMutationFence,
): PolicyMutationResult.Conflict = when {
    current == null -> PolicyMutationResult.Conflict(PolicyMutationConflict.IDENTITY_CONFLICT)
    current.contentRevision != fence.expectedContentRevision ->
        PolicyMutationResult.Conflict(PolicyMutationConflict.CONTENT_REVISION_CONFLICT)
    current.artifactSha256 != fence.expectedArtifactHash ->
        PolicyMutationResult.Conflict(PolicyMutationConflict.ARTIFACT_CONFLICT)
    else -> PolicyMutationResult.Conflict(PolicyMutationConflict.REVISION_CONFLICT)
}

private fun LearningPolicyEntity.lifecycleAuditSnapshot(
    evidence: me.rerere.rikkahub.learning.policy.PolicyLifecycleEvidenceRecord? = null,
): String = (
    listOf(
        "policy-lifecycle-snapshot-v2",
        "state_version=$stateVersion",
        "content_revision=$contentRevision",
        "status=$status",
        "source_valid=$sourceValid",
        "schema_valid=$schemaValid",
        "applicable_tools=$applicableToolSchemasWire",
        "applicable_model=$applicableModelIdentityWire",
        "applicable_provider=$applicableProviderIdentityWire",
        "applicable_template=${applicableTemplateIdentity ?: "UNPROVEN"}",
        "applicable_configuration=${applicableConfigurationIdentity ?: "UNPROVEN"}",
        "applicable_configuration_generation=${applicableConfigurationGeneration ?: "UNPROVEN"}",
        "applicable_capability=${applicableCapabilityDigest ?: "UNKNOWN"}",
        "applicable_authority=${applicableAuthorityDigest ?: "UNKNOWN"}",
        "stale_reason=${staleReason ?: "NONE"}",
        "artifact=$artifactSha256",
        "support=$distinctEpisodeSupport",
        "positive=$positiveEpisodeCount",
        "negative=$negativeEpisodeCount",
        "usage_count=$usageCount",
        "last_used_at_ms=${lastUsedAtMs ?: "UNKNOWN"}",
        "confidence=$confidence",
        "observed_utility_delta=${observedUtilityDelta ?: "UNKNOWN"}",
        "utility_uncertainty=${utilityUncertainty ?: "UNKNOWN"}",
    ) + if (evidence == null) {
        emptyList()
    } else {
        listOf(
            "lifecycle_evidence_kind=${evidence.evidenceKind.name}",
            "lifecycle_evidence_contract_version=${evidence.evidenceContractVersion}",
            "lifecycle_evidence_digest=${evidence.evidenceDigest}",
            "lifecycle_evidence_observed_at_ms=${evidence.observedAtMs}",
        )
    }
).joinToString("\n")

private fun String.withoutLifecycleEvidence(): String = lineSequence()
    .filterNot { line -> line.startsWith("lifecycle_evidence_") }
    .joinToString("\n")

private const val POLICY_LIFECYCLE_SNAPSHOT_HEADER = "policy-lifecycle-snapshot-v2\n"
