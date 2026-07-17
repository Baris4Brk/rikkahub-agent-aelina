package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.AiLogLevel
import me.rerere.rikkahub.data.datastore.SettingsStore
sealed class AILogging {
    data class Generation(
        val modelId: String,
        val providerType: String,
        val messageCount: Int,
        val inputCharacters: Int,
        val toolCount: Int,
        val requestPurpose: String,
        val stream: Boolean,
    ) : AILogging()
}

private const val MAX_LOGS = 32

class AILoggingManager(
    private val settingsStore: SettingsStore,
    appScope: AppScope,
) {
    private val logs = MutableStateFlow<List<AILogging>>(emptyList())
    private val logLevel = MutableStateFlow(settingsStore.settingsFlow.value.aiLogLevel)

    init {
        appScope.launch {
            settingsStore.settingsFlow
                .map { it.aiLogLevel }
                .collectLatest { logLevel.value = it }
        }
    }

    fun getLogs(): StateFlow<List<AILogging>> = logs

    fun getLogLevel(): StateFlow<AiLogLevel> = logLevel

    suspend fun setLogLevel(level: AiLogLevel) {
        settingsStore.update { it.copy(aiLogLevel = level) }
    }

    fun addLog(log: AILogging) {
        if (logLevel.value == AiLogLevel.OFF) return
        logs.value = logs.value + log
        if (logs.value.size > MAX_LOGS) {
            logs.value = logs.value.drop(1)
        }
    }

    fun clearLogs() {
        logs.value = emptyList()
    }
}
