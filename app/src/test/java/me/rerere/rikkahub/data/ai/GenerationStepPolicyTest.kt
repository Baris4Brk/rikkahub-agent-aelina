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

    @Test
    fun `active local second user receives a sixty minute turn by default`() {
        assertEquals(
            60L * 60_000L,
            resolveInteractiveGenerationTurnBudgetMs(null, true, 10L * 60_000L),
        )
    }

    @Test
    fun `ordinary assistants retain global time budget and explicit values are bounded`() {
        assertEquals(
            10L * 60_000L,
            resolveInteractiveGenerationTurnBudgetMs(null, false, 10L * 60_000L),
        )
        assertEquals(1L * 60_000L, resolveInteractiveGenerationTurnBudgetMs(-8, true, 1L))
        assertEquals(60L * 60_000L, resolveInteractiveGenerationTurnBudgetMs(500, false, 1L))
    }
}
