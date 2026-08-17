package me.rerere.rikkahub.learning.workflow.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.capability.RiskLevel
import me.rerere.rikkahub.learning.grant.MAX_POLICY_GRANT_AUTHORITY_RESULTS
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySource
import me.rerere.rikkahub.learning.grant.PolicyGrantAuthorityState
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearnedPolicyProposal
import me.rerere.rikkahub.learning.policy.LearnedPolicyWorkflowEvidenceAnchor
import me.rerere.rikkahub.learning.policy.LearnedPolicyWorkflowEvidencePolarity
import me.rerere.rikkahub.learning.policy.LearnedWorkflowActionProposal
import me.rerere.rikkahub.learning.policy.PolicyCandidateType
import me.rerere.rikkahub.learning.review.PolicyReviewFence
import me.rerere.rikkahub.learning.verification.WORKFLOW_CANDIDATE_VERIFIER_VERSION
import kotlin.uuid.Uuid

/** Exact user-reviewed identity. No action, argument, or slot is accepted from a caller. */
data class ReviewedPolicyWorkflowProposalRequest(
    val fence: PolicyReviewFence,
    val consumingAssistantId: Uuid,
    val expectedGrantStateVersion: Long,
    val frozenNowMs: Long,
) {
    init {
        require(expectedGrantStateVersion > 0L)
        require(frozenNowMs >= 0L)
        if (fence.scope is LearningScope.Assistant) {
            require(fence.scope.assistantId == consumingAssistantId)
        }
    }

    override fun toString(): String =
        "ReviewedPolicyWorkflowProposalRequest(scope=${fence.scope.kind}, " +
            "policyVersion=${fence.stateVersion}, grantVersion=$expectedGrantStateVersion, " +
            "ids=<redacted>)"
}

enum class ReviewedPolicyWorkflowProposalRejection {
    POLICY_FENCE_CONFLICT,
    POLICY_NOT_ACTIVE,
    POLICY_PROFILE_NOT_APPLICABLE,
    POLICY_EVIDENCE_INVALID,
    GRANT_NOT_EXACT,
    CURRENT_AUTHORITY_MISMATCH,
    CURRENT_TOOL_SCHEMA_MISMATCH,
}

enum class ReviewedPolicyWorkflowProposalUnavailableReason {
    RUNTIME_UNAVAILABLE,
    AUTHORITY_UNAVAILABLE,
    STORAGE_FAILURE,
}

sealed interface ReviewedPolicyWorkflowProposalResult {
    data class Ready(val proposal: LearnedPolicyProposal) :
        ReviewedPolicyWorkflowProposalResult

    data class Rejected(val reason: ReviewedPolicyWorkflowProposalRejection) :
        ReviewedPolicyWorkflowProposalResult

    data class Unavailable(val reason: ReviewedPolicyWorkflowProposalUnavailableReason) :
        ReviewedPolicyWorkflowProposalResult
}

fun interface ReviewedPolicyWorkflowProposalPort {
    /** Produces only the closed SAFE_TIME_INFO_V1 proposal shape. */
    suspend fun prepareExact(
        request: ReviewedPolicyWorkflowProposalRequest,
    ): ReviewedPolicyWorkflowProposalResult
}

/**
 * Content-free source evidence plus the current reviewed Policy summaries. Conversation, message,
 * lesson, trace, reward and tool-output bodies are deliberately absent.
 */
data class ExactReviewedPolicyWorkflowSource(
    val policyId: String,
    val policyStateVersion: Long,
    val policyRevision: Long,
    val policyArtifactSha256: String,
    val scope: LearningScope,
    val policyType: String,
    val trigger: String,
    val procedure: String,
    val verification: String,
    val boundary: String,
    val evidence: List<LearnedPolicyWorkflowEvidenceAnchor>,
    val applicableToolSchemaSha256: String,
    val producerProviderIdentity: String,
    val producerModelIdentity: String,
    val producerConfigurationIdentity: String,
    val producerConfigGeneration: Long,
    val producerPromptIdentity: String,
) {
    init {
        require(policyId.length in 1..256)
        require(policyStateVersion > 0L && policyRevision > 0L)
        require(policyArtifactSha256.isLowerSha256())
        require(scope is LearningScope.Assistant || scope is LearningScope.AuthoritySubject)
        require(policyType.isNotBlank())
        listOf(trigger, procedure, verification, boundary).forEach { reviewed ->
            require(reviewed.isNotBlank() && reviewed.length <= MAX_REVIEWED_FIELD_CHARS)
        }
        require(evidence.isNotEmpty() && evidence.size <= MAX_PROPOSAL_EVIDENCE)
        require(evidence.map { it.evidenceId }.distinct().size == evidence.size)
        require(evidence.any {
            it.polarity == LearnedPolicyWorkflowEvidencePolarity.POSITIVE
        })
        require(applicableToolSchemaSha256.isLowerSha256())
        listOf(
            producerProviderIdentity,
            producerModelIdentity,
            producerConfigurationIdentity,
        ).forEach { require(it.isLowerSha256()) }
        require(producerConfigGeneration >= 0L)
        require(producerPromptIdentity.isSafeVersion())
    }

    override fun toString(): String =
        "ExactReviewedPolicyWorkflowSource(scope=${scope.kind}, " +
            "policyVersion=$policyStateVersion, evidence=${evidence.size}, text=<redacted>, " +
            "ids=<redacted>)"
}

