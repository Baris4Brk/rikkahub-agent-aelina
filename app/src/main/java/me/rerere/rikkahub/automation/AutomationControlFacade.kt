package me.rerere.rikkahub.automation

import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.alarm.AlarmRepository
import me.rerere.rikkahub.data.alarm.AlarmScheduler
import me.rerere.rikkahub.data.db.entity.AlarmEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.service.CronJobScheduler

/**
 * Shared write boundary for the Settings UI and Owner automation controls.
 *
 * Repository state is always changed before its Android scheduler projection. Callers can
 * therefore verify or repair a partially completed operation from the durable row after a
 * process restart instead of assuming that WorkManager/AlarmManager accepted the request.
 */
class AutomationControlFacade(
    private val scheduledJobs: ScheduledJobRepository,
    private val scheduledJobRuns: ScheduledJobRunRepository,
    private val cronScheduler: CronJobScheduler,
    private val alarms: AlarmRepository,
    private val alarmScheduler: AlarmScheduler,
) {
    fun observeScheduledJobs(): Flow<List<ScheduledJobEntity>> = scheduledJobs.observeAll()
    suspend fun listScheduledJobs(): List<ScheduledJobEntity> = scheduledJobs.getAll()
    suspend fun getScheduledJob(id: String): ScheduledJobEntity? = scheduledJobs.getById(id)
    suspend fun scheduledJobHistory(id: String, limit: Int): List<ScheduledJobRunEntity> =
        scheduledJobRuns.getRecent(id, limit.coerceIn(1, 100))

    suspend fun saveScheduledJob(job: ScheduledJobEntity): ScheduledJobEntity {
        scheduledJobs.upsert(job)
        if (job.enabled) cronScheduler.schedule(job) else cronScheduler.cancel(job.id)
        return scheduledJobs.getById(job.id) ?: job
    }

    suspend fun setScheduledJobEnabled(id: String, enabled: Boolean): ScheduledJobEntity? {
        val current = scheduledJobs.getById(id) ?: return null
        return saveScheduledJob(current.copy(enabled = enabled))
    }

    suspend fun deleteScheduledJob(id: String): Boolean {
        if (scheduledJobs.getById(id) == null) return false
        cronScheduler.cancel(id)
        scheduledJobRuns.deleteAllForJob(id)
        scheduledJobs.deleteById(id)
        return scheduledJobs.getById(id) == null
    }

    suspend fun triggerScheduledJob(id: String): ScheduledJobTriggerResult {
        val job = scheduledJobs.getById(id) ?: return ScheduledJobTriggerResult.NOT_FOUND
        if (!job.enabled) return ScheduledJobTriggerResult.DISABLED
        cronScheduler.triggerNow(id)
        return ScheduledJobTriggerResult.ENQUEUED
    }

    suspend fun listAlarms(): List<AlarmEntity> = alarms.getAllOnce()
    suspend fun getAlarm(id: String): AlarmEntity? = alarms.getById(id)
    fun canScheduleExactAlarms(): Boolean = alarmScheduler.canScheduleExactAlarms()
    fun openExactAlarmSettings() = alarmScheduler.openExactAlarmSettings()

    suspend fun saveAlarm(alarm: AlarmEntity): AlarmEntity {
        val nextFireAt = if (alarm.enabled) alarmScheduler.calculateNextFireAt(alarm) else null
        val persisted = alarm.copy(nextFireAtMs = nextFireAt, updatedAtMs = System.currentTimeMillis())
        alarms.upsert(persisted)
        alarmScheduler.cancel(persisted.id)
        if (persisted.enabled && nextFireAt != null && canScheduleExactAlarms()) {
            alarmScheduler.schedule(persisted)
        }
        return alarms.getById(persisted.id) ?: persisted
    }

    suspend fun setAlarmEnabled(id: String, enabled: Boolean): AlarmEntity? {
        val current = alarms.getById(id) ?: return null
        return saveAlarm(current.copy(enabled = enabled))
    }

    suspend fun deleteAlarm(id: String): Boolean {
        if (alarms.getById(id) == null) return false
        alarmScheduler.cancel(id)
        alarms.deleteById(id)
        return alarms.getById(id) == null
    }
}

enum class ScheduledJobTriggerResult {
    ENQUEUED,
    DISABLED,
    NOT_FOUND,
}
