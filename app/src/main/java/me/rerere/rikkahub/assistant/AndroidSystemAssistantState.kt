package me.rerere.rikkahub.assistant

import android.app.KeyguardManager
import android.content.Context
import android.os.UserManager
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.ai.AgentSafetySettings

/** Live Android state sampled by every system-assistant submission. */
class AndroidSystemAssistantAccessState(context: Context) : SystemAssistantAccessState {
    private val appContext = context.applicationContext

    override fun isOwnerUser(): Boolean =
        appContext.getSystemService(UserManager::class.java)?.isSystemUser == true

    override fun isDeviceLocked(): Boolean {
        val keyguard = appContext.getSystemService(KeyguardManager::class.java)
        return keyguard?.let { it.isDeviceLocked || it.isKeyguardLocked } ?: true
    }
}

class AndroidSystemAssistantEmergencyStopState(
    private val safetySettings: AgentSafetySettings,
) : SystemAssistantEmergencyStopState {
    override suspend fun isActive(): Boolean = safetySettings.emergencyStopFlow.first()
}
