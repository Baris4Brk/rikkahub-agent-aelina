package me.rerere.rikkahub.assistant

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val retryHandler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable(::attemptShowSession)
    private var attemptCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isSystemAssistantHardwareInvocationAction(intent?.action)) {
            finish()
            return
        }

        attemptShowSession()
    }

    private fun attemptShowSession() {
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

        attemptCount++
        if (RikkaVoiceInteractionService.showLocalSession()) {
            finish()
        } else if (attemptCount >= MAX_SHOW_ATTEMPTS) {
            Log.w(TAG, "Ignoring hardware invocation because the voice service is not ready")
            finish()
        } else {
            retryHandler.postDelayed(retryRunnable, RETRY_DELAY_MS)
        }
    }

    override fun onDestroy() {
        retryHandler.removeCallbacks(retryRunnable)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "RikkaAssistHardware"
        const val MAX_SHOW_ATTEMPTS = 8
        const val RETRY_DELAY_MS = 150L
    }
}
