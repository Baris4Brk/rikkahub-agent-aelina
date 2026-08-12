package me.rerere.rikkahub.memory.dreaming.runtime

import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamingFeatureFlags

/** Reads the one current app-global policy immediately before scheduling or model admission. */
fun interface DreamingCostPolicySource {
    suspend fun costPolicy(): DreamingCostPolicy
}

/** Combined settings seam so flags and cost policy come from one atomically persisted JSON root. */
interface DreamingPreferencesSource : DreamingCostPolicySource, DreamingFeatureFlagSource {
    suspend fun preferences(): DreamingPreferencesV1

    fun schemaReady(): Boolean

    override suspend fun costPolicy(): DreamingCostPolicy =
        preferences().failClosed().costPolicy

    override suspend fun flagsFor(scopeId: DreamScopeId): DreamingFeatureFlags =
        preferences().failClosed().forScope(scopeId).toFeatureFlags(schemaReady())

    override suspend fun anySynthesisGenerationEnabled(): Boolean =
        schemaReady() && preferences().failClosed().scopes.values.any { it.generate }
}

/** Production adapter. DI must supply the trusted Room-schema readiness bit, never user input. */
class SettingsDreamingPreferencesSource(
    private val settingsStore: SettingsStore,
    private val trustedSchemaReady: Boolean,
) : DreamingPreferencesSource {
    override suspend fun preferences(): DreamingPreferencesV1 =
        settingsStore.settingsFlow.value.dreamingPreferences.failClosed()

    override fun schemaReady(): Boolean = trustedSchemaReady
}

/** Safe fallback for tests, recovery, or wiring failures: no behavior is enabled. */
object DisabledDreamingPreferencesSource : DreamingPreferencesSource {
    override suspend fun preferences(): DreamingPreferencesV1 = DreamingPreferencesV1()

    override fun schemaReady(): Boolean = false
}