sealed interface ReviewedPolicyWorkflowSourceResult {
    data class Ready(val source: ExactReviewedPolicyWorkflowSource) :
        ReviewedPolicyWorkflowSourceResult

    data class Rejected(val reason: ReviewedPolicyWorkflowProposalRejection) :
        ReviewedPolicyWorkflowSourceResult

    data object Unavailable : ReviewedPolicyWorkflowSourceResult
}

/** LearningDatabase-only boundary implemented by LearningRuntimeFacade. */
fun interface ReviewedPolicyWorkflowSourceRuntimePort {
    suspend fun readExactReviewedPolicyWorkflowSource(
        request: ReviewedPolicyWorkflowProposalRequest,
    ): ReviewedPolicyWorkflowSourceResult
}

/**
 * Cross-database proposal composition. The Learning runtime supplies a fenced Policy/evidence
 * projection; AppDatabase supplies the current grant; the host authority supplies the current
 * assistant and tool catalogue. Every mutable input is re-read before returning.
 */
class ProductionReviewedPolicyWorkflowProposalPort(
    private val runtime: ReviewedPolicyWorkflowSourceRuntimePort,
    private val grants: PolicyGrantAuthoritySource,
    private val workflowAuthority: WorkflowSubmissionAuthorityPort,
) : ReviewedPolicyWorkflowProposalPort {
    override suspend fun prepareExact(
        request: ReviewedPolicyWorkflowProposalRequest,
    ): ReviewedPolicyWorkflowProposalResult = try {
        val initialSource = when (
            val read = runtime.readExactReviewedPolicyWorkflowSource(request)
        ) {
            is ReviewedPolicyWorkflowSourceResult.Ready -> read.source
            is ReviewedPolicyWorkflowSourceResult.Rejected -> return rejected(read.reason)
            ReviewedPolicyWorkflowSourceResult.Unavailable -> return runtimeUnavailable()
        }
        if (!initialSource.matches(request.fence)) {
            return rejected(ReviewedPolicyWorkflowProposalRejection.POLICY_FENCE_CONFLICT)
        }
        val streamId = request.fence.sourceStreamId
            ?: return rejected(ReviewedPolicyWorkflowProposalRejection.GRANT_NOT_EXACT)
        val authorityRows = grants.listExactGranted(
            scope = request.fence.scope,
            consumingAssistantId = request.consumingAssistantId,
            sourceStreamId = streamId,
            limit = MAX_POLICY_GRANT_AUTHORITY_RESULTS,
        )
        val exactGrant = authorityRows.singleOrNull { grant ->
            grant.state == PolicyGrantAuthorityState.GRANTED &&
                grant.stateVersion == request.expectedGrantStateVersion &&
                grant.sourceStreamId == streamId &&
                grant.scope == request.fence.scope &&
                grant.consumingAssistantId == request.consumingAssistantId &&
                grant.policyId == request.fence.policyId &&
                grant.contentRevision == request.fence.contentRevision &&
                grant.artifactSha256 == request.fence.artifactSha256
        } ?: return rejected(ReviewedPolicyWorkflowProposalRejection.GRANT_NOT_EXACT)

        val proposal = initialSource.toSafeTimeInfoProposalOrNull(
            exactGrant = exactGrant,
            consumingAssistantId = request.consumingAssistantId,
            frozenNowMs = request.frozenNowMs,
        ) ?: return rejected(
            ReviewedPolicyWorkflowProposalRejection.POLICY_PROFILE_NOT_APPLICABLE,
        )
        val currentAuthority = workflowAuthority.loadCurrent(proposal)
            ?: return unavailable(
                ReviewedPolicyWorkflowProposalUnavailableReason.AUTHORITY_UNAVAILABLE,
            )
        if (currentAuthority.exactGrant != exactGrant) {
            return rejected(ReviewedPolicyWorkflowProposalRejection.CURRENT_AUTHORITY_MISMATCH)
        }
        val safeTool = currentAuthority.catalog.entry(SAFE_TIME_INFO_TOOL)
            ?: return rejected(
                ReviewedPolicyWorkflowProposalRejection.CURRENT_TOOL_SCHEMA_MISMATCH,
            )
        if (safeTool.schemaFingerprint != initialSource.applicableToolSchemaSha256 ||
            safeTool.externalUntrusted || !safeTool.currentlyInjectable ||
            safeTool.risk !in setOf(RiskLevel.Low, RiskLevel.Medium) ||
            ToolCallOrigin.TrustedWorkflow !in safeTool.allowedOrigins
        ) {
            return rejected(
                ReviewedPolicyWorkflowProposalRejection.CURRENT_TOOL_SCHEMA_MISMATCH,
            )
        }
        if (!workflowAuthority.revalidateExact(exactGrant)) {
            return rejected(ReviewedPolicyWorkflowProposalRejection.CURRENT_AUTHORITY_MISMATCH)
        }

        // Close the LearningDatabase side of the cross-database optimistic fence after rebuilding
        // current host authority. The submission service performs the AppDatabase fence again.
        val finalSource = when (
            val read = runtime.readExactReviewedPolicyWorkflowSource(request)
        ) {
            is ReviewedPolicyWorkflowSourceResult.Ready -> read.source
            is ReviewedPolicyWorkflowSourceResult.Rejected -> return rejected(read.reason)
            ReviewedPolicyWorkflowSourceResult.Unavailable -> return runtimeUnavailable()
        }
        if (finalSource != initialSource || !workflowAuthority.revalidateExact(exactGrant)) {
            return rejected(ReviewedPolicyWorkflowProposalRejection.POLICY_FENCE_CONFLICT)
        }
        ReviewedPolicyWorkflowProposalResult.Ready(proposal)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        unavailable(ReviewedPolicyWorkflowProposalUnavailableReason.STORAGE_FAILURE)
    }
}

