package me.rerere.rikkahub.learning.workflow.runtime

import me.rerere.rikkahub.learning.grant.PolicyGrantAuthoritySnapshot
import me.rerere.rikkahub.learning.policy.LearnedPolicyProposal
import me.rerere.rikkahub.learning.verification.FakeWorkflowToolRegistry
import me.rerere.rikkahub.learning.verification.WorkflowReplayFixture
import me.rerere.rikkahub.learning.workflow.LearnedWorkflowAuthorityResolver
import me.rerere.rikkahub.learning.workflow.LearnedWorkflowFakeAdapterRegistry
import me.rerere.rikkahub.learning.workflow.model.LearnedWorkflowCandidate
import me.rerere.rikkahub.toolcatalog.ToolCatalogSnapshot

/** Closed host-owned profiles. Callers cannot select an arbitrary fixture implementation. */
enum class HostWorkflowFixtureProfile {
    SAFE_TIME_INFO_V1,
}

data class LearnedWorkflowSubmissionRequest(
    val proposal: LearnedPolicyProposal,
    val fixtureProfile: HostWorkflowFixtureProfile,
    /** This API is never a background/model side effect. */
    val explicitUserSubmission: Boolean,
)

data class WorkflowSubmissionAuthorityContext(
    /** Must be byte-for-byte/value-equal to [LearnedPolicyProposal.exactGrant]. */
    val exactGrant: PolicyGrantAuthoritySnapshot,
    val catalog: ToolCatalogSnapshot,
    /** A fresh, exact assistant/authority-subject lookup owned by the host. */
    val authorityResolver: LearnedWorkflowAuthorityResolver,
)

interface WorkflowSubmissionAuthorityPort {
    /** Rebuilds the current assistant tool surface and reads the current AppDatabase grant. */
    suspend fun loadCurrent(
        proposal: LearnedPolicyProposal,
    ): WorkflowSubmissionAuthorityContext?

    /** The last call before a terminal candidate CAS. */
    suspend fun revalidateExact(snapshot: PolicyGrantAuthoritySnapshot): Boolean
}

data class HostWorkflowFixtureBundle(
    val profile: HostWorkflowFixtureProfile,
    val hostVersion: String,
    val fixtures: List<WorkflowReplayFixture>,
    val fakeTools: FakeWorkflowToolRegistry,
    val validatorAdapters: LearnedWorkflowFakeAdapterRegistry,
) {
    init {
        require(hostVersion.matches(SAFE_HOST_VERSION))
        // An empty/misconfigured host bundle remains representable so the verifier can produce
        // a durable ABSTAIN receipt. The production provider below always supplies a real case.
        require(fixtures.size <= MAX_HOST_FIXTURES)
        require(fixtures.map(WorkflowReplayFixture::fixtureId).distinct().size == fixtures.size)
    }

    private companion object {
        val SAFE_HOST_VERSION = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
        const val MAX_HOST_FIXTURES = 16
    }
}

fun interface HostWorkflowFixtureProvider {
    /** Returns null for every unknown profile/tool/schema/shape. */
    fun resolve(
        profile: HostWorkflowFixtureProfile,
        candidate: LearnedWorkflowCandidate,
        catalog: ToolCatalogSnapshot,
    ): HostWorkflowFixtureBundle?
}

enum class WorkflowCandidateTransition {
    VALIDATION_STARTED,
    VALIDATION_PASSED,
    VALIDATION_FAILED,
}

sealed interface WorkflowCandidateInsertResult {
    data class Ready(
        val candidate: LearnedWorkflowCandidate,
        val inserted: Boolean,
    ) : WorkflowCandidateInsertResult

    data object Conflict : WorkflowCandidateInsertResult
    data object Unavailable : WorkflowCandidateInsertResult
}

sealed interface WorkflowCandidateReadResult {
    data class Ready(val candidate: LearnedWorkflowCandidate) : WorkflowCandidateReadResult
    data object Missing : WorkflowCandidateReadResult
    data object Unavailable : WorkflowCandidateReadResult
}

sealed interface WorkflowCandidateTransitionResult {
    data class Applied(val candidate: LearnedWorkflowCandidate) : WorkflowCandidateTransitionResult
    data object Conflict : WorkflowCandidateTransitionResult
    data object Unavailable : WorkflowCandidateTransitionResult
}

/** LearningDatabase boundary. Implementations append the matching revision in the same transaction. */
interface WorkflowCandidateRuntimeStore {
    suspend fun insertCompiledExact(
        candidate: LearnedWorkflowCandidate,
    ): WorkflowCandidateInsertResult

    suspend fun readExact(candidateId: String): WorkflowCandidateReadResult

    suspend fun transitionExact(
        expected: LearnedWorkflowCandidate,
        next: LearnedWorkflowCandidate,
        transition: WorkflowCandidateTransition,
    ): WorkflowCandidateTransitionResult
}

/** Runtime-facade port; keeps Room/DAO handles inside the LearningDatabase session. */
interface WorkflowCandidateSubmissionRuntime : WorkflowCandidateRuntimeStore

enum class LearnedWorkflowSubmissionFailure {
    ROLLOUT_DISABLED,
    EXPLICIT_USER_SUBMISSION_REQUIRED,
    AUTHORITY_UNAVAILABLE,
    AUTHORITY_MISMATCH,
    PROFILE_NOT_APPLICABLE,
    COMPILE_REJECTED,
    CANDIDATE_CONFLICT,
    VALIDATION_REJECTED,
    VERIFICATION_FAILED,
    VERIFICATION_ABSTAINED,
    STORAGE_UNAVAILABLE,
    UNKNOWN,
}

sealed interface LearnedWorkflowSubmissionResult {
    data class Verified(
        val candidateId: String,
        val candidateVersion: Long,
        val stateVersion: Long,
        val replayed: Boolean,
    ) : LearnedWorkflowSubmissionResult

    data class Rejected(
        val candidateId: String?,
        val failure: LearnedWorkflowSubmissionFailure,
        val detailCode: String? = null,
        val replayed: Boolean = false,
    ) : LearnedWorkflowSubmissionResult

    data class Unavailable(
        val failure: LearnedWorkflowSubmissionFailure,
    ) : LearnedWorkflowSubmissionResult
}

fun interface LearnedWorkflowSubmissionService {
    suspend fun submit(
        request: LearnedWorkflowSubmissionRequest,
        nowMs: Long,
    ): LearnedWorkflowSubmissionResult
}
