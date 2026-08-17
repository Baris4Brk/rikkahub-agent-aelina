package me.rerere.rikkahub.learning.workflow.review

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidateState

const val MAX_WORKFLOW_REVIEW_PAGE_SIZE: Int = 50
const val MAX_WORKFLOW_REVIEW_REVISIONS: Int = 30
const val MAX_WORKFLOW_REVIEW_ACTIONS: Int = 8

/** Exact candidate identity approved by the user. Every mutation is fenced by all four values. */
data class WorkflowReviewFence(
    val candidateId: String,
    val candidateVersion: Long,
    val stateVersion: Long,
    val artifactSha256: String,
) {
    init {
        require(candidateId.length in 1..128)
        require(candidateVersion > 0L && stateVersion >= candidateVersion)
        require(artifactSha256.matches(Regex("[0-9a-f]{64}")))
    }

    override fun toString(): String =
        "WorkflowReviewFence(candidateVersion=$candidateVersion, stateVersion=$stateVersion, " +
            "ids=<redacted>)"
}

data class WorkflowReviewListItem(
    val fence: WorkflowReviewFence,
    val state: LearnedWorkflowCandidateState,
    val sourcePolicyId: String,
    val sourcePolicyRevision: Long,
    val evidenceCount: Int,
    val triggerSummary: String,
    val actionCount: Int,
    val fakeVerificationPassed: Boolean,
    val updatedAtMs: Long,
) {
    init {
        require(sourcePolicyId.length in 1..256 && sourcePolicyRevision > 0L)
        require(evidenceCount in 1..64)
        require(triggerSummary.length <= 2_048)
        require(actionCount in 1..MAX_WORKFLOW_REVIEW_ACTIONS)
        require(updatedAtMs >= 0L)
    }
}

data class WorkflowReviewSlot(
    val name: String,
    val type: String,
    val required: Boolean,
    val displayValue: String,
    val isSecretReference: Boolean,
)

