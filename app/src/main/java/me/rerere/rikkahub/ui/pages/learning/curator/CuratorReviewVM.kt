package me.rerere.rikkahub.ui.pages.learning.curator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.learning.curator.CuratorApplyRuntimeCoordinator
import me.rerere.rikkahub.learning.curator.CuratorCandidateProductionCoordinator
import me.rerere.rikkahub.learning.curator.CuratorCandidateProductionRequest
import me.rerere.rikkahub.learning.curator.CuratorCandidateProductionResult
import me.rerere.rikkahub.learning.curator.CuratorDeltaCandidate
import me.rerere.rikkahub.learning.curator.CuratorDeltaOperation
import me.rerere.rikkahub.learning.curator.CuratorFieldDiff
import me.rerere.rikkahub.learning.curator.CuratorPolicyDocument
import me.rerere.rikkahub.learning.curator.CuratorPolicyField
import me.rerere.rikkahub.learning.curator.CuratorProductionSourceProjection
import me.rerere.rikkahub.learning.curator.CuratorTargetDiff
import me.rerere.rikkahub.learning.curator.CuratorV1Canonicalizer
import me.rerere.rikkahub.learning.curator.CuratorReviewDetail
import me.rerere.rikkahub.learning.curator.CuratorReviewListItem
import me.rerere.rikkahub.learning.curator.CuratorReviewListRequest
import me.rerere.rikkahub.learning.curator.CuratorReviewMutationRequest
import me.rerere.rikkahub.learning.curator.CuratorReviewMutationResult
import me.rerere.rikkahub.learning.curator.CuratorReviewRuntimeCoordinator
import me.rerere.rikkahub.learning.curator.CuratorRuntimeApplyRequest
import me.rerere.rikkahub.learning.curator.CuratorRuntimeMutationResult
import me.rerere.rikkahub.learning.curator.CuratorRuntimeRollbackRequest
import me.rerere.rikkahub.learning.curator.allows
import me.rerere.rikkahub.learning.model.LearningPositiveMutationGate
import me.rerere.rikkahub.learning.model.LearningScope
import kotlin.uuid.Uuid

sealed interface CuratorReviewLoadState<out T> {
    data object Loading : CuratorReviewLoadState<Nothing>
    data class Ready<T>(val value: T) : CuratorReviewLoadState<T>
    data object NotFound : CuratorReviewLoadState<Nothing>
    data object Unavailable : CuratorReviewLoadState<Nothing>
}

sealed interface CuratorReviewFeedback {
    data class Applied(val state: String) : CuratorReviewFeedback
    data object Duplicate : CuratorReviewFeedback
    data class Conflict(val reason: String) : CuratorReviewFeedback
    data class Proposed(val candidateId: String) : CuratorReviewFeedback
}

