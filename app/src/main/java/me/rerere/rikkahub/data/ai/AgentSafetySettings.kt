package me.rerere.rikkahub.data.ai

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.agentSafetyStore by preferencesDataStore(name = "agent_safety")

/**
 * Persisted agent safety settings that control high-risk tool execution, remote entry
 * points, background automation, and the emergency-stop mechanism.
 *
 * Every field has a default that is maximally restrictive (safe default).
 * The [emergencyStop] flag is the highest-priority gate — when set, ALL tool execution
 * is blocked regardless of other settings.
 */
class AgentSafetySettings(private val context: Context) {

    companion object {
        private val HIGH_RISK_TOOLS_ENABLED = booleanPreferencesKey("high_risk_tools_enabled")
        private val REMOTE_TOOL_CALLS_ENABLED = booleanPreferencesKey("remote_tool_calls_enabled")
        private val BACKGROUND_AUTOMATION_ENABLED = booleanPreferencesKey("background_automation_enabled")
        private val ALLOW_WHILE_DEVICE_LOCKED = booleanPreferencesKey("allow_while_device_locked")
        private val PRIVILEGED_BRIDGE_ENABLED = booleanPreferencesKey("privileged_bridge_enabled")
        private val EMERGENCY_STOP = booleanPreferencesKey("emergency_stop")
    }

    // ── Flows ──────────────────────────────────────────────────────────────────────

    val highRiskToolsEnabledFlow: Flow<Boolean> = context.agentSafetyStore.data
        .map { it[HIGH_RISK_TOOLS_ENABLED] ?: false }

    val remoteToolCallsEnabledFlow: Flow<Boolean> = context.agentSafetyStore.data
        .map { it[REMOTE_TOOL_CALLS_ENABLED] ?: false }

    val backgroundAutomationEnabledFlow: Flow<Boolean> = context.agentSafetyStore.data
        .map { it[BACKGROUND_AUTOMATION_ENABLED] ?: false }

    val allowWhileDeviceLockedFlow: Flow<Boolean> = context.agentSafetyStore.data
        .map { it[ALLOW_WHILE_DEVICE_LOCKED] ?: false }

    val privilegedBridgeEnabledFlow: Flow<Boolean> = context.agentSafetyStore.data
        .map { it[PRIVILEGED_BRIDGE_ENABLED] ?: false }

    val emergencyStopFlow: Flow<Boolean> = context.agentSafetyStore.data
        .map { it[EMERGENCY_STOP] ?: false }

    // ── Snapshot helpers (for non-coroutine paths like tool execution) ─────────────

    fun isEmergencyStop(): Boolean = runBlocking { emergencyStopFlow.first() }
    fun isHighRiskToolsEnabled(): Boolean = runBlocking { highRiskToolsEnabledFlow.first() }
    fun isRemoteToolCallsEnabled(): Boolean = runBlocking { remoteToolCallsEnabledFlow.first() }
    fun isBackgroundAutomationEnabled(): Boolean = runBlocking { backgroundAutomationEnabledFlow.first() }
    fun isAllowWhileDeviceLocked(): Boolean = runBlocking { allowWhileDeviceLockedFlow.first() }
    fun isPrivilegedBridgeEnabled(): Boolean = runBlocking { privilegedBridgeEnabledFlow.first() }

    // ── Setters ────────────────────────────────────────────────────────────────────

    suspend fun setHighRiskToolsEnabled(enabled: Boolean) {
        context.agentSafetyStore.edit { it[HIGH_RISK_TOOLS_ENABLED] = enabled }
    }

    suspend fun setRemoteToolCallsEnabled(enabled: Boolean) {
        context.agentSafetyStore.edit { it[REMOTE_TOOL_CALLS_ENABLED] = enabled }
    }

    suspend fun setBackgroundAutomationEnabled(enabled: Boolean) {
        context.agentSafetyStore.edit { it[BACKGROUND_AUTOMATION_ENABLED] = enabled }
    }

    suspend fun setAllowWhileDeviceLocked(enabled: Boolean) {
        context.agentSafetyStore.edit { it[ALLOW_WHILE_DEVICE_LOCKED] = enabled }
    }

    suspend fun setPrivilegedBridgeEnabled(enabled: Boolean) {
        context.agentSafetyStore.edit { it[PRIVILEGED_BRIDGE_ENABLED] = enabled }
    }

    /**
     * Set emergency stop. When true:
     * 1. All new tool calls are rejected.
     * 2. Running Agent Runs and workflows should be cancelled (caller's responsibility).
     * 3. Telegram / WebServer / MCP services should be stopped.
     * 4. Pending approval tokens and queued tasks are cleared.
     *
     * Set to false to resume normal operation (other gates still apply).
     */
    suspend fun setEmergencyStop(stopped: Boolean) {
        context.agentSafetyStore.edit { it[EMERGENCY_STOP] = stopped }
    }

    /** Toggle emergency stop (for emergency stop page button). */
    suspend fun toggleEmergencyStop() {
        context.agentSafetyStore.edit { prefs ->
            prefs[EMERGENCY_STOP] = !(prefs[EMERGENCY_STOP] ?: false)
        }
    }

    /**
     * Reset all safety settings to their defaults (maximally restrictive).
     */
    suspend fun resetToDefaults() {
        context.agentSafetyStore.edit {
            it[HIGH_RISK_TOOLS_ENABLED] = false
            it[REMOTE_TOOL_CALLS_ENABLED] = false
            it[BACKGROUND_AUTOMATION_ENABLED] = false
            it[ALLOW_WHILE_DEVICE_LOCKED] = false
            it[PRIVILEGED_BRIDGE_ENABLED] = false
            it[EMERGENCY_STOP] = false
        }
    }
}
