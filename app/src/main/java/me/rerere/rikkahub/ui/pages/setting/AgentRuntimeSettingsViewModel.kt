package me.rerere.rikkahub.ui.pages.setting

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.ai.background.BackgroundAuthorizationCandidate
import me.rerere.rikkahub.data.ai.background.RemoteReflectionDisclosureTarget
import me.rerere.rikkahub.data.ai.background.SettingsBackedBackgroundGenerationHost
import me.rerere.rikkahub.plugin.InstalledPluginRecord
import me.rerere.rikkahub.plugin.PluginBuiltInExampleInstaller
import me.rerere.rikkahub.plugin.PluginPackageInstaller
import me.rerere.rikkahub.plugin.PluginRegistryStore
import me.rerere.rikkahub.plugin.PluginReviewStatus
import me.rerere.rikkahub.learning.model.LearningRolloutChangeResult
import me.rerere.rikkahub.learning.model.LearningRolloutController
import me.rerere.rikkahub.learning.model.LearningRolloutStage
import me.rerere.rikkahub.learning.model.LearningCuratorOperation
import me.rerere.rikkahub.learning.model.LearningRetentionPreferencesV1
import me.rerere.rikkahub.learning.model.LearningRetentionPresetV1

sealed interface PluginSettingsNotice {
    data class Installed(val name: String, val addedPermissionCount: Int) : PluginSettingsNotice
    data class Failed(val code: String) : PluginSettingsNotice
    data object Updated : PluginSettingsNotice
}