class CuratorReviewVM(
    assistantId: String,
    private val review: CuratorReviewRuntimeCoordinator,
    private val apply: CuratorApplyRuntimeCoordinator,
    private val producer: CuratorCandidateProductionCoordinator,
    private val positiveMutations: LearningPositiveMutationGate,
) : ViewModel() {
    private val assistantId = Uuid.parse(assistantId)
    private val scope = LearningScope.Assistant(this.assistantId)
    val proposalSources = MutableStateFlow<
        CuratorReviewLoadState<List<CuratorProductionSourceProjection>>
        >(CuratorReviewLoadState.Loading)
    val listState = MutableStateFlow<CuratorReviewLoadState<List<CuratorReviewListItem>>>(
        CuratorReviewLoadState.Loading,
    )
    val detailState = MutableStateFlow<CuratorReviewLoadState<CuratorReviewDetail>>(
        CuratorReviewLoadState.Loading,
    )
    val feedback = MutableStateFlow<CuratorReviewFeedback?>(null)
    val busy = MutableStateFlow(false)

    /** UI hint only; each production coordinator re-reads the same gate before every write. */
    fun operationEnabled(operation: CuratorDeltaOperation): Boolean =
        positiveMutations.allows(operation)

    init {
        refreshList()
        refreshProposalSources()
    }

    fun refreshProposalSources() = viewModelScope.launch(Dispatchers.IO) {
        proposalSources.value = CuratorReviewLoadState.Loading
        proposalSources.value = runCatching {
            producer.listExactReviewedSources(assistantId)
        }.fold(
            onSuccess = { CuratorReviewLoadState.Ready(it) },
            onFailure = { CuratorReviewLoadState.Unavailable },
        )
    }

    fun proposeCandidate(
        operation: CuratorDeltaOperation,
        selectedPolicyIds: Set<String>,
        outputPolicyId: String,
        secondOutputPolicyId: String,
        firstValues: List<String>,
        secondValues: List<String>?,
    ) = runProposalBusy {
        val available = (proposalSources.value as? CuratorReviewLoadState.Ready)?.value.orEmpty()
        val selected = available.filter { it.exact.source.policyId in selectedPolicyIds }
            .sortedBy { it.exact.source.policyId }
        require(selected.size == selectedPolicyIds.size)
        when (operation) {
            CuratorDeltaOperation.UPDATE_CANDIDATE,
            CuratorDeltaOperation.SPLIT_CANDIDATE,
            CuratorDeltaOperation.SUPERSEDE_CANDIDATE,
            -> require(selected.size == 1)
            CuratorDeltaOperation.MERGE_CANDIDATE -> require(selected.size in 2..8)
        }
        val primary = selected.first()
        require(selected.all {
            it.exact.source.scope == primary.exact.source.scope &&
                it.policyType == primary.policyType && it.taskSignature == primary.taskSignature
        })
        val rawEvidence = selected.flatMap(CuratorProductionSourceProjection::evidence)
        require(rawEvidence.groupBy { it.evidenceId }.all { (_, sameId) ->
            sameId.distinct().size == 1
        })
        val evidence = rawEvidence.distinctBy { it.evidenceId }.sortedBy { it.evidenceId }
        require(evidence.isNotEmpty() && evidence.size <= 32)
        val firstDocument = firstValues.toDocument()
        val secondDocument = secondValues?.toDocument()
        val firstTarget = when (operation) {
            CuratorDeltaOperation.UPDATE_CANDIDATE -> primary.exact.source.policyId
            else -> outputPolicyId
        }
        val firstDiff = firstDocument.diffFrom(primary.document, firstTarget)
        val candidateId = "curator-" + CuratorV1Canonicalizer.digest(
            "curator-explicit-review-candidate-id-v1",
            selected.flatMap { listOf(
                it.exact.source.policyId,
                it.exact.source.expectedRevision.toString(),
                it.exact.expectedContentRevision.toString(),
                it.exact.source.baseHash,
                it.exact.expectedStorageState,
                it.exact.expectedUpdatedAtMs.toString(),
            ) } +
                evidence.flatMap { listOf(
                    it.evidenceId,
                    it.sourceRevision.toString(),
                    it.integritySha256,
                ) } +
                listOf(operation.name, outputPolicyId, secondOutputPolicyId,
                    firstDocument.contentSha256, secondDocument?.contentSha256.orEmpty()),
        )
        val candidate = when (operation) {
            CuratorDeltaOperation.UPDATE_CANDIDATE -> CuratorDeltaCandidate.Update(
                candidateId, primary.exact.source, evidence, listOf(firstDiff),
            )
            CuratorDeltaOperation.MERGE_CANDIDATE -> CuratorDeltaCandidate.Merge(
                candidateId,
                selected.map { it.exact.source },
                outputPolicyId,
                firstDocument,
                evidence,
                listOf(firstDiff),
            )
            CuratorDeltaOperation.SPLIT_CANDIDATE -> {
                val second = requireNotNull(secondDocument)
                CuratorDeltaCandidate.Split(
                    candidateId,
                    primary.exact.source,
                    listOf(
                        CuratorDeltaCandidate.SplitOutput(outputPolicyId, firstDocument),
                        CuratorDeltaCandidate.SplitOutput(secondOutputPolicyId, second),
                    ).sortedBy { it.policyId },
                    evidence,
                    listOf(
                        firstDiff,
                        second.diffFrom(primary.document, secondOutputPolicyId),
                    ).sortedBy { it.targetPolicyId },
                )
            }
            CuratorDeltaOperation.SUPERSEDE_CANDIDATE -> CuratorDeltaCandidate.Supersede(
                candidateId,
                primary.exact.source,
                outputPolicyId,
                firstDocument,
                evidence,
                listOf(firstDiff),
            )
        }
        feedback.value = producer.propose(
            CuratorCandidateProductionRequest(
                candidate,
                selected.map { it.exact },
                explicitlyUserReviewed = true,
                proposedAtMs = System.currentTimeMillis().coerceAtLeast(
                    selected.maxOf { it.exact.expectedUpdatedAtMs },
                ),
            ),
        ).feedback()
        refreshList().join()
        refreshProposalSources().join()
    }

    fun refreshList() = viewModelScope.launch(Dispatchers.IO) {
        listState.value = CuratorReviewLoadState.Loading
        listState.value = runCatching { review.list(CuratorReviewListRequest(scope)) }
            .fold(
                onSuccess = { CuratorReviewLoadState.Ready(it) },
                onFailure = { CuratorReviewLoadState.Unavailable },
            )
    }

    fun loadDetail(candidateId: String) = viewModelScope.launch(Dispatchers.IO) {
        detailState.value = CuratorReviewLoadState.Loading
        detailState.value = try {
            review.read(candidateId, scope)?.let { CuratorReviewLoadState.Ready(it) }
                ?: CuratorReviewLoadState.NotFound
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CuratorReviewLoadState.Unavailable
        }
    }

    fun approve(detail: CuratorReviewDetail) = mutate(detail) { request -> review.approve(request) }
    fun reject(detail: CuratorReviewDetail) = mutate(detail) { request -> review.reject(request) }
    fun archive(detail: CuratorReviewDetail) = mutate(detail) { request -> review.archive(request) }

    fun apply(detail: CuratorReviewDetail) = runBusy(detail.summary.candidateId) {
        val result = apply.apply(
            CuratorRuntimeApplyRequest(
                candidateId = detail.summary.candidateId,
                expectedOperation = detail.summary.operation,
                expectedCandidateStateVersion = detail.summary.stateVersion,
                expectedCandidateSha256 = detail.summary.candidateSha256,
                expectedCandidateUpdatedAtMs = detail.summary.updatedAtMs,
                committedAtMs = now(detail.summary.updatedAtMs),
            ),
        )
        feedback.value = result.feedback()
    }

    fun rollback(detail: CuratorReviewDetail) = runBusy(detail.summary.candidateId) {
        val plan = detail.applyPlan ?: return@runBusy
        // The detail plan is canonical; runtime store revalidates both plan identifiers from DB.
        val result = apply.rollback(
            CuratorRuntimeRollbackRequest(
                candidateId = detail.summary.candidateId,
                expectedOperation = detail.summary.operation,
                expectedCandidateStateVersion = detail.summary.stateVersion,
                expectedCandidateSha256 = detail.summary.candidateSha256,
                expectedApplyPlanId = plan.planId,
                expectedApplyPlanSha256 = me.rerere.rikkahub.learning.curator.CuratorV1WireCodec
                    .applyPlanSha256(
                        me.rerere.rikkahub.learning.curator.CuratorV1WireCodec
                            .encodeApplyPlan(plan),
                    ),
                expectedCandidateUpdatedAtMs = detail.summary.updatedAtMs,
                committedAtMs = now(detail.summary.updatedAtMs),
            ),
        )
        feedback.value = result.feedback()
    }

    fun clearFeedback() { feedback.value = null }

    private fun mutate(
        detail: CuratorReviewDetail,
        action: suspend (CuratorReviewMutationRequest) -> CuratorReviewMutationResult,
    ) = runBusy(detail.summary.candidateId) {
        val item = detail.summary
        feedback.value = action(
            CuratorReviewMutationRequest(
                candidateId = item.candidateId,
                scope = item.scope,
                expectedOperation = item.operation,
                expectedState = item.state,
                expectedStateVersion = item.stateVersion,
                expectedCandidateSha256 = item.candidateSha256,
                expectedUpdatedAtMs = item.updatedAtMs,
                committedAtMs = now(item.updatedAtMs),
            ),
        ).feedback()
    }

    private fun runBusy(candidateId: String, block: suspend () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
                refreshList().join()
                loadDetail(candidateId).join()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback.value = CuratorReviewFeedback.Conflict("RUNTIME_UNAVAILABLE")
            } finally {
                busy.value = false
            }
        }
    }

    private fun runProposalBusy(block: suspend () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                feedback.value = CuratorReviewFeedback.Conflict("INVALID_EXPLICIT_DELTA")
            } finally {
                busy.value = false
            }
        }
    }

    private fun now(floor: Long): Long = System.currentTimeMillis().coerceAtLeast(floor)
}

