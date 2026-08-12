package me.rerere.rikkahub.memory.dreaming.runtime

import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamingFeatureFlags

/**
 * Runtime seam for the M5 settings integration. M4 production wiring is deliberately all-off;
 * tests can inject scope-specific flags without adding hidden Preferences defaults.
 */
fun interface DreamingFeatureFlagSource {
    suspend fun flagsFor(scopeId: DreamScopeId): DreamingFeatureFlags

    /**
     * Global scheduling gate. The safe default is false so an adapter that can only answer a
     * single-scope query can never arm background synthesis accidentally.
     */
    suspend fun anySynthesisGenerationEnabled(): Boolean = false
}

object DisabledDreamingFeatureFlagSource : DreamingFeatureFlagSource {
    override suspend fun flagsFor(scopeId: DreamScopeId): DreamingFeatureFlags =
        DreamingFeatureFlags.M1AllOff

    override suspend fun anySynthesisGenerationEnabled(): Boolean = false
}

/**
 * Generation and consumption are independent switches: shadow mode is one consumer policy, not a
 * prerequisite for producing a snapshot. Keeping this predicate shared prevents the worker and
 * Room store from silently drifting back to a shadow-only gate.
 */
internal fun DreamingFeatureFlags.allowsSynthesisGeneration(): Boolean =
    schemaReady && generate
