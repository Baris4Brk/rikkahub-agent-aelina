package me.rerere.rikkahub.learning.review

import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.policy.LearningPolicyStatus
import me.rerere.rikkahub.learning.privacy.LearningEraseConfirmationToken
import me.rerere.rikkahub.learning.privacy.LearningEraseReceipt
import kotlin.uuid.Uuid

const val MAX_POLICY_REVIEW_PAGE_SIZE: Int = 80
const val MAX_POLICY_REVIEW_REVISIONS: Int = 20
const val MAX_POLICY_REDACTED_REPORT_CHARS: Int = 16_384

/** Exact optimistic-lock identity rendered by the review UI. */
data class PolicyReviewFence(
    val policyId: String,
    val scope: LearningScope,
    val stateVersion: Long,
    val contentRevision: Long,
    val artifactSha256: String,
    /** Null means the current Learning authority stream is not ready; grant writes stay disabled. */
    val sourceStreamId: String?,
) {
    init {
        require(policyId.length in 1..256)
        require(scope is LearningScope.Assistant || scope is LearningScope.AuthoritySubject)
        require(stateVersion > 0L)
        require(contentRevision > 0L)
        require(artifactSha256.matches(Regex("[0-9a-f]{64}")))
        sourceStreamId?.let { stream ->
            require(runCatching { Uuid.parse(stream).toString() == stream }.getOrDefault(false))
        }
    }

    override fun toString(): String =
        "PolicyReviewFence(scope=${scope.kind}, stateVersion=$stateVersion, " +
            "contentRevision=$contentRevision, ids=<redacted>)"
}

data class PolicyReviewExposureSummary(
    /** Stage-D would-recall count; never inferred from a P2 exposure row. */
    val shadowRecallCount: Long,
    val shadowExactTaskRecallCount: Long,
    val shadowEstimatedTokenCost: Long,
    val shadowLastObservedAtMs: Long?,
    /** P2 actual pipeline retrieval count, prior to compilation/injection. */
    val actualRetrievedCount: Long,
    val injectedHitCount: Long,
    val hostDispatchedHitCount: Long,
    val droppedItemCount: Long,
    val dropReasons: List<String>,
    val estimatedTokenCost: Long,
) {
    init {
        require(
            listOf(
                shadowRecallCount,
                shadowExactTaskRecallCount,
                shadowEstimatedTokenCost,
                actualRetrievedCount,
                injectedHitCount,
                hostDispatchedHitCount,
                droppedItemCount,
                estimatedTokenCost,
            ).all { it >= 0L },
        )
        require(shadowExactTaskRecallCount <= shadowRecallCount)
        require((shadowRecallCount == 0L) == (shadowLastObservedAtMs == null))
        require(shadowLastObservedAtMs == null || shadowLastObservedAtMs >= 0L)
        require(dropReasons.size <= 16)
        require(dropReasons == dropReasons.distinct().sorted())
        require(dropReasons.all { reason ->
            reason.length in 1..96 && reason.all { it.isUpperCase() || it.isDigit() || it == '_' }
        })
    }

    @Deprecated("Use shadowRecallCount; this is never an actual exposure count")
    val shadowHitCount: Long get() = shadowRecallCount
}

data class PolicyReviewListItem(
    val fence: PolicyReviewFence,
    val status: LearningPolicyStatus,
    val triggerSummary: String,
    val distinctEpisodeSupport: Long,
    val positiveEpisodeCount: Long,
    val negativeEpisodeCount: Long,
    val confidence: Double,
    val observedUtilityDelta: Double?,
    val utilityUncertainty: Double?,
    val staleReason: String?,
    val exposure: PolicyReviewExposureSummary,
    val updatedAtMs: Long,
) {
    /** Observed association whose complete projected confidence interval is below zero. */
    val observedUtilityReviewRecommended: Boolean
        get() = observedUtilityDelta != null && utilityUncertainty != null &&
            observedUtilityDelta + utilityUncertainty < 0.0

    init {
        require(distinctEpisodeSupport >= 0L)
        require(positiveEpisodeCount >= 0L && negativeEpisodeCount >= 0L)
        require(confidence.isFinite() && confidence in 0.0..1.0)
        require(observedUtilityDelta == null || observedUtilityDelta.isFinite())
        require((observedUtilityDelta == null) == (utilityUncertainty == null))
        require(utilityUncertainty == null || utilityUncertainty.isFinite() && utilityUncertainty >= 0.0)
        require(updatedAtMs >= 0L)
    }
}