private fun List<String>.toDocument(): CuratorPolicyDocument {
    require(size == 6)
    val tools = if (this[5].isBlank()) emptyList() else this[5].split(',').map(String::trim)
    return CuratorPolicyDocument(
        trigger = this[0],
        procedure = this[1],
        verification = this[2],
        boundary = this[3],
        failureMode = this[4],
        applicableToolSchemaSha256 = tools.distinct().sorted(),
    )
}

private fun CuratorPolicyDocument.diffFrom(
    before: CuratorPolicyDocument,
    targetPolicyId: String,
): CuratorTargetDiff {
    val fields = CuratorPolicyField.entries.mapNotNull { field ->
        val beforeValue = before.value(field)
        val afterValue = value(field)
        if (beforeValue == afterValue) null else CuratorFieldDiff(
            field,
            CuratorV1Canonicalizer.fieldSha256(field, beforeValue),
            afterValue,
        )
    }
    require(fields.isNotEmpty())
    return CuratorTargetDiff(targetPolicyId, fields)
}

private fun CuratorReviewMutationResult.feedback(): CuratorReviewFeedback = when (this) {
    is CuratorReviewMutationResult.Applied -> CuratorReviewFeedback.Applied(state)
    is CuratorReviewMutationResult.Duplicate -> CuratorReviewFeedback.Duplicate
    is CuratorReviewMutationResult.Conflict -> CuratorReviewFeedback.Conflict(reason.name)
}

private fun CuratorRuntimeMutationResult.feedback(): CuratorReviewFeedback = when (this) {
    is CuratorRuntimeMutationResult.Applied -> CuratorReviewFeedback.Applied("APPLIED")
    is CuratorRuntimeMutationResult.RolledBack -> CuratorReviewFeedback.Applied("ROLLED_BACK")
    is CuratorRuntimeMutationResult.Duplicate -> CuratorReviewFeedback.Duplicate
    is CuratorRuntimeMutationResult.Conflict -> CuratorReviewFeedback.Conflict(reason.name)
}

private fun CuratorCandidateProductionResult.feedback(): CuratorReviewFeedback = when (this) {
    is CuratorCandidateProductionResult.Proposed -> CuratorReviewFeedback.Proposed(candidateId)
    is CuratorCandidateProductionResult.Duplicate -> CuratorReviewFeedback.Duplicate
    is CuratorCandidateProductionResult.Conflict -> CuratorReviewFeedback.Conflict(reason.name)
}
