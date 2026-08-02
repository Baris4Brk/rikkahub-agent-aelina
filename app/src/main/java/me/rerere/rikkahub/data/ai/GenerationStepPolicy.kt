package me.rerere.rikkahub.data.ai

const val ORDINARY_GENERATION_MAX_STEPS = 32
const val SECOND_USER_GENERATION_MAX_STEPS = 64
const val MAX_CONFIGURABLE_GENERATION_STEPS = 256

/** Resolves an interactive turn budget without changing sub-agent-specific limits. */
fun resolveInteractiveGenerationMaxSteps(
    configured: Int?,
    isActiveLocalSecondUser: Boolean,
): Int = (configured ?: if (isActiveLocalSecondUser) {
    SECOND_USER_GENERATION_MAX_STEPS
} else {
    ORDINARY_GENERATION_MAX_STEPS
}).coerceIn(1, MAX_CONFIGURABLE_GENERATION_STEPS)