data class PolicyReviewRevision(
    val revision: Long,
    val reasonCode: String,
    val actor: String,
    val artifactSha256: String,
    val createdAtMs: Long,
    val isCurrent: Boolean,
    /** True only when the immutable revision contains a complete locally restorable snapshot. */
    val historicContentRestorable: Boolean = false,
    /** Bounded, redacted field names changed from the prior durable revision. */
    val changedFields: List<String> = emptyList(),
) {
    init {
        require(revision > 0L)
        require(artifactSha256.matches(Regex("[0-9a-f]{64}")))
        require(createdAtMs >= 0L)
        require(changedFields.size <= 24)
        require(changedFields == changedFields.distinct().sorted())
    }
}

data class PolicyReviewDetail(
    val item: PolicyReviewListItem,
    val policyType: String,
    val taskSignature: String,
    val procedureSummary: String,
    val verificationSummary: String,
    val boundarySummary: String,
    val failureModeSummary: String,
    val producerModelIdentity: String,
    val producerProviderIdentity: String,
    val producerProviderKind: String,
    val producerPromptIdentity: String,
    val producerTemplateIdentity: String,
    val producerSchemaIdentity: String,
    val revisions: List<PolicyReviewRevision>,
) {
    init {
        require(revisions.size <= MAX_POLICY_REVIEW_REVISIONS)
    }
}

enum class PolicyReviewGrantState {
    NONE,
    EXACT_GRANTED,
    STALE_GRANTED,
    REVOKED,
    STREAM_UNAVAILABLE,
}

data class PolicyReviewGrantView(
    val state: PolicyReviewGrantState,
    /** Zero is the exact compare-and-set fence for an absent head. */
    val stateVersion: Long,
) {
    init {
        require(stateVersion >= 0L)
        require((state == PolicyReviewGrantState.NONE || state == PolicyReviewGrantState.STREAM_UNAVAILABLE) ==
            (stateVersion == 0L))
    }
}

data class ReviewedPolicyListItem(
    val policy: PolicyReviewListItem,
    val grant: PolicyReviewGrantView,
)

data class ReviewedPolicyDetail(
    val policy: PolicyReviewDetail,
    val grant: PolicyReviewGrantView,
)

enum class PolicyReviewUnavailableReason {
    FEATURE_DISABLED,
    WRONG_PROCESS,
    RUNTIME_NOT_READY,
    RESTORE_IN_PROGRESS,
    STORAGE_FAILURE,
    STREAM_NOT_READY,
    HISTORIC_CONTENT_RESTORE_NOT_SUPPORTED,
    GRANT_MUST_BE_REVOKED,
    ACTION_NOT_ALLOWED,
}

sealed interface PolicyReviewReadResult<out T> {
    data class Ready<T>(val value: T) : PolicyReviewReadResult<T>
    data object NotFound : PolicyReviewReadResult<Nothing>
    data class Unavailable(val reason: PolicyReviewUnavailableReason) : PolicyReviewReadResult<Nothing>
}

enum class PolicyReviewLifecycleAction {
    ARCHIVE,
    RESTORE_ARCHIVED_REVISION,
    SUSPEND,
    RESUME,
}

data class PolicyReviewLifecycleCommand(
    val fence: PolicyReviewFence,
    val action: PolicyReviewLifecycleAction,
    val selectedRevision: Long,
    val frozenNowMs: Long,
) {
    init {
        require(selectedRevision > 0L)
        require(frozenNowMs >= 0L)
    }
}

sealed interface PolicyReviewRuntimeMutationResult {
    data class Applied(val revision: Long, val status: LearningPolicyStatus) :
        PolicyReviewRuntimeMutationResult

    data class Duplicate(val revision: Long) : PolicyReviewRuntimeMutationResult
    data object Conflict : PolicyReviewRuntimeMutationResult
    data class Unavailable(val reason: PolicyReviewUnavailableReason) :
        PolicyReviewRuntimeMutationResult
}

