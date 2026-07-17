package me.rerere.rikkahub.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.alarm.AlarmScheduler
import org.koin.java.KoinJavaComponent

class AlarmRescheduleReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AlarmReschedule"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Reschedule triggered: ${intent.action}")
        val pendingResult = goAsync()
        try {
            runBlocking {
                val scheduler: AlarmScheduler = KoinJavaComponent.get(AlarmScheduler::class.java)
                scheduler.rescheduleAll()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reschedule alarms", e)
        } finally {
            pendingResult.finish()
        }
    }
}
