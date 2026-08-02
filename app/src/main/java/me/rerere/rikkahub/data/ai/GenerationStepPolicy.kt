package me.rerere.rikkahub.data.ai

const val ORDINARY_GENERATION_MAX_STEPS = 32
const val SECOND_USER_GENERATION_MAX_STEPS = 64
const val MAX_CONFIGURABLE_GENERATION_STEPS = 256
const val SECOND_USER_GENERATION_TURN_BUDGET_MINUTES = 60
const val MAX_CONFIGURABLE_GENERATION_TURN_BUDGET_MINUTES = 60

/** Resolves an interactive turn budget without changing sub-agent-specific limits. */
fun resolveInteractiveGenerationMaxSteps(
    configured: Int?,
    isActiveLocalSecondUser: Boolean,
): Int = (configured ?: if (isActiveLocalSecondUser) {
    SECOND_USER_GENERATION_MAX_STEPS
} else {
    ORDINARY_GENERATION_MAX_STEPS
}).coerceIn(1, MAX_CONFIGURABLE_GENERATION_STEPS)

/** Freezes the wall-clock limit at turn admission so a settings refresh cannot shorten a run. */
fun resolveInteractiveGenerationTurnBudgetMs(
    configuredMinutes: Int?,
    isActiveLocalSecondUser: Boolean,
    globalTurnBudgetMs: Long,
): Long {
    val minutes = configuredMinutes?.coerceIn(
        1,
        MAX_CONFIGURABLE_GENERATION_TURN_BUDGET_MINUTES,
    )
    if (minutes != null) return minutes * 60_000L
    return if (isActiveLocalSecondUser) {
        SECOND_USER_GENERATION_TURN_BUDGET_MINUTES * 60_000L
    } else {
        globalTurnBudgetMs
    }
}
