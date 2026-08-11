package me.rerere.rikkahub.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings

internal data class AccessibilityRecoveryResult(
    val ok: Boolean,
    val code: String,
    val message: String,
)

/** Direct recovery path used only when WRITE_SECURE_SETTINGS was explicitly granted. */
internal class AccessibilitySecureSettingsRestorer(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val component = ComponentName(
        appContext,
        RikkaAccessibilityService::class.java,
    ).flattenToString()

    fun isEnabledInSettings(): Boolean = AccessibilityServiceListPolicy.contains(
        Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        component,
    )

    suspend fun restore(forceRebind: Boolean): AccessibilityRecoveryResult {
        if (Process.myUid() / PER_USER_RANGE != OWNER_USER_ID) {
            return AccessibilityRecoveryResult(
                ok = false,
                code = "OWNER_USER_ONLY",
                message = "Accessibility recovery is restricted to Android user 0.",
            )
        }
        if (appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return AccessibilityRecoveryResult(
                ok = false,
                code = "WRITE_SECURE_SETTINGS_REQUIRED",
                message = "WRITE_SECURE_SETTINGS has not been granted.",
            )
        }
        var removedSnapshot: String? = null
        return try {
            var current = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            )
            if (forceRebind && AccessibilityServiceListPolicy.contains(current, component)) {
                val removed = Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    AccessibilityServiceListPolicy.remove(current, component),
                )
                if (!removed) return writeRejected()
                // This short critical section must finish even if onUnbind() starts the already
                // running monitor service again; cancellation here could strand the component off.
                removedSnapshot = AccessibilityServiceListPolicy.remove(current, component)
                SystemClock.sleep(REBIND_SETTLE_MS)
                current = removedSnapshot
            }

            val merged = AccessibilityServiceListPolicy.add(current, component)
            if (!AccessibilityServiceListPolicy.contains(current, component) || forceRebind) {
                if (!Settings.Secure.putString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        merged,
                    )
                ) {
                    return writeRejected()
                }
                removedSnapshot = null
            }
            if (!Settings.Secure.putInt(
                    resolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1,
                )
            ) {
                return writeRejected()
            }
            if (!isEnabledInSettings()) {
                AccessibilityRecoveryResult(
                    ok = false,
                    code = "VERIFY_FAILED",
                    message = "Android did not retain RikkaHub's accessibility component.",
                )
            } else {
                AccessibilityRecoveryResult(
                    ok = true,
                    code = if (forceRebind) "ACCESSIBILITY_REBOUND" else "ACCESSIBILITY_RESTORED",
                    message = "RikkaHub accessibility authorization was restored.",
                )
            }
        } catch (error: SecurityException) {
            AccessibilityRecoveryResult(
                ok = false,
                code = "WRITE_SECURE_SETTINGS_REQUIRED",
                message = error.message ?: "Secure settings write was denied.",
            )
        } catch (error: Throwable) {
            AccessibilityRecoveryResult(
                ok = false,
                code = "RECOVERY_FAILED",
                message = error.message ?: "Accessibility recovery failed.",
            )
        } finally {
            removedSnapshot?.let { snapshot ->
                // Best-effort rollback: a failed/rejected second write must not leave the service
                // disabled merely because a rebind pulse was attempted.
                runCatching {
                    val latest = Settings.Secure.getString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    ) ?: snapshot
                    Settings.Secure.putString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        AccessibilityServiceListPolicy.add(latest, component),
                    )
                    Settings.Secure.putInt(
                        resolver,
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1,
                    )
                }
            }
        }
    }

    private fun writeRejected() = AccessibilityRecoveryResult(
        ok = false,
        code = "WRITE_REJECTED",
        message = "Android rejected the secure settings update.",
    )

    private companion object {
        private const val REBIND_SETTLE_MS = 250L
        private const val PER_USER_RANGE = 100_000
        private const val OWNER_USER_ID = 0
    }
}