sealed interface PolicyReviewExportResult {
    data class Ready(val redactedReport: String) : PolicyReviewExportResult {
        init {
            require(redactedReport.isNotBlank())
            require(redactedReport.length <= MAX_POLICY_REDACTED_REPORT_CHARS)
        }
    }

    data object NotFound : PolicyReviewExportResult
    data class Unavailable(val reason: PolicyReviewUnavailableReason) : PolicyReviewExportResult
}

/** Unit-confined LearningDatabase surface implemented by LearningRuntimeFacade. */
interface PolicyReviewRuntimePort {
    suspend fun listForReview(
        consumingAssistantId: Uuid,
        limit: Int = MAX_POLICY_REVIEW_PAGE_SIZE,
    ): PolicyReviewReadResult<List<PolicyReviewListItem>>

    suspend fun readForReview(
        consumingAssistantId: Uuid,
        policyId: String,
    ): PolicyReviewReadResult<PolicyReviewDetail>

    suspend fun mutateForReview(
        command: PolicyReviewLifecycleCommand,
    ): PolicyReviewRuntimeMutationResult

    suspend fun exportRedactedReviewReport(
        consumingAssistantId: Uuid,
        policyId: String,
    ): PolicyReviewExportResult
}

data class PolicyReviewActionCommand(
    val fence: PolicyReviewFence,
    val consumingAssistantId: Uuid,
    val expectedGrantStateVersion: Long,
) {
    init {
        require(expectedGrantStateVersion >= 0L)
        if (fence.scope is LearningScope.Assistant) {
            require(fence.scope.assistantId == consumingAssistantId)
        }
    }
}

sealed interface PolicyReviewActionResult {
    data object Applied : PolicyReviewActionResult
    data object Duplicate : PolicyReviewActionResult
    /** AppDatabase authority committed; derived lifecycle projection is explicitly pending. */
    data object AuthorityCommittedDerivedPending : PolicyReviewActionResult
    data object Conflict : PolicyReviewActionResult
    data class Unavailable(val reason: PolicyReviewUnavailableReason) : PolicyReviewActionResult
}

class PolicyReviewEraseChallenge internal constructor(
    val scope: LearningScope,
    internal val authorityToken: LearningEraseConfirmationToken,
) {
    override fun toString(): String = "PolicyReviewEraseChallenge(scope=${scope.kind}, token=<redacted>)"
}

sealed interface PolicyReviewEraseResult {
    data class Erased(val receipt: LearningEraseReceipt) : PolicyReviewEraseResult
    data object Conflict : PolicyReviewEraseResult
    data class Unavailable(val reason: PolicyReviewUnavailableReason) : PolicyReviewEraseResult
}

/** User-review facade: joins bounded Learning reads with AppDatabase grant authority. */
interface LearningPolicyReviewRepository {
    suspend fun list(
        consumingAssistantId: Uuid,
        limit: Int = MAX_POLICY_REVIEW_PAGE_SIZE,
    ): PolicyReviewReadResult<List<ReviewedPolicyListItem>>

    suspend fun detail(
        consumingAssistantId: Uuid,
        policyId: String,
    ): PolicyReviewReadResult<ReviewedPolicyDetail>

    suspend fun approve(command: PolicyReviewActionCommand): PolicyReviewActionResult
    suspend fun revoke(command: PolicyReviewActionCommand): PolicyReviewActionResult
    suspend fun suspendPolicy(command: PolicyReviewActionCommand): PolicyReviewActionResult
    suspend fun archive(command: PolicyReviewActionCommand): PolicyReviewActionResult
    suspend fun restoreRevision(
        command: PolicyReviewActionCommand,
        selectedRevision: Long,
    ): PolicyReviewActionResult

    suspend fun issueEraseChallenge(
        scope: LearningScope,
    ): PolicyReviewReadResult<PolicyReviewEraseChallenge>
    suspend fun erase(challenge: PolicyReviewEraseChallenge): PolicyReviewEraseResult

    suspend fun exportRedacted(
        consumingAssistantId: Uuid,
        policyId: String,
    ): PolicyReviewExportResult
}
