package me.rerere.rikkahub.learning.policy

import kotlinx.coroutines.CancellationException

private val POLICY_DRIFT_SHA256 = Regex("[0-9a-f]{64}")
private const val MAX_POLICY_DRIFT_TOOL_SCHEMAS = 16
private const val MAX_CURRENT_TOOL_SCHEMAS = 512

sealed interface PolicyDriftEvidence {
    val evidenceContractVersion: Int
    val evidenceDigest: String
}

enum class PolicySourceAuthorityState {
    CURRENT,
    INVALIDATED,
    TOMBSTONED,
    UNKNOWN,
}

data class PolicySourceDriftEvidence(
    val expectedSourceRevision: Long,
    val currentSourceRevision: Long?,
    val authorityState: PolicySourceAuthorityState,
    override val evidenceContractVersion: Int,
    override val evidenceDigest: String,
) : PolicyDriftEvidence {
    init {
        require(expectedSourceRevision > 0L)
        require(currentSourceRevision == null || currentSourceRevision > 0L)
        require(authorityState != PolicySourceAuthorityState.CURRENT || currentSourceRevision != null)
        requireValidPolicyDriftEvidenceIdentity(evidenceContractVersion, evidenceDigest)
    }
}

/** Exact schema fingerprints required by the Policy and currently available to the caller. */
data class PolicyToolSchemaDriftEvidence(
    val expectedToolSchemaFingerprints: Set<String>,
    /** Null is an explicit UNKNOWN authority read and therefore never authorizes a downgrade. */
    val availableToolSchemaFingerprints: Set<String>?,
    override val evidenceContractVersion: Int,
    override val evidenceDigest: String,
) : PolicyDriftEvidence {
    init {
        require(expectedToolSchemaFingerprints.size <= MAX_POLICY_DRIFT_TOOL_SCHEMAS)
        require(expectedToolSchemaFingerprints.all(POLICY_DRIFT_SHA256::matches))
        require(
            availableToolSchemaFingerprints == null ||
                availableToolSchemaFingerprints.size <= MAX_CURRENT_TOOL_SCHEMAS,
        )
        require(availableToolSchemaFingerprints?.all(POLICY_DRIFT_SHA256::matches) != false)
        requireValidPolicyDriftEvidenceIdentity(evidenceContractVersion, evidenceDigest)
    }
}

enum class PolicyAuthorityAccessState {
    ALLOWED,
    DENIED,
    UNKNOWN,
}

data class PolicyAuthorityDriftEvidence(
    val expectedAuthorityDigest: String,
    val currentAuthorityDigest: String?,
    val accessState: PolicyAuthorityAccessState,
    override val evidenceContractVersion: Int,
    override val evidenceDigest: String,
) : PolicyDriftEvidence {
    init {
        require(expectedAuthorityDigest.matches(POLICY_DRIFT_SHA256))
        require(currentAuthorityDigest == null || currentAuthorityDigest.matches(POLICY_DRIFT_SHA256))
        require(accessState != PolicyAuthorityAccessState.ALLOWED || currentAuthorityDigest != null)
        requireValidPolicyDriftEvidenceIdentity(evidenceContractVersion, evidenceDigest)
    }
}

enum class PolicyCapabilityState {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}

/** Versioned provider/model capability contract, not a helpfulness or model-judge signal. */
data class PolicyCapabilityDriftEvidence(
    val expectedCapabilityDigest: String,
    val currentCapabilityDigest: String?,
    val capabilityState: PolicyCapabilityState,
    override val evidenceContractVersion: Int,
    override val evidenceDigest: String,
) : PolicyDriftEvidence {
    init {
        require(expectedCapabilityDigest.matches(POLICY_DRIFT_SHA256))
        require(currentCapabilityDigest == null || currentCapabilityDigest.matches(POLICY_DRIFT_SHA256))
        require(capabilityState != PolicyCapabilityState.AVAILABLE || currentCapabilityDigest != null)
        requireValidPolicyDriftEvidenceIdentity(evidenceContractVersion, evidenceDigest)
    }
}

/**
 * Producer identity changes split utility cohorts; they are not evidence that the Policy became
 * harmful and therefore never authorize a lifecycle mutation.
 */
data class PolicyProducerCohortDriftEvidence(
    val expectedProducerModelIdentity: String,
    val currentProducerModelIdentity: String,
    val expectedProducerProviderIdentity: String,
    val currentProducerProviderIdentity: String,
    override val evidenceContractVersion: Int,
    override val evidenceDigest: String,
) : PolicyDriftEvidence {
    init {
        listOf(
            expectedProducerModelIdentity,
            currentProducerModelIdentity,
            expectedProducerProviderIdentity,
            currentProducerProviderIdentity,
        ).forEach { require(it.matches(POLICY_DRIFT_SHA256)) }
        requireValidPolicyDriftEvidenceIdentity(evidenceContractVersion, evidenceDigest)
    }
}

