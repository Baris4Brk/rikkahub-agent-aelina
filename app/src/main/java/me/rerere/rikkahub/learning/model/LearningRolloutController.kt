package me.rerere.rikkahub.learning.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.learning.jobs.LearningWorkScheduler

enum class LearningRolloutFailure {
    SETTINGS_UNINITIALIZED,
    INVALID_MODEL_IDENTITY,
    PERSISTENCE_FAILED,
    STAGE_NOT_ELIGIBLE,
}

sealed interface LearningRolloutChangeResult {
    data class Applied(val stage: LearningRolloutStage) : LearningRolloutChangeResult
    data class Rejected(val failure: LearningRolloutFailure) : LearningRolloutChangeResult
}

/**
 * Single production writer for the persisted P1 rollout state and its WorkManager side effects.
 * UI code cannot manufacture arbitrary combinations of the underlying booleans.
 */
class LearningRolloutController(
    private val settingsStore: SettingsStore,
    private val workScheduler: LearningWorkScheduler,
) {
    private val mutex = Mutex()

    suspend fun setStage(
        stage: LearningRolloutStage,
        exactModelIdentityDigest: String? = null,
        exactRemoteProviderIdentityDigest: String? = null,
        authorizeRemoteReflection: Boolean = false,
    ): LearningRolloutChangeResult = mutex.withLock {
        val before = settingsStore.settingsFlow.value
        if (before.init) {
            return@withLock LearningRolloutChangeResult.Rejected(
                LearningRolloutFailure.SETTINGS_UNINITIALIZED,
            )
        }
        val configured = try {
            LearningRolloutPolicy.configure(
                current = before.learningPreferences.failClosed(),
                stage = stage,
                exactModelIdentityDigest = exactModelIdentityDigest,
                exactRemoteProviderIdentityDigest = exactRemoteProviderIdentityDigest,
                authorizeRemoteReflection = authorizeRemoteReflection,
            )
        } catch (_: IllegalArgumentException) {
            return@withLock LearningRolloutChangeResult.Rejected(
                LearningRolloutFailure.INVALID_MODEL_IDENTITY,
            )
        }
        try {
            settingsStore.update { current ->
                check(!current.init) { "Settings became unavailable during Learning rollout" }
                current.copy(learningPreferences = configured)
            }
        } catch (_: Exception) {
            return@withLock LearningRolloutChangeResult.Rejected(
                LearningRolloutFailure.PERSISTENCE_FAILED,
            )
        }
        if (stage == LearningRolloutStage.OFF) {
            workScheduler.cancelAll()
        } else {
            workScheduler.scheduleStartupAndRecovery()
        }
        LearningRolloutChangeResult.Applied(stage)
    }

    suspend fun setRemoteReflectionAllowed(
        allowed: Boolean,
        exactProviderIdentityDigest: String? = null,
        exactModelIdentityDigest: String? = null,
    ): LearningRolloutChangeResult = updatePreferences { current ->
        LearningRolloutPolicy.configureRemoteReflection(
            current,
            allowed,
            exactProviderIdentityDigest,
            exactModelIdentityDigest,
        )
    }

    suspend fun setRetention(
        retention: LearningRetentionPreferencesV1,
    ): LearningRolloutChangeResult = updatePreferences { current ->
        LearningRolloutPolicy.configureRetention(current, retention)
    }

    suspend fun setWorkflowCandidateEnabled(
        enabled: Boolean,
    ): LearningRolloutChangeResult = updatePreferences { current ->
        LearningRolloutPolicy.configureWorkflowCandidate(current, enabled)
    }

    suspend fun setWorkflowPromotionEnabled(
        enabled: Boolean,
    ): LearningRolloutChangeResult = updatePreferences { current ->
        LearningRolloutPolicy.configureWorkflowPromotion(current, enabled)
    }

    suspend fun setCuratorOperationEnabled(
        operation: LearningCuratorOperation,
        enabled: Boolean,
    ): LearningRolloutChangeResult = updatePreferences { current ->
        LearningRolloutPolicy.configureCuratorOperation(current, operation, enabled)
    }

    private suspend fun updatePreferences(
        transform: (LearningPreferencesV1) -> LearningPreferencesV1,
    ): LearningRolloutChangeResult = mutex.withLock {
        val before = settingsStore.settingsFlow.value
        if (before.init) {
            return@withLock LearningRolloutChangeResult.Rejected(
                LearningRolloutFailure.SETTINGS_UNINITIALIZED,
            )
        }
        val configured = try {
            transform(before.learningPreferences.failClosed())
        } catch (_: IllegalArgumentException) {
            return@withLock LearningRolloutChangeResult.Rejected(
                LearningRolloutFailure.STAGE_NOT_ELIGIBLE,
            )
        }
        try {
            settingsStore.update { current ->
                check(!current.init) { "Settings became unavailable during Learning update" }
                current.copy(learningPreferences = configured)
            }
        } catch (_: Exception) {
            return@withLock LearningRolloutChangeResult.Rejected(
                LearningRolloutFailure.PERSISTENCE_FAILED,
            )
        }
        workScheduler.scheduleMaintenance()
        LearningRolloutChangeResult.Applied(
            LearningRolloutPolicy.stageOf(configured) ?: LearningRolloutStage.OFF,
        )
    }
}
