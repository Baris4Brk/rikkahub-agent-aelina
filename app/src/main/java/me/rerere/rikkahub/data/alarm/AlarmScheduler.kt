package me.rerere.rikkahub.data.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.data.db.entity.AlarmEntity
import me.rerere.rikkahub.service.AlarmReceiver
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmScheduler(
    private val context: Context,
    private val repository: AlarmRepository,
) {
    companion object {
        const val ALARM_URI_SCHEME = "rikkahub"
        const val ALARM_URI_HOST = "alarm"
    }

    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            return am.canScheduleExactAlarms()
        }
        return true // Pre-Android 12 no restriction
    }

    fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    fun schedule(alarm: AlarmEntity) {
        val nextFireAt = alarm.nextFireAtMs ?: return
        if (!canScheduleExactAlarms()) return

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            data = android.net.Uri.parse("$ALARM_URI_SCHEME://$ALARM_URI_HOST/${alarm.id}")
            putExtra("alarm_id", alarm.id)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextFireAt, pi)
    }

    fun cancel(alarmId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            data = android.net.Uri.parse("$ALARM_URI_SCHEME://$ALARM_URI_HOST/$alarmId")
        }
        val pi = PendingIntent.getBroadcast(
            context,
            alarmId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.cancel(pi)
    }

    suspend fun rescheduleAll() {
        val alarms = repository.getEnabledWithNextFire()
        alarms.forEach { schedule(it) }
    }

    /**
     * Calculate the next fire time for an alarm.
     *
     * For "once" alarms: the [time] is an ISO-8601 date-time string.
     * For "weekly" alarms: calculate the next occurrence based on [hour], [minute], and [daysOfWeek].
     */
    fun calculateNextFireAt(alarm: AlarmEntity): Long? {
        val zone = try { ZoneId.of(alarm.timezone) } catch (e: Exception) { ZoneId.systemDefault() }
        val now = ZonedDateTime.now(zone)

        return when (alarm.scheduleType) {
            "once" -> {
                val timeStr = alarm.time ?: return null
                val fireTime = try {
                    ZonedDateTime.parse(timeStr).withNano(0)
                } catch (e: Exception) {
                    return null
                }
                if (fireTime <= now) null else fireTime.toInstant().toEpochMilli()
            }

            "weekly" -> {
                val h = alarm.hour ?: return null
                val m = alarm.minute ?: return null
                val days = alarm.daysOfWeek?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: return null
                if (days.isEmpty()) return null

                val today = now.dayOfWeek.value // 1=Mon, 7=Sun
                val sortedDays = days.sorted()

                for (day in sortedDays) {
                    val daysUntil = when {
                        day > today -> (day - today).toLong()
                        day < today -> (7L - today + day)
                        else -> 0L // today
                    }
                    var fireTime = now
                        .plusDays(daysUntil)
                        .withHour(h)
                        .withMinute(m)
                        .withSecond(0)
                        .withNano(0)
                    if (fireTime <= now && daysUntil == 0L) {
                        // Today's time already passed, try next week
                        fireTime = fireTime.plusDays(7)
                    }
                    if (fireTime > now) {
                        return fireTime.toInstant().toEpochMilli()
                    }
                }

                // Fallback: use the earliest weekday next week
                val firstDay = sortedDays.first()
                val daysToAdd = if (firstDay > today) {
                    (firstDay - today).toLong()
                } else {
                    (7L - today + firstDay)
                }
                now.plusDays(daysToAdd).withHour(h).withMinute(m).withSecond(0).withNano(0)
                    .toInstant().toEpochMilli()
            }

            else -> null
        }
    }
}
