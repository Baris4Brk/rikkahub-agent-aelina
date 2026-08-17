package me.rerere.rikkahub.learning.jobs

import me.rerere.rikkahub.learning.runtime.LearningRuntimeMaintenanceRequest
import me.rerere.rikkahub.learning.runtime.withMandatoryAuthorityInvalidationLane
import me.rerere.rikkahub.learning.storage.LearningJobType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class P1NegativeAuthorityLaneTest {
    @Test
    fun `capture off admits adjacent feedback authority but not a new positive source`() {
        assertFalse(
            rewardAuthorityCaptureGateAllows(
                captureEnabled = false,
                previousSourceRevision = null,
            ),
        )
        assertTrue(
            rewardAuthorityCaptureGateAllows(
                captureEnabled = false,
                previousSourceRevision = 1L,
            ),
        )
        assertTrue(
            rewardAuthorityCaptureGateAllows(
                captureEnabled = true,
                previousSourceRevision = null,
            ),
        )
    }

    @Test
    fun `maintenance off still drains only the mandatory authority-loss job types`() {
        val disabled = request(processJobs = false).withMandatoryAuthorityInvalidationLane()

        assertTrue(disabled.processJobs)
        assertEquals(
            setOf(
                LearningJobType.INVALIDATE_SOURCE_V1,
                LearningJobType.APPLY_REWARD_AUTHORITY_V1,
            ),
            disabled.eligibleJobTypes,
        )
    }

    @Test
    fun `caller allow-list cannot exclude mandatory authority loss and unrestricted stays exact`() {
        val restricted = request(
            processJobs = true,
            eligibleJobTypes = setOf(LearningJobType.ASSEMBLE_EPISODE_SHADOW),
        ).withMandatoryAuthorityInvalidationLane()
        assertEquals(
            setOf(
                LearningJobType.ASSEMBLE_EPISODE_SHADOW,
                LearningJobType.INVALIDATE_SOURCE_V1,
                LearningJobType.APPLY_REWARD_AUTHORITY_V1,
            ),
            restricted.eligibleJobTypes,
        )

        val unrestricted = request(processJobs = true)
        assertSame(unrestricted, unrestricted.withMandatoryAuthorityInvalidationLane())
    }

    private fun request(
        processJobs: Boolean,
        eligibleJobTypes: Set<LearningJobType>? = null,
    ) = LearningRuntimeMaintenanceRequest(
        maxJobs = 4,
        monotonicDeadlineMs = 10_000L,
        processJobs = processJobs,
        mode = LearningDrainMode.DRAIN_ONLY,
        eligibleJobTypes = eligibleJobTypes,
    )
}