data class UserReviewedPolicyWorkflowSubmissionCommand(
    val proposalRequest: ReviewedPolicyWorkflowProposalRequest,
    /** Must be set by the direct user-review action; background/model callers remain rejected. */
    val explicitUserSubmission: Boolean,
)

sealed interface UserReviewedPolicyWorkflowSubmissionResult {
    data class Submitted(val result: LearnedWorkflowSubmissionResult) :
        UserReviewedPolicyWorkflowSubmissionResult

    data object ExplicitUserSubmissionRequired : UserReviewedPolicyWorkflowSubmissionResult

    data class ProposalRejected(val reason: ReviewedPolicyWorkflowProposalRejection) :
        UserReviewedPolicyWorkflowSubmissionResult

    data class ProposalUnavailable(
        val reason: ReviewedPolicyWorkflowProposalUnavailableReason,
    ) : UserReviewedPolicyWorkflowSubmissionResult
}

fun interface UserReviewedPolicyWorkflowSubmissionService {
    suspend fun submitFromUser(
        command: UserReviewedPolicyWorkflowSubmissionCommand,
    ): UserReviewedPolicyWorkflowSubmissionResult
}

class UserReviewedPolicyWorkflowSubmissionCoordinator(
    private val proposals: ReviewedPolicyWorkflowProposalPort,
    private val submissions: LearnedWorkflowSubmissionService,
) : UserReviewedPolicyWorkflowSubmissionService {
    override suspend fun submitFromUser(
        command: UserReviewedPolicyWorkflowSubmissionCommand,
    ): UserReviewedPolicyWorkflowSubmissionResult {
        if (!command.explicitUserSubmission) {
            return UserReviewedPolicyWorkflowSubmissionResult.ExplicitUserSubmissionRequired
        }
        return when (val prepared = proposals.prepareExact(command.proposalRequest)) {
            is ReviewedPolicyWorkflowProposalResult.Ready ->
                UserReviewedPolicyWorkflowSubmissionResult.Submitted(
                    submissions.submit(
                        LearnedWorkflowSubmissionRequest(
                            proposal = prepared.proposal,
                            fixtureProfile = HostWorkflowFixtureProfile.SAFE_TIME_INFO_V1,
                            explicitUserSubmission = true,
                        ),
                        nowMs = command.proposalRequest.frozenNowMs,
                    ),
                )
            is ReviewedPolicyWorkflowProposalResult.Rejected ->
                UserReviewedPolicyWorkflowSubmissionResult.ProposalRejected(prepared.reason)
            is ReviewedPolicyWorkflowProposalResult.Unavailable ->
                UserReviewedPolicyWorkflowSubmissionResult.ProposalUnavailable(prepared.reason)
        }
    }
}

