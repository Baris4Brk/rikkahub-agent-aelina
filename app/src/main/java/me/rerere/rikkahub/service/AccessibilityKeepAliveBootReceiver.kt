package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AccessibilityKeepAliveBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in SUPPORTED_ACTIONS) return
        if (AccessibilityKeepAliveState.isEnabled(context)) {
            AccessibilityKeepAliveService.start(context)
        }
    }

    private companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
