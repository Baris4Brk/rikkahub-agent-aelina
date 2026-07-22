package me.rerere.rikkahub.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.R

/** Keeps a local silent browser task observable and alive without owning model generation. */
class BrowserTaskService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.browser_task_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val pageId = intent.getStringExtra(EXTRA_PAGE_ID)
            if (pageId != null) {
                HeadlessBrowserSessionPool.releaseByPageId(pageId)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val pageId = intent?.getStringExtra(EXTRA_PAGE_ID)
        startForeground(NOTIFICATION_ID, notification(pageId))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(pageId: String?): Notification {
        val viewIntent = BrowserActivity.intent(this, pageId = pageId ?: MISSING_PAGE_ID)
        val viewPending = PendingIntent.getActivity(
            this, 1, viewIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPending = PendingIntent.getService(
            this,
            2,
            Intent(this, BrowserTaskService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_PAGE_ID, pageId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_telegram)
            .setContentTitle(getString(R.string.browser_task_notification_title))
            .setContentText(getString(R.string.browser_task_notification_text))
            .setOngoing(true)
            .setContentIntent(viewPending)
            .addAction(0, getString(R.string.browser_task_notification_view), viewPending)
            .addAction(0, getString(R.string.browser_task_notification_stop), stopPending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "browser_background"
        private const val NOTIFICATION_ID = 18201
        private const val MISSING_PAGE_ID = "ai-page-missing"
        const val ACTION_STOP = "me.rerere.rikkahub.browser.STOP"
        const val EXTRA_PAGE_ID = "me.rerere.rikkahub.browser.PAGE_ID"

        fun start(context: Context, conversationId: String) {
            val intent = Intent(context, BrowserTaskService::class.java)
                .putExtra(EXTRA_PAGE_ID, BrowserNotificationHandoff.pageIdFor(conversationId))
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