data class PolicyDriftCommand(
    val fence: PolicyMutationFence,
    val evidence: PolicyDriftEvidence,
    /** Frozen once by the authority observer and reused for replay. */
    val frozenNowMs: Long,
) {
    init {
        require(frozenNowMs >= 0L)
    }
}

enum class PolicyDriftAbstainReason {
    EVIDENCE_UNKNOWN,
    MUTATION_UNAVAILABLE,
}

sealed interface PolicyDriftGovernorResult {
    data object NoDrift : PolicyDriftGovernorResult

    data class CohortBoundaryRequired(
        val producerModelChanged: Boolean,
        val producerProviderChanged: Boolean,
    ) : PolicyDriftGovernorResult

    data class DowngradeApplied(
        val result: PolicyMutationResult.Applied,
    ) : PolicyDriftGovernorResult

    data class DowngradeDuplicate(
        val result: PolicyMutationResult.Duplicate,
    ) : PolicyDriftGovernorResult

    data class DowngradeConflict(
        val conflict: PolicyMutationResult.Conflict,
    ) : PolicyDriftGovernorResult

    data class Abstained(
        val reason: PolicyDriftAbstainReason,
    ) : PolicyDriftGovernorResult
}

/**
 * P2 deterministic drift boundary. UNKNOWN never changes state, while a hard authority fact is
 * embedded in the exact revision/content/artifact-fenced lifecycle mutation. The canonical Room
 * mutation commits state, reason and evidence in the same append-only revision transaction.
 */
