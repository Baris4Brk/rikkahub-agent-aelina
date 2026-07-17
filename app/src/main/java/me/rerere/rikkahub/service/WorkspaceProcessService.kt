package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.WORKSPACE_PROCESS_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.ai.AgentSafetySettings
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceProcessManager
import me.rerere.workspace.WorkspaceProcessSummary
import org.koin.android.ext.android.inject

class WorkspaceProcessService : Service() {
    private val manager: WorkspaceProcessManager by inject()
    private val workspaceRepository: WorkspaceRepository by inject()
    private val safetySettings: AgentSafetySettings by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var restoreJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        isStopRequested = false
        startForegroundCompat(WorkspaceProcessSummary())
        observeManagerState()
        restoreDefinitions()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (manager.summary.value.desiredRunningCount == 0) {
                isStopRequested = true
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        isStopRequested = false
        startForegroundCompat(manager.summary.value)
        restoreDefinitions()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Managed processes intentionally outlive the task and chat UI.
    }

    override fun onDestroy() {
        isRunning = false
        isStopRequested = false
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun restoreDefinitions() {
        if (restoreJob?.isActive == true) return
        restoreJob = serviceScope.launch(Dispatchers.IO) {
            try {
                val validWorkspaces = workspaceRepository.getAll().associate { it.id to it.root }
                if (safetySettings.isEmergencyStop()) {
                    manager.reconcileEmergencyStop(validWorkspaces)
                } else {
                    manager.restoreDesiredProcesses(validWorkspaces)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to restore managed Workspace processes", error)
            }
        }
    }

    private fun observeManagerState() {
        if (stateJob?.isActive == true) return
        stateJob = serviceScope.launch {
            manager.summary.collectLatest { summary ->
                updateWakeLock(summary.keepAwakeCount > 0)
                updateNotification(summary)
            }
        }
    }

    private fun updateWakeLock(needed: Boolean) {
        if (needed) {
            val lock = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
                .also { wakeLock = it }
            if (!lock.isHeld) lock.acquire()
            isWakeLockHeld = lock.isHeld
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
        isWakeLockHeld = false
    }

    private fun startForegroundCompat(summary: WorkspaceProcessSummary) {
        val notification = buildNotification(summary)
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

    private fun updateNotification(summary: WorkspaceProcessSummary) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(summary))
    }

    private fun buildNotification(summary: WorkspaceProcessSummary) = NotificationCompat.Builder(
        this,
        WORKSPACE_PROCESS_NOTIFICATION_CHANNEL_ID,
    )
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle(getString(R.string.notification_channel_workspace_process))
        .setContentText(
            if (summary.desiredRunningCount == 0) {
                getString(R.string.notification_workspace_process_starting)
            } else {
                getString(
                    R.string.notification_workspace_process_running,
                    summary.activeCount,
                    summary.keepAwakeCount,
                )
            },
        )
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, RouteActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        private const val ACTION_START = "me.rerere.rikkahub.action.WORKSPACE_PROCESS_START"
        private const val ACTION_STOP = "me.rerere.rikkahub.action.WORKSPACE_PROCESS_STOP"
        private const val NOTIFICATION_ID = 2401
        private const val WAKE_LOCK_TAG = "RikkaHub:WorkspaceProcess"
        private const val TAG = "WorkspaceProcessService"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var isStopRequested: Boolean = false
            private set

        @Volatile
        var isWakeLockHeld: Boolean = false
            private set

        fun startIntent(context: Context): Intent = Intent(context, WorkspaceProcessService::class.java)
            .setAction(ACTION_START)

        fun stopIntent(context: Context): Intent = Intent(context, WorkspaceProcessService::class.java)
            .setAction(ACTION_STOP)
    }
}
