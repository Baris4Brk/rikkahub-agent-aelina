package me.rerere.rikkahub.ui.pages.learning.workflow

import me.rerere.rikkahub.learning.workflow.review.WorkflowReviewMutationResult
import me.rerere.rikkahub.learning.workflow.review.WorkflowReviewUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkflowReviewVMTest {
    @Test
    fun `typed mutation results preserve the two activation phases and conflict`() {
        assertEquals(
            WorkflowReviewFeedback.PromotedDisabled,
            WorkflowReviewMutationResult.PromotedDisabled("learned:id", false)
                .toWorkflowReviewFeedback(),
        )
        assertEquals(
            WorkflowReviewFeedback.PromotionReplayed,
            WorkflowReviewMutationResult.PromotedDisabled("learned:id", true)
                .toWorkflowReviewFeedback(),
        )
        assertEquals(
            WorkflowReviewFeedback.Enabled,
            WorkflowReviewMutationResult.Enabled("learned:id").toWorkflowReviewFeedback(),
        )
        assertEquals(
            WorkflowReviewFeedback.ConflictRefreshed,
            WorkflowReviewMutationResult.Conflict.toWorkflowReviewFeedback(),
        )
        assertEquals(
            WorkflowReviewFeedback.Unavailable(WorkflowReviewUnavailableReason.STORAGE_FAILURE),
            WorkflowReviewMutationResult.Unavailable(
                WorkflowReviewUnavailableReason.STORAGE_FAILURE,
            ).toWorkflowReviewFeedback(),
        )
    }
}
