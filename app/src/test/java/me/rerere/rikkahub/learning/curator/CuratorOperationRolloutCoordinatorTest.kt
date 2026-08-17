package me.rerere.rikkahub.learning.curator

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.model.DisabledLearningPositiveMutationGate
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CuratorOperationRolloutCoordinatorTest {
    private val scope = LearningScope.Assistant(
        Uuid.parse("00000000-0000-0000-0000-000000000041"),
    )

    @Test
    fun disabledApproveDoesNotReachStoreButRejectStillDoes() = runBlocking {
        var approveWrites = 0
        var rejectWrites = 0
        val store = object : CuratorReviewRuntimeStore {
            override suspend fun list(request: CuratorReviewListRequest) =
                emptyList<CuratorReviewListItem>()

            override suspend fun read(
                candidateId: String,
                scope: LearningScope,
            ): CuratorReviewDetail? = null

            override suspend fun approve(
                request: CuratorReviewMutationRequest,
            ): CuratorReviewMutationResult {
                approveWrites += 1
                return CuratorReviewMutationResult.Conflict(CuratorReviewConflict.STATE_CONFLICT)
            }

            override suspend fun reject(
                request: CuratorReviewMutationRequest,
            ): CuratorReviewMutationResult {
                rejectWrites += 1
                return CuratorReviewMutationResult.Conflict(CuratorReviewConflict.STATE_CONFLICT)
            }

            override suspend fun archive(
                request: CuratorReviewMutationRequest,
            ): CuratorReviewMutationResult =
                CuratorReviewMutationResult.Conflict(CuratorReviewConflict.STATE_CONFLICT)

            override suspend fun listRetentionArchivable(
                cutoffMs: Long,
                after: CuratorRetentionArchiveCursor,
                limit: Int,
            ) = emptyList<CuratorReviewListItem>()

            override suspend fun archiveRetention(
                request: CuratorRetentionArchiveRequest,
            ): CuratorReviewMutationResult =
                CuratorReviewMutationResult.Conflict(CuratorReviewConflict.STATE_CONFLICT)
        }
        val coordinator = CuratorReviewRuntimeCoordinator(
            store,
            DisabledLearningPositiveMutationGate,
        )
        val request = reviewRequest()

        val denied = coordinator.approve(request)
        assertEquals(
            CuratorReviewMutationResult.Conflict(CuratorReviewConflict.ROLLOUT_DISABLED),
            denied,
        )
        assertEquals(0, approveWrites)

        coordinator.reject(request)
        assertEquals(1, rejectWrites)
    }

    @Test
    fun disabledApplyDoesNotReachStoreButRollbackStillDoes() = runBlocking {
        var applyWrites = 0
        var rollbackWrites = 0
        val store = object : CuratorApplyRuntimeStore {
            override suspend fun applyApproved(
                request: CuratorRuntimeApplyRequest,
            ): CuratorRuntimeMutationResult {
                applyWrites += 1
                return CuratorRuntimeMutationResult.Conflict(CuratorRuntimeConflict.PLAN_INVALID)
            }

            override suspend fun rollbackApplied(
                request: CuratorRuntimeRollbackRequest,
            ): CuratorRuntimeMutationResult {
                rollbackWrites += 1
                return CuratorRuntimeMutationResult.Conflict(CuratorRuntimeConflict.PLAN_INVALID)
            }
        }
        val coordinator = CuratorApplyRuntimeCoordinator(
            store,
            DisabledLearningPositiveMutationGate,
        )

        val denied = coordinator.apply(
            CuratorRuntimeApplyRequest(
                candidateId = "candidate-a",
                expectedOperation = CuratorDeltaOperation.UPDATE_CANDIDATE,
                expectedCandidateStateVersion = 1L,
                expectedCandidateSha256 = "c".repeat(64),
                expectedCandidateUpdatedAtMs = 100L,
                committedAtMs = 100L,
            ),
        )
        assertTrue(denied is CuratorRuntimeMutationResult.Conflict)
        assertEquals(
            CuratorRuntimeConflict.ROLLOUT_DISABLED,
            (denied as CuratorRuntimeMutationResult.Conflict).reason,
        )
        assertEquals(0, applyWrites)

        coordinator.rollback(
            CuratorRuntimeRollbackRequest(
                candidateId = "candidate-a",
                expectedOperation = CuratorDeltaOperation.UPDATE_CANDIDATE,
                expectedCandidateStateVersion = 1L,
                expectedCandidateSha256 = "c".repeat(64),
                expectedApplyPlanId = "curator-plan-v1:${"d".repeat(64)}",
                expectedApplyPlanSha256 = "d".repeat(64),
                expectedCandidateUpdatedAtMs = 100L,
                committedAtMs = 100L,
            ),
        )
        assertEquals(1, rollbackWrites)
    }

    private fun reviewRequest() = CuratorReviewMutationRequest(
        candidateId = "candidate-a",
        scope = scope,
        expectedOperation = CuratorDeltaOperation.UPDATE_CANDIDATE,
        expectedState = "PROPOSED",
        expectedStateVersion = 1L,
        expectedCandidateSha256 = "c".repeat(64),
        expectedUpdatedAtMs = 100L,
        committedAtMs = 100L,
    )
}
