package me.rerere.rikkahub.learning.workflow.runtime

import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearnedPolicyWorkflowEvidenceAnchor
import me.rerere.rikkahub.learning.policy.LearnedPolicyWorkflowEvidencePolarity
import me.rerere.rikkahub.learning.policy.PolicyCandidateType
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.storage.PolicyApplicabilityWire
import me.rerere.rikkahub.learning.storage.StoredLearningPolicyStatus

/** Content-free row assembled from the validity projection and its exact evidence edge. */
internal data class ReviewedPolicyWorkflowEvidenceRecord(
    val evidenceId: String,
    val polarity: String,
    val sourceRevision: Long,
    val sourceIntegritySha256: String,
    val sourceValid: Boolean,
)

/** Pure final fence used by the Room-backed runtime and JVM tests. */
internal fun projectExactReviewedPolicyWorkflowSource(
    request: ReviewedPolicyWorkflowProposalRequest,
    currentStreamId: String,
    policy: LearningPolicyEntity,
    evidence: List<ReviewedPolicyWorkflowEvidenceRecord>,
): ReviewedPolicyWorkflowSourceResult {
    val fence = request.fence
    val scope = LearningScope.parseOrNull(policy.scopeKind, policy.scopeId)
        ?: return rejected(ReviewedPolicyWorkflowProposalRejection.POLICY_FENCE_CONFLICT)
    if (fence.sourceStreamId == null || currentStreamId != fence.sourceStreamId ||
        policy.id != fence.policyId || policy.stateVersion != fence.stateVersion ||
        policy.contentRevision != fence.contentRevision ||
        policy.artifactSha256 != fence.artifactSha256 || scope != fence.scope
    ) {
        return rejected(ReviewedPolicyWorkflowProposalRejection.POLICY_FENCE_CONFLICT)
    }
    if (policy.status != StoredLearningPolicyStatus.ACTIVE.name ||
        !policy.sourceValid || !policy.schemaValid || policy.staleReason != null
    ) {
        return rejected(ReviewedPolicyWorkflowProposalRejection.POLICY_NOT_ACTIVE)
    }
    if (policy.policyType != PolicyCandidateType.PROCEDURE.name) {
        return rejected(
            ReviewedPolicyWorkflowProposalRejection.POLICY_PROFILE_NOT_APPLICABLE,
        )
    }
    val applicableSchemas = runCatching {
        PolicyApplicabilityWire.decodeToolSchemasOrNull(policy.applicableToolSchemasWire)
    }.getOrNull()
    if (applicableSchemas?.size != 1) {
        return rejected(
            ReviewedPolicyWorkflowProposalRejection.POLICY_PROFILE_NOT_APPLICABLE,
        )
    }
    if (evidence.isEmpty() || evidence.size > MAX_PROPOSAL_EVIDENCE ||
        evidence.map { it.evidenceId }.distinct().size != evidence.size ||
        evidence.any { row ->
            !row.sourceValid || row.sourceRevision <= 0L ||
                !row.sourceIntegritySha256.isLowerSha256() ||
                row.polarity !in SUPPORTED_EVIDENCE_POLARITIES
        }
    ) {
        return rejected(ReviewedPolicyWorkflowProposalRejection.POLICY_EVIDENCE_INVALID)
    }
    val positive = evidence.count { it.polarity == STORED_POSITIVE }
    val negative = evidence.count { it.polarity == STORED_NEGATIVE }
    if (positive == 0 || policy.distinctEpisodeSupport != evidence.size.toLong() ||
        policy.positiveEpisodeCount != positive.toLong() ||
        policy.negativeEpisodeCount != negative.toLong()
    ) {
        return rejected(ReviewedPolicyWorkflowProposalRejection.POLICY_EVIDENCE_INVALID)
    }
    return ReviewedPolicyWorkflowSourceResult.Ready(
        ExactReviewedPolicyWorkflowSource(
            policyId = policy.id,
            policyStateVersion = policy.stateVersion,
            policyRevision = policy.contentRevision,
            policyArtifactSha256 = policy.artifactSha256,
            scope = scope,
            policyType = policy.policyType,
            trigger = policy.triggerSummary,
            procedure = policy.procedureSummary,
            verification = policy.verificationSummary,
            boundary = policy.boundarySummary,
            evidence = evidence.sortedBy(ReviewedPolicyWorkflowEvidenceRecord::evidenceId).map { row ->
                LearnedPolicyWorkflowEvidenceAnchor(
                    evidenceId = row.evidenceId,
                    polarity = when (row.polarity) {
                        STORED_POSITIVE -> LearnedPolicyWorkflowEvidencePolarity.POSITIVE
                        STORED_NEGATIVE -> LearnedPolicyWorkflowEvidencePolarity.NEGATIVE
                        else -> error("Unsupported evidence polarity escaped its closed fence")
                    },
                    sourceRevision = row.sourceRevision,
                    sourceIntegritySha256 = row.sourceIntegritySha256,
                )
            },
            applicableToolSchemaSha256 = applicableSchemas.single(),
            producerProviderIdentity = policy.producerProviderIdentity,
            producerModelIdentity = policy.producerModelIdentity,
            producerConfigurationIdentity = policy.producerConfigurationIdentity,
            producerConfigGeneration = policy.producerConfigGeneration,
            producerPromptIdentity = policy.producerPromptIdentity,
        ),
    )
}

private fun rejected(
    reason: ReviewedPolicyWorkflowProposalRejection,
): ReviewedPolicyWorkflowSourceResult = ReviewedPolicyWorkflowSourceResult.Rejected(reason)

private fun String.isLowerSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private const val STORED_POSITIVE = "POSITIVE"
private const val STORED_NEGATIVE = "NEGATIVE"
private val SUPPORTED_EVIDENCE_POLARITIES = setOf(STORED_POSITIVE, STORED_NEGATIVE)
