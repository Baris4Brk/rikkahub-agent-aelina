package me.rerere.rikkahub.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ACCESSIBILITY_KEEP_ALIVE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.privilege.ShizukuBridgeManager
import org.koin.android.ext.android.inject

/**
 * Keeps the app process important and repairs only RikkaHub's own accessibility component.
 * It never removes or rewrites another app's component and never targets a secondary user.
 */
class AccessibilityKeepAliveService : Service() {
    private val shizukuBridgeManager: ShizukuBridgeManager by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var directRestorer: AccessibilitySecureSettingsRestorer
    private var monitorJob: Job? = null
    private var disconnectedSinceMs = 0L
    private var lastRebindAttemptMs = Long.MIN_VALUE
    private var lastFailureCode: String? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        directRestorer = AccessibilitySecureSettingsRestorer(this)
        startForegroundCompat(R.string.accessibility_keep_alive_starting)
        startMonitorIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AccessibilityKeepAliveState.setEnabled(this, false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!AccessibilityKeepAliveState.isEnabled(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        startMonitorIfNeeded()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Persistence is intentional and independent of the task/UI lifecycle.
    }

    override fun onDestroy() {
        isRunning = false
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startMonitorIfNeeded() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    checkAndRepair()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.e(TAG, "Accessibility keep-alive check failed", error)
                    updateNotification(R.string.accessibility_keep_alive_privilege_required)
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun checkAndRepair() {
        if (!AccessibilityKeepAliveState.isEnabled(this)) {
            stopSelf()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val enabledInSettings = directRestorer.isEnabledInSettings()
        val bound = RikkaAccessibilityService.instance != null

        if (enabledInSettings && bound) {
            disconnectedSinceMs = 0L
            lastFailureCode = null
            updateNotification(R.string.accessibility_keep_alive_active)
            return
        }

        if (!enabledInSettings) {
            disconnectedSinceMs = now
            updateNotification(R.string.accessibility_keep_alive_recovering)
            reportRecovery(recover(forceRebind = false))
            return
        }

        if (disconnectedSinceMs == 0L) disconnectedSinceMs = now
        val disconnectedFor = now - disconnectedSinceMs
        val rebindDue = disconnectedFor >= REBIND_GRACE_MS &&
            elapsedSince(lastRebindAttemptMs, now) >= REBIND_RETRY_MS
        if (!rebindDue) {
            updateNotification(R.string.accessibility_keep_alive_waiting_for_bind)
            return
        }

        lastRebindAttemptMs = now
        updateNotification(R.string.accessibility_keep_alive_recovering)
        reportRecovery(recover(forceRebind = true))
    }

    private suspend fun recover(forceRebind: Boolean): AccessibilityRecoveryResult {
        val direct = directRestorer.restore(forceRebind)
        if (direct.ok) return direct

        val status = runCatching { shizukuBridgeManager.status() }.getOrNull()
        if (status?.binderAvailable != true ||
            !status.permissionGranted ||
            !status.userServiceAvailable
        ) {
            return direct
        }
        val privileged = try {
            shizukuBridgeManager.ensureOwnAccessibilityServiceEnabled(forceRebind)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return AccessibilityRecoveryResult(
                ok = false,
                code = "SHIZUKU_RECOVERY_FAILED",
                message = error.message ?: "Shizuku accessibility recovery failed.",
            )
        }
        return AccessibilityRecoveryResult(
            ok = privileged.ok,
            code = privileged.code,
            message = privileged.message,
        )
    }

    private fun reportRecovery(result: AccessibilityRecoveryResult) {
        if (result.ok) {
            lastFailureCode = null
            updateNotification(R.string.accessibility_keep_alive_recovering)
            Log.i(TAG, "Accessibility recovery requested: ${result.code}")
            return
        }
        updateNotification(R.string.accessibility_keep_alive_privilege_required)
        if (lastFailureCode != result.code) {
            lastFailureCode = result.code
            Log.w(TAG, "Accessibility recovery unavailable: ${result.code}: ${result.message}")
        }
    }

    private fun startForegroundCompat(@StringRes contentText: Int) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(@StringRes contentText: Int) {
        runCatching {
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(contentText))
        }.onFailure { error ->
            Log.w(TAG, "Unable to update accessibility keep-alive notification", error)
        }
    }

    private fun buildNotification(@StringRes contentText: Int) = NotificationCompat.Builder(
        this,
        ACCESSIBILITY_KEEP_ALIVE_NOTIFICATION_CHANNEL_ID,
    )
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle(getString(R.string.accessibility_keep_alive_title))
        .setContentText(getString(contentText))
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN_APP,
                Intent(this, RouteActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            R.drawable.small_icon,
            getString(R.string.accessibility_keep_alive_open_settings),
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN_ACCESSIBILITY_SETTINGS,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .addAction(
            R.drawable.small_icon,
            getString(R.string.accessibility_keep_alive_stop),
            PendingIntent.getService(
                this,
                REQUEST_STOP,
                Intent(this, AccessibilityKeepAliveService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        private const val ACTION_START =
            "me.rerere.rikkahub.action.ACCESSIBILITY_KEEP_ALIVE_START"
        private const val ACTION_STOP =
            "me.rerere.rikkahub.action.ACCESSIBILITY_KEEP_ALIVE_STOP"
        private const val NOTIFICATION_ID = 2402
        private const val REQUEST_OPEN_APP = 2402
        private const val REQUEST_OPEN_ACCESSIBILITY_SETTINGS = 2403
        private const val REQUEST_STOP = 2404
        private const val CHECK_INTERVAL_MS = 15_000L
        private const val REBIND_GRACE_MS = 30_000L
        private const val REBIND_RETRY_MS = 120_000L
        private const val TAG = "AccessibilityKeepAlive"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            if (!AccessibilityKeepAliveState.isEnabled(context)) return
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AccessibilityKeepAliveService::class.java)
                        .setAction(ACTION_START),
                )
            }.onFailure { error ->
                Log.w(TAG, "Unable to start accessibility keep-alive service", error)
            }
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            AccessibilityKeepAliveState.setEnabled(context, enabled)
            if (enabled) {
                start(context)
            } else {
                context.stopService(Intent(context, AccessibilityKeepAliveService::class.java))
            }
        }

        private fun elapsedSince(previous: Long, now: Long): Long =
            if (previous == Long.MIN_VALUE) Long.MAX_VALUE else (now - previous).coerceAtLeast(0L)
    }
}
