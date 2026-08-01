package me.rerere.rikkahub.ui.pages.setting.scheduledjobs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.automation.AutomationControlFacade
import me.rerere.rikkahub.automation.ScheduledJobTriggerResult
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity

/**
 * Backs the Settings → Scheduled Jobs list + detail screens. Mirrors the responsibilities
 * the cron-job *tools* expose to the LLM (`list_jobs`, `pause_job`, `resume_job`,
 * `trigger_job_now`, `delete_job`, `get_job_history`) so the UI can drive every operation
 * the assistant can — without going through the LLM.
 *
 * State changes go through [ScheduledJobRepository] AND [CronJobScheduler] in the same
 * order the tools use, so the WorkManager schedule and the DB row stay in lock-step.
 */
class ScheduledJobsViewModel(
    private val automation: AutomationControlFacade,
) : ViewModel() {

    val jobs: StateFlow<List<ScheduledJobEntity>> =
        automation.observeScheduledJobs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            automation.setScheduledJobEnabled(id, enabled)
        }
    }

    fun delete(id: String, onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            automation.deleteScheduledJob(id)
            // Callers pass UI work (nav.popBackStack) — must not run on the IO dispatcher.
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    /** Manually fire — same path as the LLM's trigger_job_now tool. */
    suspend fun runNow(id: String): RunNowOutcome {
        return when (automation.triggerScheduledJob(id)) {
            ScheduledJobTriggerResult.ENQUEUED -> RunNowOutcome.Fired
            ScheduledJobTriggerResult.DISABLED -> RunNowOutcome.Disabled
            ScheduledJobTriggerResult.NOT_FOUND -> RunNowOutcome.NotFound
        }
    }

    suspend fun history(id: String, limit: Int = 20): List<ScheduledJobRunEntity> =
        automation.scheduledJobHistory(id, limit)

    suspend fun get(id: String): ScheduledJobEntity? = automation.getScheduledJob(id)

    enum class RunNowOutcome { Fired, NotFound, Disabled }
}
