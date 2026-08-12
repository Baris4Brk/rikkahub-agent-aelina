package me.rerere.rikkahub.learning.model

import kotlinx.serialization.Serializable

private val LOWER_SHA256 = Regex("[0-9a-f]{64}")

/** Persisted P1 rollout/consent root. Invalid imports fail closed to the default instance. */
@Serializable
data class LearningPreferencesV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val handoff: Boolean = false,
    val capture: Boolean = false,
    val jobs: Boolean = false,
    val reflectionShadow: Boolean = false,
    val policyCandidate: Boolean = false,
    val policyRetrievalShadow: Boolean = false,
    val backgroundWorkAuthorized: Boolean = false,
    val authorizedModelIdentityDigests: Set<String> = emptySet(),
    val allowMeteredNetwork: Boolean = false,
    val allowRemoteReflection: Boolean = false,
) {
    fun failClosed(): LearningPreferencesV1 = takeIf {
        schemaVersion == CURRENT_SCHEMA_VERSION &&
            authorizedModelIdentityDigests.size <= MAX_AUTHORIZED_MODELS &&
            authorizedModelIdentityDigests.all(LOWER_SHA256::matches) &&
            (!allowRemoteReflection || backgroundWorkAuthorized)
    } ?: LearningPreferencesV1()

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        private const val MAX_AUTHORIZED_MODELS = 8
    }
}
