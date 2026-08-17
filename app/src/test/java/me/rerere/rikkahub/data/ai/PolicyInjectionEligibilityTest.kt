package me.rerere.rikkahub.data.ai

import kotlin.uuid.Uuid
import me.rerere.rikkahub.learning.exposure.PolicyLearningCommandContext
import me.rerere.rikkahub.learning.model.LearningScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyInjectionEligibilityTest {
    private val assistantId = Uuid.parse("00000000-0000-0000-0000-0000000000a1")
    private val runId = Uuid.parse("00000000-0000-0000-0000-0000000000b1")
    private val command = PolicyLearningCommandContext(
        scope = LearningScope.Assistant(assistantId),
        consumingAssistantId = assistantId,
        lineageId = Uuid.parse("00000000-0000-0000-0000-0000000000c1"),
        branchAnchorMessageId = Uuid.parse("00000000-0000-0000-0000-0000000000d1"),
        logicalRunId = runId,
    )

    @Test
    fun `exact opted-in local assistant is eligible`() {
        assertTrue(eligible(optIn = true))
    }

    @Test
    fun `assistant opt-in is an independent hard gate`() {
        assertFalse(eligible(optIn = false))
    }

    @Test
    fun `other assistant and authority surface are ineligible`() {
        assertFalse(
            eligible(
                optIn = true,
                assistant = Uuid.parse("00000000-0000-0000-0000-0000000000a2"),
            ),
        )
        assertFalse(eligible(optIn = true, origin = ToolCallOrigin.SystemAssistant))
    }

    private fun eligible(
        optIn: Boolean,
        assistant: Uuid = assistantId,
        origin: ToolCallOrigin = ToolCallOrigin.LocalChat,
    ): Boolean = isPolicyInjectionDispatchEligible(
        requestIsNormal = true,
        isHeadless = false,
        isSubAgent = false,
        assistantPolicyOptIn = optIn,
        callOrigin = origin,
        command = command,
        expectedRunId = runId,
        expectedAssistantId = assistant,
        hasPriorExposure = false,
    )
}
