package me.rerere.rikkahub.ui.pages.learning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.learning.review.LearningPolicyReviewRepository
import me.rerere.rikkahub.learning.review.PolicyReviewActionCommand
import me.rerere.rikkahub.learning.review.PolicyReviewActionResult
import me.rerere.rikkahub.learning.review.PolicyReviewEraseChallenge
import me.rerere.rikkahub.learning.review.PolicyReviewEraseResult
import me.rerere.rikkahub.learning.review.PolicyReviewExportResult
import me.rerere.rikkahub.learning.review.PolicyReviewReadResult
import me.rerere.rikkahub.learning.review.PolicyReviewUnavailableReason
import me.rerere.rikkahub.learning.review.ReviewedPolicyDetail
import me.rerere.rikkahub.learning.review.ReviewedPolicyListItem
import me.rerere.rikkahub.learning.model.LearningPositiveMutation
import me.rerere.rikkahub.learning.model.LearningPositiveMutationGate
import me.rerere.rikkahub.learning.workflow.runtime.LearnedWorkflowSubmissionResult
import me.rerere.rikkahub.learning.workflow.runtime.ReviewedPolicyWorkflowProposalRequest
import me.rerere.rikkahub.learning.workflow.runtime.UserReviewedPolicyWorkflowSubmissionCommand
import me.rerere.rikkahub.learning.workflow.runtime.UserReviewedPolicyWorkflowSubmissionResult
import me.rerere.rikkahub.learning.workflow.runtime.UserReviewedPolicyWorkflowSubmissionService
import kotlin.uuid.Uuid

sealed interface LearningCenterLoadState<out T> {
    data object Loading : LearningCenterLoadState<Nothing>
    data class Ready<T>(val value: T) : LearningCenterLoadState<T>
    data object NotFound : LearningCenterLoadState<Nothing>
    data class Unavailable(val reason: PolicyReviewUnavailableReason) :
        LearningCenterLoadState<Nothing>
}

sealed interface LearningCenterFeedback {
    data object Applied : LearningCenterFeedback
    data object Duplicate : LearningCenterFeedback
    data object AuthorityCommittedDerivedPending : LearningCenterFeedback
    data object Conflict : LearningCenterFeedback
    data class Unavailable(val reason: PolicyReviewUnavailableReason) : LearningCenterFeedback
    data object Erased : LearningCenterFeedback
    data object ExportReady : LearningCenterFeedback
    data object WorkflowCandidateVerified : LearningCenterFeedback
    data class WorkflowCandidateRejected(val reason: String) : LearningCenterFeedback
    data class WorkflowCandidateUnavailable(val reason: String) : LearningCenterFeedback
}

