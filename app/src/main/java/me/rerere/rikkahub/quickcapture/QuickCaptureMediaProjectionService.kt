package me.rerere.rikkahub.quickcapture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

/**
 * Owns the Android MediaProjection grant and its one VirtualDisplay. Bitmaps remain in the
 * process-local [QuickCaptureProjectionSession]; no image crosses Binder or is saved to Photos.
 */
class QuickCaptureMediaProjectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null
    private var projectionInstalled = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat(getString(R.string.quick_capture_projection_notification_starting))
        stateJob = scope.launch {
            QuickCaptureProjectionSession.state.collectLatest { state ->
                when (state) {
                    QuickCaptureProjectionState.NeedsConsent -> if (projectionInstalled) stopSelf()
                    is QuickCaptureProjectionState.Failed -> if (projectionInstalled) stopSelf()
                    is QuickCaptureProjectionState.Ready -> updateNotification(
                        getString(
                            R.string.quick_capture_projection_notification_ready,
                            state.width,
                            state.height,
                        ),
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    QuickCaptureProjectionSession.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
            }
            ACTION_INSTALL -> installProjection(intent, startId)
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stateJob?.cancel()
        // MediaProjection.onStop may already have released this session. The lock is short-lived
        // and makes Service destruction deterministic even when the user stops projection from
        // Android's privacy chip or locks the device.
        runBlocking(Dispatchers.Default) { QuickCaptureProjectionSession.stop() }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun installProjection(intent: Intent, startId: Int) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            stopSelfResult(startId)
            return
        }
        // Android 14+ requires the mediaProjection foreground type before projection creation.
        startForegroundCompat(getString(R.string.quick_capture_projection_notification_starting))
        scope.launch(Dispatchers.Default) {
            val projection = runCatching {
                getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(resultCode, resultData)
            }.getOrNull()
            if (projection == null) {
                stopSelfResult(startId)
                return@launch
            }
            val installed = QuickCaptureProjectionSession.install(applicationContext, projection)
            if (installed.isSuccess) {
                projectionInstalled = true
            } else {
                stopSelfResult(startId)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.quick_capture_projection_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            },
        )
    }

    private fun startForegroundCompat(content: String) {
        val notification = notification(content)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(content))
    }

    private fun notification(content: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle(getString(R.string.quick_capture_projection_notification_title))
        .setContentText(content)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, RouteActivity::class.java).apply {
                    putExtra(RouteActivity.EXTRA_OPEN_QUICK_CAPTURE_SETTINGS, true)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        const val ACTION_INSTALL = "me.rerere.rikkahub.action.QUICK_CAPTURE_INSTALL_PROJECTION"
        const val ACTION_STOP = "me.rerere.rikkahub.action.QUICK_CAPTURE_STOP_PROJECTION"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "quick_capture_projection"
        private const val NOTIFICATION_ID = 2411

        fun capturePermissionIntent(context: Context): Intent = context
            .getSystemService(MediaProjectionManager::class.java)
            .createScreenCaptureIntent()

        fun installIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, QuickCaptureMediaProjectionService::class.java).apply {
                action = ACTION_INSTALL
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, QuickCaptureMediaProjectionService::class.java).setAction(ACTION_STOP)

        fun install(context: Context, resultCode: Int, resultData: Intent) {
            ContextCompat.startForegroundService(context, installIntent(context, resultCode, resultData))
        }
    }
}