/** Parameters are normalized JSON and already redacted by the trusted read side. */
data class WorkflowReviewAction(
    val index: Int,
    val toolName: String,
    val normalizedParameters: String,
    val secretReferenceMasked: Boolean,
    val capabilities: List<String>,
    val risk: String,
    val origin: String,
    val schemaSha256: String,
) {
    init {
        require(index in 0 until MAX_WORKFLOW_REVIEW_ACTIONS)
        require(toolName.length in 1..256)
        require(normalizedParameters.toByteArray(Charsets.UTF_8).size <= 16 * 1_024)
        require(capabilities.size <= 64 && capabilities == capabilities.distinct().sorted())
        require(risk.length in 1..64 && origin.length in 1..64)
        require(schemaSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

data class WorkflowReviewFakeReport(
    val status: String,
    val verifierVersion: String,
    val fixtureSetSha256: String,
    val passedChecks: Int,
    val failedChecks: Int,
    val failureCodes: List<String>,
    val completedAtMs: Long,
) {
    init {
        require(status.length in 1..32 && verifierVersion.length in 1..160)
        require(fixtureSetSha256.matches(Regex("[0-9a-f]{64}")))
        require(passedChecks >= 0 && failedChecks >= 0)
        require(failureCodes.size <= 64)
        require(completedAtMs >= 0L)
    }
}

enum class WorkflowEnableImpact {
    /** Manual trigger only; each action still passes the normal capability and execution gates. */
    MANUAL_TRIGGER_GATED_ACTIONS,
}

data class WorkflowReviewRevision(
    val candidateVersion: Long,
    val stateVersion: Long,
    val state: LearnedWorkflowCandidateState,
    val artifactSha256: String,
    val previousArtifactSha256: String?,
    val reasonCode: String,
    val actor: String,
    val createdAtMs: Long,
    val isCurrent: Boolean,
) {
    init {
        require(candidateVersion > 0L && stateVersion >= candidateVersion)
        require(artifactSha256.matches(Regex("[0-9a-f]{64}")))
        require(previousArtifactSha256 == null ||
            previousArtifactSha256.matches(Regex("[0-9a-f]{64}")))
        require(reasonCode.length in 1..64 && actor.length in 1..64)
        require(createdAtMs >= 0L)
    }
}

data class WorkflowReviewDetail(
    val item: WorkflowReviewListItem,
    val assistantId: String,
    val authoritySubjectId: String?,
    val sourcePolicyArtifactSha256: String,
    val sourceGrantDigest: String,
    val positiveAnchorEvidenceId: String,
    val evidenceIds: List<String>,
    val trigger: String,
    val conditions: List<String>,
    val slots: List<WorkflowReviewSlot>,
    val actions: List<WorkflowReviewAction>,
    val capabilitySnapshot: List<String>,
    val fakeReport: WorkflowReviewFakeReport?,
    val producerProviderIdentity: String,
    val producerModelIdentity: String,
    val compilerVersion: String,
    val templateVersion: String,
    val validatorVersion: String,
    val enableImpact: WorkflowEnableImpact,
    /** Non-null only after the exact learned workflow exists and remains disabled. */
    val installedWorkflowStateVersion: Long?,
    val revisions: List<WorkflowReviewRevision>,
) {
    init {
        require(runCatching { Uuid.parse(assistantId).toString() == assistantId }.getOrDefault(false))
        require(authoritySubjectId == null || authoritySubjectId.length in 1..256)
        require(sourcePolicyArtifactSha256.matches(Regex("[0-9a-f]{64}")))
        require(sourceGrantDigest.matches(Regex("[0-9a-f]{64}")))
        require(evidenceIds.isNotEmpty() && evidenceIds.size <= 64)
        require(evidenceIds == evidenceIds.distinct().sorted())
        require(positiveAnchorEvidenceId in evidenceIds)
        require(conditions.size <= 32 && slots.size <= 32)
        require(actions.isNotEmpty() && actions.size <= MAX_WORKFLOW_REVIEW_ACTIONS)
        require(capabilitySnapshot == capabilitySnapshot.distinct().sorted())
        require(installedWorkflowStateVersion == null || installedWorkflowStateVersion > 0L)
        require(revisions.size <= MAX_WORKFLOW_REVIEW_REVISIONS)
    }

    val canPromoteDisabled: Boolean
        get() = item.state == LearnedWorkflowCandidateState.VERIFIED &&
            fakeReport?.status == "PASSED"

    val canEnable: Boolean
        get() = item.state == LearnedWorkflowCandidateState.PROMOTED_DISABLED &&
            fakeReport?.status == "PASSED" && installedWorkflowStateVersion != null
}

enum class WorkflowReviewUnavailableReason {
    FEATURE_DISABLED,
    WRONG_PROCESS,
    RUNTIME_NOT_READY,
    RESTORE_IN_PROGRESS,
    STORAGE_FAILURE,
    AUTHORITY_UNAVAILABLE,
    VALIDATION_UNAVAILABLE,
    ACTION_NOT_ALLOWED,
}

sealed interface WorkflowReviewReadResult<out T> {
    data class Ready<T>(val value: T) : WorkflowReviewReadResult<T>
    data object NotFound : WorkflowReviewReadResult<Nothing>
    data class Unavailable(val reason: WorkflowReviewUnavailableReason) :
        WorkflowReviewReadResult<Nothing>
}

data class PromoteWorkflowDisabledCommand(
    val consumingAssistantId: Uuid,
    val fence: WorkflowReviewFence,
)

data class EnablePromotedWorkflowCommand(
    val consumingAssistantId: Uuid,
    val fence: WorkflowReviewFence,
    val expectedWorkflowStateVersion: Long,
    /** Set only by the second confirmation dialog; the repository rejects false. */
    val explicitUserConfirmation: Boolean,
) {
    init {
        require(expectedWorkflowStateVersion > 0L)
    }
}

sealed interface WorkflowReviewMutationResult {
    data class PromotedDisabled(val workflowId: String, val replayed: Boolean) :
        WorkflowReviewMutationResult
    data class Enabled(val workflowId: String) : WorkflowReviewMutationResult
    data object Conflict : WorkflowReviewMutationResult
    data class Rejected(val reasonCode: String) : WorkflowReviewMutationResult
    data class Unavailable(val reason: WorkflowReviewUnavailableReason) :
        WorkflowReviewMutationResult
}

/** UI-facing boundary. Implementations re-read candidate, authority, schema and workflow CAS heads. */
interface WorkflowReviewRepository {
    suspend fun list(
        consumingAssistantId: Uuid,
        limit: Int = MAX_WORKFLOW_REVIEW_PAGE_SIZE,
    ): WorkflowReviewReadResult<List<WorkflowReviewListItem>>

    suspend fun detail(
        consumingAssistantId: Uuid,
        candidateId: String,
    ): WorkflowReviewReadResult<WorkflowReviewDetail>

    suspend fun promoteDisabled(
        command: PromoteWorkflowDisabledCommand,
    ): WorkflowReviewMutationResult

    suspend fun enable(
        command: EnablePromotedWorkflowCommand,
    ): WorkflowReviewMutationResult
}

/** Narrow LearningDatabase port; LearningRuntimeFacade owns its minimal implementation. */
interface WorkflowReviewRuntimePort {
    suspend fun listWorkflowCandidates(
        consumingAssistantId: Uuid,
        limit: Int,
    ): WorkflowReviewReadResult<List<WorkflowReviewListItem>>

    suspend fun readWorkflowCandidate(
        consumingAssistantId: Uuid,
        candidateId: String,
    ): WorkflowReviewReadResult<WorkflowReviewDetail>
}
