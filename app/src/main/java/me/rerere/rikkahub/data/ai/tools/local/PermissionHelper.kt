package me.rerere.rikkahub.data.ai.tools.local

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

object PermissionHelper {
    fun hasRuntime(ctx: Context, perms: List<String>): Boolean =
        perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    fun hasWriteSettings(ctx: Context): Boolean = Settings.System.canWrite(ctx)

    fun hasDndAccess(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun writeSettingsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${ctx.packageName}".toUri())

    fun dndAccessIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // ACTION_NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS is @hide/@SystemApi; use literal action string.
            Intent("android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS")
                .setData("package:${ctx.packageName}".toUri())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }

    /**
     * True iff the app is exempted from Doze battery optimizations. Without this exemption,
     * OEM-aggressive ROMs (Xiaomi/OPPO/OnePlus/Vivo) cut network for foreground services when
     * the screen turns off, which makes the Telegram bot's long-poll go silent.
     */
    fun ignoresBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false

    /**
     * Direct prompt to whitelist the app from Doze. Required-permission per Play Store policy
     * is `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; manifest declares it.
     */
    fun requestIgnoreBatteryOptimizationsIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData("package:${ctx.packageName}".toUri())

    /** Fallback page for users who declined the prompt; shows the system-wide list. */
    fun batteryOptimizationsListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun hasAccessibilityService(ctx: Context): Boolean =
        AccessibilityServiceHandle.isEnabledInSettings(ctx)

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun hasNotificationListener(ctx: Context): Boolean =
        NotificationListenerHandle.isEnabledInSettings(ctx)

    fun notificationListenerSettingsIntent(): Intent =
        Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * "All files access" — gates File.listFiles() on shared storage paths like
     * /storage/emulated/0/Download. Without it, scoped storage hides every
     * pre-existing file (only subdirs and the calling app's own creations show
     * up). Granted via the system "Allow access to manage all files" toggle.
     * On API < 30 there's no scoped-storage gate to begin with, so we treat it
     * as always-granted there.
     */
    fun hasAllFilesAccess(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    fun allFilesAccessIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSpecific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData("package:${ctx.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (appSpecific.resolveActivity(ctx.packageManager) != null) {
                appSpecific
            } else {
                val global = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (global.resolveActivity(ctx.packageManager) != null) {
                    global
                } else {
                    Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        } else {
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Whether the app can schedule exact alarms (Android 12+). */
    fun hasExactAlarmAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = ctx.getSystemService(android.app.AlarmManager::class.java)
            am?.canScheduleExactAlarms() ?: false
        } else {
            true
        }

    /** Intent to open the exact alarm permission page for this app (Android 12+). */
    fun exactAlarmIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData("package:${ctx.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Whether the app has usage stats access granted. */
    fun hasUsageStatsAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        }
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }
    fun usageAccessIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun canRequestPackageInstalls(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun unknownAppSourcesIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData("package:${ctx.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Whether Android 14+ currently permits this app to use full-screen intents. */
    fun canUseFullScreenIntent(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ctx.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
        } else {
            true
        }

    /** Opens the Android 14+ per-app full-screen-intent access page. */
    fun fullScreenIntentSettingsIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData("package:${ctx.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /** Opens Android's VPN management page; VPN consent itself is still per app/service. */
    fun vpnSettingsIntent(): Intent =
        Intent(Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Reports whether this device exposes MediaProjection. This is a capability check only:
     * Android does not provide a durable "granted" state and requires user consent for each
     * projection session.
     */
    fun hasMediaProjectionCapability(ctx: Context): Boolean =
        ctx.getSystemService(MediaProjectionManager::class.java) != null

    /** Creates the per-session system consent request used by a future projection host. */
    fun mediaProjectionConsentIntent(ctx: Context): Intent? =
        ctx.getSystemService(MediaProjectionManager::class.java)?.createScreenCaptureIntent()
}
