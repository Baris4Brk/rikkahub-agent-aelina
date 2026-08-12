package me.rerere.rikkahub.data.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalResolutionPolicyTest {
    @Test
    fun `positive second-user decisions require the trusted app surface`() {
        val result = evaluateApprovalResolution(
            currentStatus = ApprovalStatus.PENDING,
            currentVersion = 3,
            decision = PersistedApprovalDecision.APPROVED,
            expectedVersion = 3,
            trustedAppApproval = false,
        )

        assertEquals(ApprovalResolutionPrecondition.TrustedAppRequired, result)
    }

    @Test
    fun `remote denial remains allowed`() {
        val result = evaluateApprovalResolution(
            currentStatus = ApprovalStatus.PENDING,
            currentVersion = 3,
            decision = PersistedApprovalDecision.DENIED,
            expectedVersion = 3,
            trustedAppApproval = false,
        )

        assertEquals(ApprovalResolutionPrecondition.Proceed, result)
    }

    @Test
    fun `legacy positive decision without an exact version fails closed`() {
        val result = evaluateApprovalResolution(
            currentStatus = ApprovalStatus.PENDING,
            currentVersion = 3,
            decision = PersistedApprovalDecision.APPROVED,
            expectedVersion = null,
            trustedAppApproval = true,
        )

        assertEquals(
            ApprovalResolutionPrecondition.Conflict("approval_version_required"),
            result,
        )
    }

    @Test
    fun `same resolved outcome is idempotent and never executes twice`() {
        val result = evaluateApprovalResolution(
            currentStatus = ApprovalStatus.APPROVED,
            currentVersion = 4,
            decision = PersistedApprovalDecision.ANSWERED,
            expectedVersion = 3,
            trustedAppApproval = true,
        )

        assertEquals(ApprovalResolutionPrecondition.Idempotent, result)
    }

    @Test
    fun `stale pending version conflicts`() {
        val result = evaluateApprovalResolution(
            currentStatus = ApprovalStatus.PENDING,
            currentVersion = 4,
            decision = PersistedApprovalDecision.APPROVED,
            expectedVersion = 3,
            trustedAppApproval = true,
        )

        assertTrue(result is ApprovalResolutionPrecondition.Conflict)
        assertEquals(
            "approval_version_conflict",
            (result as ApprovalResolutionPrecondition.Conflict).reasonCode,
        )
    }
}
