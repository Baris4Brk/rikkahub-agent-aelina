package me.rerere.rikkahub.learning.model

import me.rerere.rikkahub.data.ai.background.BackgroundGenerationUserPolicy
import me.rerere.rikkahub.data.ai.background.BackgroundGenerationUserPolicySource
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * Production rollout adapter. Settings remain default-off, but a persisted, explicit preference
 * can enable each P1 layer without a new build. Capability readiness is supplied by the installed
 * code/schema, never by user input.
 */
class SettingsLearningFeatureFlagSource(
    private val settingsStore: SettingsStore,
    private val capabilities: LearningFeatureCapabilities,
) : LearningFeatureFlagSource {
    override fun current(): ResolvedLearningFeatureFlags {
        val settings = settingsStore.settingsFlow.value
        if (settings.init) return LearningFeatureFlagPolicy.resolve(LearningFeatureFlags())
        return LearningFeatureFlagPolicy.resolve(
            configured = settings.learningPreferences.toFeatureFlags(capabilities.schemaReady),
            capabilities = capabilities,
        )
    }
}

/** Exact model-scoped consent adapter for the shared background-generation host. */
class SettingsLearningBackgroundGenerationUserPolicySource(
    private val settingsStore: SettingsStore,
) : BackgroundGenerationUserPolicySource {
    override fun current(): BackgroundGenerationUserPolicy {
        val settings = settingsStore.settingsFlow.value
        if (settings.init) return BackgroundGenerationUserPolicy()
        val preferences = settings.learningPreferences.failClosed()
        return BackgroundGenerationUserPolicy(
            backgroundWorkAuthorized = preferences.backgroundWorkAuthorized,
            authorizedModelIdentityDigests = preferences.authorizedModelIdentityDigests,
            allowRemoteReflection = preferences.allowRemoteReflection,
        )
    }
}

private fun LearningPreferencesV1.toFeatureFlags(
    trustedSchemaReady: Boolean,
) =
    failClosed().let { preferences ->
        LearningFeatureFlags(
            schemaReady = trustedSchemaReady,
            handoff = preferences.handoff,
            capture = preferences.capture,
            jobs = preferences.jobs,
            reflectionShadow = preferences.reflectionShadow,
            policyCandidate = preferences.policyCandidate,
            policyRetrievalShadow = preferences.policyRetrievalShadow,
            policyInjection = false,
            workflowCandidate = false,
            workflowPromotion = false,
            vector = false,
            temporalOperational = false,
            allowRemoteReflection = preferences.allowRemoteReflection,
        )
    }
