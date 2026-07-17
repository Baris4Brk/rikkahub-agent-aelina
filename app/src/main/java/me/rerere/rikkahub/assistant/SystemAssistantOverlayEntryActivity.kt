package me.rerere.rikkahub.assistant

import android.app.Activity
import android.content.Intent
import android.app.KeyguardManager
import android.os.Bundle
import android.os.UserManager
import android.util.Log

const val SYSTEM_ASSISTANT_HARDWARE_INVOCATION_ACTION =
    "me.rerere.rikkahub.action.SHOW_SYSTEM_ASSISTANT_HARDWARE"

internal fun isSystemAssistantHardwareInvocationAction(action: String?): Boolean =
    action == SYSTEM_ASSISTANT_HARDWARE_INVOCATION_ACTION

/**
 * Minimal exported adapter for OEM hardware-key launchers that require an Activity target.
 *
 * It runs beside the active voice service in the lightweight process, accepts no prompt or
 * destination extras, and can only ask the already-bound VoiceInteractionService to show its
 * existing local session for the unlocked Android owner.
 */
class SystemAssistantOverlayEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isSystemAssistantHardwareInvocationAction(intent?.action)) {
            finish()
            return
        }

        showSessionOrActivityFallback()
    }

    private fun showSessionOrActivityFallback() {
        val userManager = getSystemService(UserManager::class.java)
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        val mayShow = userManager != null && keyguardManager != null &&
            shouldShowLocalSystemAssistant(
                isSystemUser = userManager.isSystemUser,
                isDeviceLocked = keyguardManager.isDeviceLocked,
                isKeyguardLocked = keyguardManager.isKeyguardLocked,
            )
        if (!mayShow) {
            Log.w(TAG, "Ignoring hardware invocation outside the unlocked owner")
            finish()
            return
        }

        if (RikkaVoiceInteractionService.showLocalSession()) {
            finish()
        } else {
            Log.i(TAG, "Voice service unavailable; using the activity-hosted local surface")
            startActivity(
                Intent(this, SystemAssistantHardwareOverlayActivity::class.java).apply {
                    action = SYSTEM_ASSISTANT_HARDWARE_INVOCATION_ACTION
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
            finish()
        }
    }

    private companion object {
        const val TAG = "RikkaAssistHardware"
    }
}
