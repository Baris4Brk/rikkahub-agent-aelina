package me.rerere.rikkahub.learning.retrieval

import me.rerere.rikkahub.learning.model.LearningFeatureFlagSource

sealed interface PolicyShadowRuntimeResult {
    data class Completed(val trace: PolicyRetrievalTrace) : PolicyShadowRuntimeResult
    data object Disabled : PolicyShadowRuntimeResult
    data object Unavailable : PolicyShadowRuntimeResult
}

/** P1 exposes only the content-free trace; candidate text and IDs never leave the runtime fence. */
fun interface PolicyShadowRuntimePort {
    suspend fun retrieveShadow(request: PolicyRetrievalRequest): PolicyShadowRuntimeResult
}

internal class PolicyShadowFeatureGate(
    private val flags: LearningFeatureFlagSource,
) {
    fun enabled(): Boolean = runCatching { flags.current() }
        .getOrNull()
        ?.let { it.isValid && it.effective.policyRetrievalShadow }
        ?: false
}