class LearningCenterVM(
    id: String,
    private val settingsStore: SettingsStore,
    private val repository: LearningPolicyReviewRepository,
    private val workflowSubmission: UserReviewedPolicyWorkflowSubmissionService,
    private val positiveMutations: LearningPositiveMutationGate,
) : ViewModel() {
    val assistantId: Uuid = Uuid.parse(id)
    val assistantName = settingsStore.settingsFlow
        .map { settings -> settings.getAssistantById(assistantId)?.name.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val authorityScopeEraseAvailable = combine(
        settingsStore.settingsFlow,
        SecondUserAuthorityRegistry.flow,
    ) { settings, authority ->
        !settings.init && authority?.assistantId == assistantId &&
            settings.assistants.singleOrNull { it.id == assistantId } != null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val policyPositiveActionsEnabled = settingsStore.settingsFlow.map {
        positiveMutations.allows(LearningPositiveMutation.POLICY_APPROVE_OR_RESUME)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val workflowCandidateActionEnabled = settingsStore.settingsFlow.map {
        positiveMutations.allows(LearningPositiveMutation.WORKFLOW_CANDIDATE_SUBMISSION)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val listState = MutableStateFlow<LearningCenterLoadState<List<ReviewedPolicyListItem>>>(
        LearningCenterLoadState.Loading,
    )
    val detailState = MutableStateFlow<LearningCenterLoadState<ReviewedPolicyDetail>>(
        LearningCenterLoadState.Loading,
    )
    val busyPolicyId = MutableStateFlow<String?>(null)
    val feedback = MutableStateFlow<LearningCenterFeedback?>(null)
    val eraseChallenge = MutableStateFlow<PolicyReviewEraseChallenge?>(null)
    val exportedReport = MutableStateFlow<String?>(null)

    init {
        refreshList()
    }

    fun refreshList() {
        viewModelScope.launch(Dispatchers.IO) {
            listState.value = LearningCenterLoadState.Loading
            listState.value = repository.list(assistantId).toLoadState()
        }
    }

    fun loadDetail(policyId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            detailState.value = LearningCenterLoadState.Loading
            detailState.value = repository.detail(assistantId, policyId).toLoadState()
        }
    }

    fun approve(detail: ReviewedPolicyDetail) = perform(detail) { command ->
        if (!positiveMutations.allows(LearningPositiveMutation.POLICY_APPROVE_OR_RESUME)) {
            return@perform PolicyReviewActionResult.Unavailable(
                PolicyReviewUnavailableReason.FEATURE_DISABLED,
            )
        }
        repository.approve(command)
    }

    fun revoke(detail: ReviewedPolicyDetail) = perform(detail) { command ->
        repository.revoke(command)
    }

    fun suspendPolicy(detail: ReviewedPolicyDetail) = perform(detail) { command ->
        repository.suspendPolicy(command)
    }

    fun archive(detail: ReviewedPolicyDetail) = perform(detail) { command ->
        repository.archive(command)
    }

    fun restoreRevision(detail: ReviewedPolicyDetail, revision: Long) =
        perform(detail) { command ->
            if (!positiveMutations.allows(
                    LearningPositiveMutation.POLICY_RESTORE_ARCHIVED_REVISION,
                )
            ) {
                return@perform PolicyReviewActionResult.Unavailable(
                    PolicyReviewUnavailableReason.FEATURE_DISABLED,
                )
            }
            repository.restoreRevision(command, revision)
        }

    fun requestErase(detail: ReviewedPolicyDetail) {
        requestEraseScope(
            scope = detail.policy.item.fence.scope,
            key = "erase:${detail.policy.item.fence.policyId}",
        )
    }

    /** Exact assistant-scope erase remains available even when no Policy row exists. */
    fun requestAssistantScopeErase() {
        requestEraseScope(
            scope = me.rerere.rikkahub.learning.model.LearningScope.Assistant(assistantId),
            key = "erase-scope:$assistantId",
        )
    }

    /** Active epoch is re-read at click time; the raw subject ID is never projected to UI. */
    fun requestAuthorityScopeErase() {
        val authority = SecondUserAuthorityRegistry.current() ?: run {
            feedback.value = LearningCenterFeedback.Unavailable(
                PolicyReviewUnavailableReason.ACTION_NOT_ALLOWED,
            )
            return
        }
        val settings = settingsStore.settingsFlow.value
        val authorized = !settings.init && authority.assistantId == assistantId &&
            settings.assistants.singleOrNull { it.id == assistantId } != null
        if (!authorized) {
            feedback.value = LearningCenterFeedback.Unavailable(
                PolicyReviewUnavailableReason.ACTION_NOT_ALLOWED,
            )
            return
        }
        requestEraseScope(
            scope = me.rerere.rikkahub.learning.model.LearningScope.AuthoritySubject(
                authority.subjectId,
            ),
            key = "erase-authority-scope:${authority.authorityEpoch}",
        )
    }

    private fun requestEraseScope(
        scope: me.rerere.rikkahub.learning.model.LearningScope,
        key: String,
    ) {
        if (!busyPolicyId.compareAndSet(null, key)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = repository.issueEraseChallenge(scope)) {
                    is PolicyReviewReadResult.Ready -> eraseChallenge.value = result.value
                    PolicyReviewReadResult.NotFound -> feedback.value = LearningCenterFeedback.Conflict
                    is PolicyReviewReadResult.Unavailable -> {
                        feedback.value = LearningCenterFeedback.Unavailable(result.reason)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback.value = LearningCenterFeedback.Unavailable(
                    PolicyReviewUnavailableReason.STORAGE_FAILURE,
                )
            } finally {
                busyPolicyId.compareAndSet(key, null)
            }
        }
    }

    fun cancelErase() {
        eraseChallenge.value = null
    }

    fun confirmErase(policyId: String? = null) {
        val challenge = eraseChallenge.value ?: return
        val key = "erase-confirm:${policyId ?: assistantId}"
        if (!busyPolicyId.compareAndSet(null, key)) return
        eraseChallenge.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = repository.erase(challenge)) {
                    is PolicyReviewEraseResult.Erased -> {
                        feedback.value = LearningCenterFeedback.Erased
                        listState.value = repository.list(assistantId).toLoadState()
                        if (policyId != null) {
                            detailState.value = repository.detail(assistantId, policyId).toLoadState()
                        }
                    }
                    PolicyReviewEraseResult.Conflict -> {
                        feedback.value = LearningCenterFeedback.Conflict
                    }
                    is PolicyReviewEraseResult.Unavailable -> {
                        feedback.value = LearningCenterFeedback.Unavailable(result.reason)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback.value = LearningCenterFeedback.Unavailable(
                    PolicyReviewUnavailableReason.STORAGE_FAILURE,
                )
            } finally {
                busyPolicyId.compareAndSet(key, null)
            }
        }
    }

    fun exportRedacted(detail: ReviewedPolicyDetail) {
        val policyId = detail.policy.item.fence.policyId
        val key = "export:$policyId"
        if (!busyPolicyId.compareAndSet(null, key)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = repository.exportRedacted(assistantId, policyId)) {
                    is PolicyReviewExportResult.Ready -> {
                        exportedReport.value = result.redactedReport
                        feedback.value = LearningCenterFeedback.ExportReady
                    }
                    PolicyReviewExportResult.NotFound -> {
                        feedback.value = LearningCenterFeedback.Conflict
                    }
                    is PolicyReviewExportResult.Unavailable -> {
                        feedback.value = LearningCenterFeedback.Unavailable(result.reason)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback.value = LearningCenterFeedback.Unavailable(
                    PolicyReviewUnavailableReason.STORAGE_FAILURE,
                )
            } finally {
                busyPolicyId.compareAndSet(key, null)
            }
        }
    }

    /** Direct user action; the backend accepts only the fixed SAFE_TIME_INFO_V1 proposal. */
    fun submitWorkflowCandidate(detail: ReviewedPolicyDetail) {
        if (!positiveMutations.allows(
                LearningPositiveMutation.WORKFLOW_CANDIDATE_SUBMISSION,
            )
        ) {
            feedback.value = LearningCenterFeedback.WorkflowCandidateUnavailable(
                "ROLLOUT_DISABLED",
            )
            return
        }
        val fence = detail.policy.item.fence
        val key = "workflow-submit:${fence.policyId}"
        if (!busyPolicyId.compareAndSet(null, key)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = workflowSubmission.submitFromUser(
                    UserReviewedPolicyWorkflowSubmissionCommand(
                        proposalRequest = ReviewedPolicyWorkflowProposalRequest(
                            fence = fence,
                            consumingAssistantId = assistantId,
                            expectedGrantStateVersion = detail.grant.stateVersion,
                            frozenNowMs = System.currentTimeMillis().coerceAtLeast(0L),
                        ),
                        explicitUserSubmission = true,
                    ),
                )
                feedback.value = when (result) {
                    is UserReviewedPolicyWorkflowSubmissionResult.Submitted -> when (
                        val terminal = result.result
                    ) {
                        is LearnedWorkflowSubmissionResult.Verified ->
                            LearningCenterFeedback.WorkflowCandidateVerified
                        is LearnedWorkflowSubmissionResult.Rejected ->
                            LearningCenterFeedback.WorkflowCandidateRejected(terminal.failure.name)
                        is LearnedWorkflowSubmissionResult.Unavailable ->
                            LearningCenterFeedback.WorkflowCandidateUnavailable(terminal.failure.name)
                    }
                    UserReviewedPolicyWorkflowSubmissionResult.ExplicitUserSubmissionRequired ->
                        LearningCenterFeedback.WorkflowCandidateRejected(
                            "EXPLICIT_USER_SUBMISSION_REQUIRED",
                        )
                    is UserReviewedPolicyWorkflowSubmissionResult.ProposalRejected ->
                        LearningCenterFeedback.WorkflowCandidateRejected(result.reason.name)
                    is UserReviewedPolicyWorkflowSubmissionResult.ProposalUnavailable ->
                        LearningCenterFeedback.WorkflowCandidateUnavailable(result.reason.name)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback.value = LearningCenterFeedback.WorkflowCandidateUnavailable(
                    "STORAGE_FAILURE",
                )
            } finally {
                busyPolicyId.compareAndSet(key, null)
            }
        }
    }

    fun clearExport() {
        exportedReport.value = null
    }

    fun clearFeedback() {
        feedback.value = null
    }

    private fun perform(
        detail: ReviewedPolicyDetail,
        action: suspend (PolicyReviewActionCommand) -> PolicyReviewActionResult,
    ) {
        val fence = detail.policy.item.fence
        if (!busyPolicyId.compareAndSet(null, fence.policyId)) return
        val command = PolicyReviewActionCommand(
            fence = fence,
            consumingAssistantId = assistantId,
            expectedGrantStateVersion = detail.grant.stateVersion,
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = action(command)) {
                    PolicyReviewActionResult.Applied -> {
                        feedback.value = LearningCenterFeedback.Applied
                        refreshAfterWrite(fence.policyId)
                    }
                    PolicyReviewActionResult.Duplicate -> {
                        feedback.value = LearningCenterFeedback.Duplicate
                        refreshAfterWrite(fence.policyId)
                    }
                    PolicyReviewActionResult.AuthorityCommittedDerivedPending -> {
                        feedback.value = LearningCenterFeedback.AuthorityCommittedDerivedPending
                        refreshAfterWrite(fence.policyId)
                    }
                    PolicyReviewActionResult.Conflict -> {
                        feedback.value = LearningCenterFeedback.Conflict
                        refreshAfterWrite(fence.policyId)
                    }
                    is PolicyReviewActionResult.Unavailable -> {
                        feedback.value = LearningCenterFeedback.Unavailable(result.reason)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback.value = LearningCenterFeedback.Unavailable(
                    PolicyReviewUnavailableReason.STORAGE_FAILURE,
                )
            } finally {
                busyPolicyId.compareAndSet(fence.policyId, null)
            }
        }
    }

    private suspend fun refreshAfterWrite(policyId: String) {
        listState.value = repository.list(assistantId).toLoadState()
        detailState.value = repository.detail(assistantId, policyId).toLoadState()
    }
}

private fun <T> PolicyReviewReadResult<T>.toLoadState(): LearningCenterLoadState<T> = when (this) {
    is PolicyReviewReadResult.Ready -> LearningCenterLoadState.Ready(value)
    PolicyReviewReadResult.NotFound -> LearningCenterLoadState.NotFound
    is PolicyReviewReadResult.Unavailable -> LearningCenterLoadState.Unavailable(reason)
}
