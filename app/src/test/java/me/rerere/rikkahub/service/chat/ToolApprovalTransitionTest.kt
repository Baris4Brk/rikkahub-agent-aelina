package me.rerere.rikkahub.service.chat

import me.rerere.ai.ui.ToolApprovalState
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolApprovalTransitionTest {
    @Test
    fun `pending approval applies first terminal decision`() {
        val requested = ToolApprovalState.Approved
        assertEquals(
            ToolApprovalTransition.Apply(requested),
            resolveToolApproval(ToolApprovalState.Pending, requested),
        )
    }

    @Test
    fun `repeating same approval is idempotent`() {
        assertEquals(
            ToolApprovalTransition.Idempotent,
            resolveToolApproval(ToolApprovalState.Approved, ToolApprovalState.Approved),
        )
        assertEquals(
            ToolApprovalTransition.Idempotent,
            resolveToolApproval(
                ToolApprovalState.Denied("no"),
                ToolApprovalState.Denied("no"),
            ),
        )
    }

    @Test
    fun `changing a terminal approval is a conflict`() {
        assertEquals(
            ToolApprovalTransition.Conflict,
            resolveToolApproval(ToolApprovalState.Approved, ToolApprovalState.Denied("no")),
        )
        assertEquals(
            ToolApprovalTransition.Conflict,
            resolveToolApproval(ToolApprovalState.Denied("no"), ToolApprovalState.Approved),
        )
    }

    @Test
    fun `auto approval is not a pending user decision`() {
        assertEquals(
            ToolApprovalTransition.NotPending,
            resolveToolApproval(ToolApprovalState.Auto, ToolApprovalState.Approved),
        )
    }

    @Test
    fun `only the command that applies a pending decision may resume`() {
        assertEquals(true, shouldResumeAfterApproval(appliedPendingDecision = true, hasPendingAfterUpdate = false))
        assertEquals(false, shouldResumeAfterApproval(appliedPendingDecision = false, hasPendingAfterUpdate = false))
        assertEquals(false, shouldResumeAfterApproval(appliedPendingDecision = true, hasPendingAfterUpdate = true))
    }
}