class AgentRuntimeSettingsViewModel(
    context: Context,
    private val settingsStore: SettingsStore,
    private val registry: PluginRegistryStore,
    private val installer: PluginPackageInstaller,
    private val builtInInstaller: PluginBuiltInExampleInstaller,
    private val learningRollout: LearningRolloutController,
    private val backgroundGenerationHost: SettingsBackedBackgroundGenerationHost,
) : ViewModel() {
    private val appContext = context.applicationContext

    val settings: StateFlow<Settings> = settingsStore.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        Settings.dummy(),
    )
    val learningModelCandidates: StateFlow<List<BackgroundAuthorizationCandidate>> =
        settingsStore.settingsFlow
            .map { backgroundGenerationHost.listAuthorizationCandidates() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val remoteReflectionDisclosureTargets: StateFlow<List<RemoteReflectionDisclosureTarget>> =
        settingsStore.settingsFlow
            .map { backgroundGenerationHost.listRemoteReflectionDisclosureTargets() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _plugins = MutableStateFlow(registry.snapshot())
    val plugins: StateFlow<List<InstalledPluginRecord>> = _plugins.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _notice = MutableStateFlow<PluginSettingsNotice?>(null)
    val notice: StateFlow<PluginSettingsNotice?> = _notice.asStateFlow()
    private val _learningNotice = MutableStateFlow<String?>(null)
    val learningNotice: StateFlow<String?> = _learningNotice.asStateFlow()

    fun updateSettings(transform: (Settings) -> Settings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    fun updateCurrentAssistant(transform: (Assistant) -> Assistant) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(
                    assistants = current.assistants.map { assistant ->
                        if (assistant.id == current.assistantId) transform(assistant) else assistant
                    }
                )
            }
        }
    }

    fun setLearningStage(
        stage: LearningRolloutStage,
        exactCandidate: BackgroundAuthorizationCandidate? = null,
    ) {
        viewModelScope.launch {
            val requiresModel = stage == LearningRolloutStage.CANDIDATE_SHADOW ||
                stage == LearningRolloutStage.RETRIEVAL_SHADOW ||
                stage == LearningRolloutStage.REVIEWED_POLICY_OPT_IN
            val liveCandidate = exactCandidate?.let { selected ->
                backgroundGenerationHost.listAuthorizationCandidates().singleOrNull { live ->
                    live.kind == selected.kind &&
                        live.providerIdentityDigest == selected.providerIdentityDigest &&
                        live.modelIdentityDigest == selected.modelIdentityDigest
                }
            }
            if (requiresModel && liveCandidate == null) {
                _learningNotice.value = "learning_rollout_invalid_model_identity"
                return@launch
            }
            _learningNotice.value = when (
                val result = learningRollout.setStage(
                    stage = stage,
                    exactModelIdentityDigest = liveCandidate?.modelIdentityDigest,
                    exactRemoteProviderIdentityDigest = liveCandidate
                        ?.takeIf { it.isRemote }
                        ?.providerIdentityDigest,
                    authorizeRemoteReflection = liveCandidate?.isRemote == true,
                )
            ) {
                is LearningRolloutChangeResult.Applied -> "learning_rollout_${result.stage.name.lowercase()}"
                is LearningRolloutChangeResult.Rejected -> "learning_rollout_${result.failure.name.lowercase()}"
            }
        }
    }

    fun setRemoteReflectionAllowed(
        allowed: Boolean,
        exactTarget: RemoteReflectionDisclosureTarget? = null,
    ) {
        viewModelScope.launch {
            _learningNotice.value = when (val result =
                learningRollout.setRemoteReflectionAllowed(
                    allowed,
                    exactTarget?.providerIdentityDigest,
                    exactTarget?.modelIdentityDigest,
                )
            ) {
                is LearningRolloutChangeResult.Applied -> "learning_remote_reflection_updated"
                is LearningRolloutChangeResult.Rejected ->
                    "learning_remote_reflection_${result.failure.name.lowercase()}"
            }
        }
    }

    fun rejectRemoteReflectionDisclosureUnavailable() {
        _learningNotice.value = "learning_remote_reflection_disclosure_unavailable"
    }

    fun setWorkflowCandidateEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _learningNotice.value = when (val result =
                learningRollout.setWorkflowCandidateEnabled(enabled)
            ) {
                is LearningRolloutChangeResult.Applied -> "learning_workflow_candidate_updated"
                is LearningRolloutChangeResult.Rejected ->
                    "learning_workflow_candidate_${result.failure.name.lowercase()}"
            }
        }
    }

    fun setWorkflowPromotionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _learningNotice.value = when (val result =
                learningRollout.setWorkflowPromotionEnabled(enabled)
            ) {
                is LearningRolloutChangeResult.Applied -> "learning_workflow_promotion_updated"
                is LearningRolloutChangeResult.Rejected ->
                    "learning_workflow_promotion_${result.failure.name.lowercase()}"
            }
        }
    }

    fun setCuratorOperationEnabled(operation: LearningCuratorOperation, enabled: Boolean) {
        viewModelScope.launch {
            _learningNotice.value = when (val result =
                learningRollout.setCuratorOperationEnabled(operation, enabled)
            ) {
                is LearningRolloutChangeResult.Applied ->
                    "learning_curator_${operation.name.lowercase()}_updated"
                is LearningRolloutChangeResult.Rejected ->
                    "learning_curator_${operation.name.lowercase()}_${result.failure.name.lowercase()}"
            }
        }
    }

    fun setTraceRetention(preset: LearningRetentionPresetV1) {
        setRetention { current -> current.copy(tracePreset = preset) }
    }

    fun setRewardRetention(preset: LearningRetentionPresetV1) {
        setRetention { current -> current.copy(rewardPreset = preset) }
    }

    private fun setRetention(
        transform: (LearningRetentionPreferencesV1) -> LearningRetentionPreferencesV1,
    ) {
        viewModelScope.launch {
            val current = settingsStore.settingsFlow.value.learningPreferences.retention.failClosed()
            _learningNotice.value = when (val result = learningRollout.setRetention(transform(current))) {
                is LearningRolloutChangeResult.Applied -> "learning_retention_updated"
                is LearningRolloutChangeResult.Rejected ->
                    "learning_retention_${result.failure.name.lowercase()}"
            }
        }
    }

    fun clearLearningNotice() {
        _learningNotice.value = null
    }

    fun install(uri: Uri) {
        runPluginOperation {
            val importDir = File(appContext.cacheDir, "plugin-imports").apply { mkdirs() }
            val archive = File.createTempFile("plugin-import-", ".zip", importDir)
            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(archive).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_IMPORT_BYTES) { "plugin_archive_size_invalid" }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                } ?: error("plugin_archive_unreadable")
                installer.install(archive).getOrThrow()
            } finally {
                archive.delete()
            }
        }
    }

    fun installBuiltInExample() {
        runPluginOperation { builtInInstaller.install().getOrThrow() }
    }

    fun approve(pluginId: String) {
        updatePlugin(pluginId) { record ->
            record.copy(
                enabled = false,
                reviewStatus = PluginReviewStatus.APPROVED,
                failureTimestampsMs = emptyList(),
                pendingAddedPermissions = emptySet(),
            )
        }
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        updatePlugin(pluginId) { record ->
            require(!enabled || record.reviewStatus == PluginReviewStatus.APPROVED) {
                "plugin_not_approved"
            }
            record.copy(enabled = enabled)
        }
    }

    fun setPluginEnabledForCurrentAssistant(pluginId: String, enabled: Boolean) {
        updateCurrentAssistant { assistant ->
            assistant.copy(
                enabledPluginIds = if (enabled) {
                    assistant.enabledPluginIds + pluginId
                } else {
                    assistant.enabledPluginIds - pluginId
                }
            )
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    private fun updatePlugin(
        pluginId: String,
        transform: (InstalledPluginRecord) -> InstalledPluginRecord,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { registry.update(pluginId, transform) }
                .onSuccess {
                    refreshPlugins()
                    _notice.value = PluginSettingsNotice.Updated
                }
                .onFailure { failure ->
                    _notice.value = PluginSettingsNotice.Failed(failure.toPluginCode())
                }
        }
    }

    private fun runPluginOperation(
        operation: suspend () -> me.rerere.rikkahub.plugin.PluginInstallResult,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = withContext(Dispatchers.IO) { operation() }
                refreshPlugins()
                _notice.value = PluginSettingsNotice.Installed(
                    result.record.name,
                    result.addedPermissions.size,
                )
            } catch (failure: Throwable) {
                _notice.value = PluginSettingsNotice.Failed(failure.toPluginCode())
            } finally {
                _busy.value = false
            }
        }
    }

    private fun refreshPlugins() {
        _plugins.value = registry.snapshot()
    }

    private fun Throwable.toPluginCode(): String = message
        ?.takeIf { it.matches(Regex("[a-z0-9_]{3,80}")) }
        ?: "plugin_operation_failed"

    private companion object {
        const val MAX_IMPORT_BYTES = 8L * 1024 * 1024
    }
}
