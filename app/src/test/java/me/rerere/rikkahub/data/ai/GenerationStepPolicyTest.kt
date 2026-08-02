package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationStepPolicyTest {
    @Test
    fun `active local second user defaults to 64 steps`() {
        assertEquals(
            SECOND_USER_GENERATION_MAX_STEPS,
            resolveInteractiveGenerationMaxSteps(
                configured = null,
                isActiveLocalSecondUser = true,
            ),
        )
    }

    @Test
    fun `ordinary assistant keeps 32 step default`() {
        assertEquals(
            ORDINARY_GENERATION_MAX_STEPS,
            resolveInteractiveGenerationMaxSteps(
                configured = null,
                isActiveLocalSecondUser = false,
            ),
        )
    }

    @Test
    fun `explicit step budget is shared and bounded`() {
        assertEquals(128, resolveInteractiveGenerationMaxSteps(128, true))
        assertEquals(128, resolveInteractiveGenerationMaxSteps(128, false))
        assertEquals(1, resolveInteractiveGenerationMaxSteps(-20, true))
        assertEquals(256, resolveInteractiveGenerationMaxSteps(2_000, true))
    }
}
