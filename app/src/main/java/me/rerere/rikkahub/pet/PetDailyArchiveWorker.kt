package me.rerere.rikkahub.pet

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** Best-effort 00:05 trigger; repository read/write paths remain the authoritative fallback. */
class PetDailyArchiveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val repository: PetDialogueRepository by inject()
    private val settingsStore: SettingsStore by inject()
    private val summaryScheduler: PetSummaryScheduler by inject()
    private val dao: me.rerere.rikkahub.data.db.dao.PetDialogueDao by inject()

    override suspend fun doWork(): Result = try {
        val settings = settingsStore.settingsFlow.first { !it.init }
        settings.assistants.filter { it.petEnabled && it.privilegedConversationId != null }
            .forEach { assistant ->
                repository.ensureActive(
                    assistantId = assistant.id.toString(),
                    privilegedConversationId = checkNotNull(assistant.privilegedConversationId).toString(),
                )
            }
        repository.purgeExpiredTrash()
        dao.getPendingSummaries(100).forEach { summaryScheduler.schedule(it.sessionId) }
        Result.success()
    } catch (_: Throwable) {
        Result.retry()
    } finally {
        PetDailyArchiveScheduler.schedule(applicationContext)
    }
}

object PetDailyArchiveScheduler {
    fun schedule(context: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        var next = now.toLocalDate().plusDays(1).atTime(0, 5).atZone(now.zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delayMs = Duration.between(now, next).toMillis().coerceAtLeast(1_000L)
        val request = OneTimeWorkRequestBuilder<PetDailyArchiveWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private const val UNIQUE_WORK_NAME = "pet-dialogue-daily-archive"
}