class PolicyDriftGovernor(
    private val mutationStore: PolicyMutationStore,
) {
    suspend fun evaluate(command: PolicyDriftCommand): PolicyDriftGovernorResult {
        return when (val plan = planPolicyDrift(command.evidence)) {
            PolicyDriftPlan.NoDrift -> PolicyDriftGovernorResult.NoDrift
            PolicyDriftPlan.Unknown -> PolicyDriftGovernorResult.Abstained(
                PolicyDriftAbstainReason.EVIDENCE_UNKNOWN,
            )
            is PolicyDriftPlan.CohortBoundary -> PolicyDriftGovernorResult.CohortBoundaryRequired(
                producerModelChanged = plan.producerModelChanged,
                producerProviderChanged = plan.producerProviderChanged,
            )
            is PolicyDriftPlan.Downgrade -> applyDowngrade(command, plan)
        }
    }

    private suspend fun applyDowngrade(
        command: PolicyDriftCommand,
        plan: PolicyDriftPlan.Downgrade,
    ): PolicyDriftGovernorResult {
        val evidenceRecord = PolicyLifecycleEvidenceRecord(
            fence = command.fence,
            target = plan.target,
            reason = plan.reason,
            evidenceKind = plan.evidenceKind,
            evidenceContractVersion = command.evidence.evidenceContractVersion,
            evidenceDigest = command.evidence.evidenceDigest,
            observedAtMs = command.frozenNowMs,
        )
        val mutation = try {
            mutationStore.mutate(
                PolicyMutationRequest.Transition(
                    fence = command.fence,
                    target = plan.target,
                    reason = plan.reason,
                    frozenNowMs = command.frozenNowMs,
                    actor = plan.actor,
                    lifecycleEvidence = evidenceRecord,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return PolicyDriftGovernorResult.Abstained(
                PolicyDriftAbstainReason.MUTATION_UNAVAILABLE,
            )
        }
        return when (mutation) {
            is PolicyMutationResult.Applied -> PolicyDriftGovernorResult.DowngradeApplied(mutation)
            is PolicyMutationResult.Duplicate ->
                PolicyDriftGovernorResult.DowngradeDuplicate(mutation)
            is PolicyMutationResult.Conflict ->
                PolicyDriftGovernorResult.DowngradeConflict(mutation)
        }
    }
}

private sealed interface PolicyDriftPlan {
    data object NoDrift : PolicyDriftPlan
    data object Unknown : PolicyDriftPlan

    data class CohortBoundary(
        val producerModelChanged: Boolean,
        val producerProviderChanged: Boolean,
    ) : PolicyDriftPlan

    data class Downgrade(
        val target: LearningPolicyStatus,
        val reason: PolicyLifecycleReason,
        val actor: PolicyMutationActor,
        val evidenceKind: PolicyLifecycleEvidenceKind,
    ) : PolicyDriftPlan
}

private fun planPolicyDrift(evidence: PolicyDriftEvidence): PolicyDriftPlan = when (evidence) {
    is PolicySourceDriftEvidence -> when (evidence.authorityState) {
        PolicySourceAuthorityState.UNKNOWN -> PolicyDriftPlan.Unknown
        PolicySourceAuthorityState.TOMBSTONED -> sourceDowngrade(
            PolicyLifecycleEvidenceKind.SOURCE_TOMBSTONE,
        )
        PolicySourceAuthorityState.INVALIDATED -> sourceDowngrade(
            PolicyLifecycleEvidenceKind.SOURCE_REVISION_DRIFT,
        )
        PolicySourceAuthorityState.CURRENT -> if (
            evidence.currentSourceRevision == evidence.expectedSourceRevision
        ) {
            PolicyDriftPlan.NoDrift
        } else {
            sourceDowngrade(PolicyLifecycleEvidenceKind.SOURCE_REVISION_DRIFT)
        }
    }

    is PolicyToolSchemaDriftEvidence -> when {
        evidence.availableToolSchemaFingerprints == null -> PolicyDriftPlan.Unknown
        evidence.expectedToolSchemaFingerprints.all {
            it in evidence.availableToolSchemaFingerprints
        } -> PolicyDriftPlan.NoDrift
        else -> PolicyDriftPlan.Downgrade(
            target = LearningPolicyStatus.STALE_SCHEMA,
            reason = PolicyLifecycleReason.TOOL_SCHEMA_CHANGED,
            actor = PolicyMutationActor.SOURCE_INVALIDATOR,
            evidenceKind = PolicyLifecycleEvidenceKind.TOOL_SCHEMA_DRIFT,
        )
    }

    is PolicyAuthorityDriftEvidence -> when (evidence.accessState) {
        PolicyAuthorityAccessState.UNKNOWN -> PolicyDriftPlan.Unknown
        PolicyAuthorityAccessState.DENIED -> authorityDowngrade()
        PolicyAuthorityAccessState.ALLOWED -> if (
            evidence.currentAuthorityDigest == evidence.expectedAuthorityDigest
        ) {
            PolicyDriftPlan.NoDrift
        } else {
            authorityDowngrade()
        }
    }

    is PolicyCapabilityDriftEvidence -> when (evidence.capabilityState) {
        PolicyCapabilityState.UNKNOWN -> PolicyDriftPlan.Unknown
        PolicyCapabilityState.UNAVAILABLE -> capabilityDowngrade()
        PolicyCapabilityState.AVAILABLE -> if (
            evidence.currentCapabilityDigest == evidence.expectedCapabilityDigest
        ) {
            PolicyDriftPlan.NoDrift
        } else {
            capabilityDowngrade()
        }
    }

    is PolicyProducerCohortDriftEvidence -> {
        val modelChanged = evidence.currentProducerModelIdentity !=
            evidence.expectedProducerModelIdentity
        val providerChanged = evidence.currentProducerProviderIdentity !=
            evidence.expectedProducerProviderIdentity
        if (!modelChanged && !providerChanged) {
            PolicyDriftPlan.NoDrift
        } else {
            PolicyDriftPlan.CohortBoundary(modelChanged, providerChanged)
        }
    }
}

private fun sourceDowngrade(kind: PolicyLifecycleEvidenceKind) = PolicyDriftPlan.Downgrade(
    target = LearningPolicyStatus.STALE_SOURCE,
    reason = PolicyLifecycleReason.SOURCE_INVALIDATED,
    actor = PolicyMutationActor.SOURCE_INVALIDATOR,
    evidenceKind = kind,
)

private fun authorityDowngrade() = PolicyDriftPlan.Downgrade(
    target = LearningPolicyStatus.STALE_AUTHORITY,
    reason = PolicyLifecycleReason.AUTHORITY_CHANGED,
    actor = PolicyMutationActor.AUTHORITY_RECONCILER,
    evidenceKind = PolicyLifecycleEvidenceKind.AUTHORITY_DRIFT,
)

private fun capabilityDowngrade() = PolicyDriftPlan.Downgrade(
    target = LearningPolicyStatus.STALE_SCHEMA,
    reason = PolicyLifecycleReason.CAPABILITY_CHANGED,
    actor = PolicyMutationActor.SOURCE_INVALIDATOR,
    evidenceKind = PolicyLifecycleEvidenceKind.CAPABILITY_DRIFT,
)

private fun requireValidPolicyDriftEvidenceIdentity(version: Int, digest: String) {
    require(version > 0) { "Invalid Policy drift evidence version" }
    require(digest.matches(POLICY_DRIFT_SHA256)) { "Invalid Policy drift evidence digest" }
}