internal fun ExactReviewedPolicyWorkflowSource.toSafeTimeInfoProposalOrNull(
    exactGrant: PolicyGrantAuthoritySnapshot,
    consumingAssistantId: Uuid,
    frozenNowMs: Long,
): LearnedPolicyProposal? {
    if (policyType != PolicyCandidateType.PROCEDURE.name ||
        exactGrant.state != PolicyGrantAuthorityState.GRANTED ||
        exactGrant.scope != scope ||
        exactGrant.consumingAssistantId != consumingAssistantId ||
        exactGrant.policyId != policyId ||
        exactGrant.contentRevision != policyRevision ||
        exactGrant.artifactSha256 != policyArtifactSha256
    ) return null
    return LearnedPolicyProposal(
        policyId = policyId,
        policyRevision = policyRevision,
        policyArtifactSha256 = policyArtifactSha256,
        exactGrant = exactGrant,
        consumingAssistantId = consumingAssistantId.toString(),
        trigger = trigger,
        procedure = procedure,
        verification = verification,
        boundary = boundary,
        evidence = evidence,
        actions = listOf(
            LearnedWorkflowActionProposal(
                toolName = SAFE_TIME_INFO_TOOL,
                args = JsonObject(emptyMap()),
                timeoutSeconds = SAFE_TIME_INFO_TIMEOUT_SECONDS,
            ),
        ),
        typedSlots = emptyList(),
        name = SAFE_TIME_INFO_NAME,
        description = SAFE_TIME_INFO_DESCRIPTION,
        producerProviderIdentity = producerProviderIdentity,
        producerModelIdentity = producerModelIdentity,
        producerConfigurationIdentity = producerConfigurationIdentity,
        producerConfigGeneration = producerConfigGeneration,
        compilerVersion = SAFE_TIME_INFO_COMPILER_VERSION,
        promptVersion = producerPromptIdentity,
        templateVersion = SAFE_TIME_INFO_TEMPLATE_VERSION,
        validatorVersion = SAFE_TIME_INFO_VALIDATOR_VERSION,
        verifierVersion = WORKFLOW_CANDIDATE_VERIFIER_VERSION,
        maxOutputUtf8Bytes = SAFE_TIME_INFO_MAX_OUTPUT_UTF8_BYTES,
        frozenNowMs = frozenNowMs,
    )
}

private fun ExactReviewedPolicyWorkflowSource.matches(fence: PolicyReviewFence): Boolean =
    policyId == fence.policyId && policyStateVersion == fence.stateVersion &&
        policyRevision == fence.contentRevision &&
        policyArtifactSha256 == fence.artifactSha256 && scope == fence.scope

private fun rejected(reason: ReviewedPolicyWorkflowProposalRejection) =
    ReviewedPolicyWorkflowProposalResult.Rejected(reason)

private fun unavailable(reason: ReviewedPolicyWorkflowProposalUnavailableReason) =
    ReviewedPolicyWorkflowProposalResult.Unavailable(reason)

private fun runtimeUnavailable() = unavailable(
    ReviewedPolicyWorkflowProposalUnavailableReason.RUNTIME_UNAVAILABLE,
)

private fun String.isLowerSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private fun String.isSafeVersion(): Boolean =
    length in 1..160 && isNotBlank() && none { it.code < 0x20 || it == '\u007f' }

internal const val MAX_PROPOSAL_EVIDENCE: Int = 64
internal const val SAFE_TIME_INFO_TOOL: String = "get_time_info"
private const val MAX_REVIEWED_FIELD_CHARS = 4_096
private const val SAFE_TIME_INFO_TIMEOUT_SECONDS = 30
private const val SAFE_TIME_INFO_MAX_OUTPUT_UTF8_BYTES = 1_024
private const val SAFE_TIME_INFO_NAME = "Reviewed local time"
private const val SAFE_TIME_INFO_DESCRIPTION = "Disabled manual candidate from an exact reviewed Policy"
private const val SAFE_TIME_INFO_COMPILER_VERSION = "workflow-compiler-v1"
private const val SAFE_TIME_INFO_TEMPLATE_VERSION = "workflow-safe-time-info-template-v1"
private const val SAFE_TIME_INFO_VALIDATOR_VERSION = "workflow-validator-v1"
