package me.rerere.rikkahub.assistant

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/** Minimal accessibility adapter used only for the standard navigation-bar button. */
class SystemAssistantAccessibilityButtonService : AccessibilityService() {
    private val buttonCallback = object :
        AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            Log.i(TAG, "System-assistant accessibility button clicked")
            sendBroadcast(
                Intent(SYSTEM_ASSISTANT_ACCESSIBILITY_INVOCATION_ACTION).apply {
                    setClass(
                        this@SystemAssistantAccessibilityButtonService,
                        SystemAssistantInvocationReceiver::class.java,
                    )
                }
            )
        }

        override fun onAvailabilityChanged(
            controller: AccessibilityButtonController,
            available: Boolean,
        ) {
            Log.i(TAG, "System-assistant accessibility button available=$available")
        }
    }

    private var buttonController: AccessibilityButtonController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        unregisterButtonCallback()
        buttonController = accessibilityButtonController.also { controller ->
            controller.registerAccessibilityButtonCallback(buttonCallback)
        }
        Log.i(TAG, "System-assistant accessibility button service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterButtonCallback()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        unregisterButtonCallback()
        super.onDestroy()
    }

    private fun unregisterButtonCallback() {
        buttonController?.unregisterAccessibilityButtonCallback(buttonCallback)
        buttonController = null
    }

    private companion object {
        const val TAG = "RikkaAssistButton"
    }
}
