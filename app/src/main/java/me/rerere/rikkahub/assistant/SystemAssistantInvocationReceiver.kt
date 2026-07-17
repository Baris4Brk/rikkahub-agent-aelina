package me.rerere.rikkahub.assistant

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log

const val SYSTEM_ASSISTANT_TEST_INVOCATION_ACTION =
    "me.rerere.rikkahub.action.SHOW_SYSTEM_ASSISTANT_TEST"
const val SYSTEM_ASSISTANT_ACCESSIBILITY_INVOCATION_ACTION =
    "me.rerere.rikkahub.action.SHOW_SYSTEM_ASSISTANT_ACCESSIBILITY"

/** Same-UID bridge from trusted local surfaces to the active lightweight voice process. */
class SystemAssistantInvocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ALLOWED_ACTIONS) return
        val userManager = context.getSystemService(UserManager::class.java)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        val mayShow = userManager != null && keyguardManager != null &&
            shouldShowLocalSystemAssistant(
                isSystemUser = userManager.isSystemUser,
                isDeviceLocked = keyguardManager.isDeviceLocked,
                isKeyguardLocked = keyguardManager.isKeyguardLocked,
            )
        if (!mayShow) {
            Log.w(TAG, "Ignoring local invocation outside the unlocked Android owner")
            return
        }
        if (!RikkaVoiceInteractionService.showLocalSession()) {
            Log.w(TAG, "Ignoring local invocation because the voice service is not ready")
        }
    }

    private companion object {
        const val TAG = "RikkaVoiceLocal"
        val ALLOWED_ACTIONS = setOf(
            SYSTEM_ASSISTANT_TEST_INVOCATION_ACTION,
            SYSTEM_ASSISTANT_ACCESSIBILITY_INVOCATION_ACTION,
        )
    }
}

internal fun shouldShowLocalSystemAssistant(
    isSystemUser: Boolean,
    isDeviceLocked: Boolean,
    isKeyguardLocked: Boolean,
): Boolean = isSystemUser && !isDeviceLocked && !isKeyguardLocked
