package me.rerere.rikkahub.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.alarm.AlarmRepository
import me.rerere.rikkahub.data.alarm.AlarmScheduler
import me.rerere.rikkahub.data.db.entity.AlarmEntity
import org.koin.java.KoinJavaComponent

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val CHANNEL_ID = "alarm"
        const val NOTIFICATION_ID_BASE = 10000
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra("alarm_id") ?: return
        Log.i(TAG, "Alarm triggered: $alarmId")

        val pendingResult = goAsync()
        try {
            runBlocking {
                val repository: AlarmRepository = KoinJavaComponent.get(AlarmRepository::class.java)
                val scheduler: AlarmScheduler = KoinJavaComponent.get(AlarmScheduler::class.java)
                val alarm = repository.getById(alarmId)

                if (alarm != null && alarm.enabled) {
                    val now = System.currentTimeMillis()

                    if (alarm.scheduleType == "once") {
                        repository.markFired(alarmId, now, null)
                        repository.setEnabled(alarmId, false)
                    } else {
                        // Weekly: recalculate next fire
                        val updatedAlarm = alarm.copy(
                            lastFiredAtMs = now,
                            updatedAtMs = now,
                        )
                        val nextFire = scheduler.calculateNextFireAt(updatedAlarm)
                        repository.markFired(alarmId, now, nextFire)
                        if (nextFire != null) {
                            scheduler.schedule(updatedAlarm.copy(nextFireAtMs = nextFire))
                        }
                    }

                    sendAlarmNotification(context, alarm)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process alarm $alarmId", e)
        } finally {
            pendingResult.finish()
        }
    }

    private fun sendAlarmNotification(context: Context, alarm: AlarmEntity) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(alarm.label)
            .setContentText(alarm.note ?: context.getString(me.rerere.rikkahub.R.string.alarm_notification_default_text))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setVibrate(if (alarm.vibrate) longArrayOf(0, 500, 200, 500) else null)
            .build()

        notificationManager.notify(NOTIFICATION_ID_BASE + alarm.id.hashCode(), notification)
    }
}
