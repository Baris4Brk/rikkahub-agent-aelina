package me.rerere.rikkahub.learning.jobs

import org.junit.Assert.assertTrue
import org.junit.Test

class P1RewardAuthorityPolicyTest {
    @Test
    fun finalSavedCompletedIsNotGoalSuccessEvidence() {
        assertTrue(
            P1RewardAuthorityPolicy.commandTerminalSignals(
                completionKind = "GENERATION_FINAL_SAVED",
                terminalState = "COMPLETED",
            ).isEmpty(),
        )
    }

    @Test
    fun hostFailureIsNotAuthoritativeTaskFailureEvidence() {
        assertTrue(
            P1RewardAuthorityPolicy.commandTerminalSignals(
                completionKind = "FAILED_OTHER",
                terminalState = "FAILED",
            ).isEmpty(),
        )
    }
}
