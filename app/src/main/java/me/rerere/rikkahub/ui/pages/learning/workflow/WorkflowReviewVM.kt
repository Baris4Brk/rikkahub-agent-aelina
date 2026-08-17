package me.rerere.rikkahub.ui.pages.learning.workflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.learning.model.LearningPositiveMutation
import me.rerere.rikkahub.learning.model.LearningPositiveMutationGate
import me.rerere.rikkahub.learning.workflow.review.EnablePromotedWorkflowCommand
import me.rerere.rikkahub.learning.workflow.review.PromoteWorkflowDisabledCommand
import me.rerere.rikkahub.learning.workflow.review.WorkflowReviewDetail
import me.rerere.rikkahub.learning.workflow.review.WorkflowReviewListItem
import me.rerere.rikkahub.learning.workflow.review.WorkflowReviewMutationResult
import me.rerere.rikkahub.learning.workflow.review.WorkflowReviewReadResult
import me.rerere.rikkahub.learning.workflow.review.WorkflowReviewRepository
import me.rerere.rikkahub.learning.workflow.review.WorkflowReviewUnavailableReason
import kotlin.uuid.Uuid

sealed interface WorkflowReviewLoadState<out T> {
    data object Loading : WorkflowReviewLoadState<Nothing>
    data class Ready<T>(val value: T) : WorkflowReviewLoadState<T>
    data object NotFound : WorkflowReviewLoadState<Nothing>
    data class Unavailable(val reason: WorkflowReviewUnavailableReason) :
        WorkflowReviewLoadState<Nothing>
}

sealed interface WorkflowReviewFeedback {
    data object PromotedDisabled : WorkflowReviewFeedback
    data object PromotionReplayed : WorkflowReviewFeedback
    data object Enabled : WorkflowReviewFeedback
    data object ConflictRefreshed : WorkflowReviewFeedback
    data class Rejected(val reasonCode: String) : WorkflowReviewFeedback
    data class Unavailable(val reason: WorkflowReviewUnavailableReason) : WorkflowReviewFeedback
}

internal fun WorkflowReviewMutationResult.toWorkflowReviewFeedback(): WorkflowReviewFeedback =
    when (this) {
        is WorkflowReviewMutationResult.PromotedDisabled -> {
            if (replayed) WorkflowReviewFeedback.PromotionReplayed
            else WorkflowReviewFeedback.PromotedDisabled
        }
        is WorkflowReviewMutationResult.Enabled -> WorkflowReviewFeedback.Enabled
        WorkflowReviewMutationResult.Conflict -> WorkflowReviewFeedback.ConflictRefreshed
        is WorkflowReviewMutationResult.Rejected -> WorkflowReviewFeedback.Rejected(reasonCode)
        is WorkflowReviewMutationResult.Unavailable -> WorkflowReviewFeedback.Unavailable(reason)
    }

class WorkflowReviewVM(
    assistantId: String,
    private val repository: WorkflowReviewRepository,
    settingsStore: SettingsStore,
    private val positiveMutations: LearningPositiveMutationGate,
) : ViewModel() {
    val assistantId: Uuid = Uuid.parse(assistantId)
    val listState = MutableStateFlow<WorkflowReviewLoadState<List<WorkflowReviewListItem>>>(
        WorkflowReviewLoadState.Loading,
    )
    val detailState = MutableStateFlow<WorkflowReviewLoadState<WorkflowReviewDetail>>(
        WorkflowReviewLoadState.Loading,
    )
    val busyCandidateId = MutableStateFlow<String?>(null)
    val feedback = MutableStateFlow<WorkflowReviewFeedback?>(null)
    val positiveActionsEnabled: StateFlow<Boolean> = settingsStore.settingsFlow.map {
        positiveMutations.allows(
            LearningPositiveMutation.WORKFLOW_PROMOTION_OR_ENABLE,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        refreshList()
    }

    fun refreshList() {
        viewModelScope.launch(Dispatchers.IO) {
            listState.value = WorkflowReviewLoadState.Loading
            listState.value = repository.list(assistantId).toLoadState()
        }
    }

    fun loadDetail(candidateId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            detailState.value = WorkflowReviewLoadState.Loading
            detailState.value = repository.detail(assistantId, candidateId).toLoadState()
        }
    }

    fun promoteDisabled(detail: WorkflowReviewDetail) {
        val fence = detail.item.fence
        if (!positiveMutations.allows(
                LearningPositiveMutation.WORKFLOW_PROMOTION_OR_ENABLE,
            ) || !detail.canPromoteDisabled ||
            !busyCandidateId.compareAndSet(null, fence.candidateId)
        ) {
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                handleMutation(
                    candidateId = fence.candidateId,
                    repository.promoteDisabled(
                        PromoteWorkflowDisabledCommand(
                            consumingAssistantId = assistantId,
                            fence = fence,
                        ),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback.value = WorkflowReviewFeedback.Unavailable(
                    WorkflowReviewUnavailableReason.STORAGE_FAILURE,
                )
            } finally {
                busyCandidateId.compareAndSet(fence.candidateId, null)
            }
        }
    }

    fun enable(detail: WorkflowReviewDetail) {
        val fence = detail.item.fence
        val workflowVersion = detail.installedWorkflowStateVersion ?: return
        if (!positiveMutations.allows(
                LearningPositiveMutation.WORKFLOW_PROMOTION_OR_ENABLE,
            ) || !detail.canEnable || !busyCandidateId.compareAndSet(null, fence.candidateId)
        ) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                handleMutation(
                    candidateId = fence.candidateId,
                    repository.enable(
                        EnablePromotedWorkflowCommand(
                            consumingAssistantId = assistantId,
                            fence = fence,
                            expectedWorkflowStateVersion = workflowVersion,
                            explicitUserConfirmation = true,
                        ),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback.value = WorkflowReviewFeedback.Unavailable(
                    WorkflowReviewUnavailableReason.STORAGE_FAILURE,
                )
            } finally {
                busyCandidateId.compareAndSet(fence.candidateId, null)
            }
        }
    }

    fun clearFeedback() {
        feedback.value = null
    }

    private suspend fun handleMutation(
        candidateId: String,
        result: WorkflowReviewMutationResult,
    ) {
        feedback.value = result.toWorkflowReviewFeedback()
        // Conflict and success both invalidate every rendered fence. Always re-read both heads.
        listState.value = repository.list(assistantId).toLoadState()
        detailState.value = repository.detail(assistantId, candidateId).toLoadState()
    }
}

private fun <T> WorkflowReviewReadResult<T>.toLoadState(): WorkflowReviewLoadState<T> = when (this) {
    is WorkflowReviewReadResult.Ready -> WorkflowReviewLoadState.Ready(value)
    WorkflowReviewReadResult.NotFound -> WorkflowReviewLoadState.NotFound
    is WorkflowReviewReadResult.Unavailable -> WorkflowReviewLoadState.Unavailable(reason)
}
