package me.rerere.rikkahub.service

import android.content.Context

/** Persistent user intent for the accessibility keep-alive foreground service. */
object AccessibilityKeepAliveState {
    fun isEnabled(context: Context): Boolean = preferences(context)
        .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putBoolean(KEY_CONFIGURED, true)
            .apply()
    }

    /**
     * The first successful system bind proves that the user enabled the accessibility service.
     * Existing explicit keep-alive choices (including Stop from the notification) are retained.
     */
    fun recordAccessibilityConnection(context: Context): Boolean {
        return initializeFromSystemAuthorization(context, authorized = true)
    }

    /** Migrates an existing system authorization without overriding an explicit opt-out. */
    fun initializeFromSystemAuthorization(context: Context, authorized: Boolean): Boolean {
        val preferences = preferences(context)
        if (authorized && !preferences.getBoolean(KEY_CONFIGURED, false)) {
            preferences.edit()
                .putBoolean(KEY_CONFIGURED, true)
                .putBoolean(KEY_ENABLED, true)
                .apply()
        }
        return preferences.getBoolean(KEY_ENABLED, false)
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private const val PREFERENCES_NAME = "accessibility_keep_alive"
    private const val KEY_CONFIGURED = "configured"
    private const val KEY_ENABLED = "enabled"
}
