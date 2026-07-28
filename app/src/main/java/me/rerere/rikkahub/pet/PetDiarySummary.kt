package me.rerere.rikkahub.pet

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.dao.PetDialogueDao
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

fun interface PetSummaryScheduler {
    fun schedule(sessionId: String)
}

class AndroidPetSummaryScheduler(private val context: Context) : PetSummaryScheduler {
    override fun schedule(sessionId: String) {
        val request = OneTimeWorkRequestBuilder<PetDiarySummaryWorker>()
            .setInputData(Data.Builder().putString(KEY_SESSION_ID, sessionId).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "pet-diary-summary-$sessionId",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    companion object { const val KEY_SESSION_ID = "session_id" }
}

@Serializable
data class PetDiaryGeneratedSummary(val title: String = "", val summary: String = "")

class PetDiarySummarizer(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun summarize(assistantId: String, transcript: String): PetDiaryGeneratedSummary {
        val settings = settingsStore.settingsFlow.first { !it.init }
        val assistant = settings.assistants.first { it.id.toString() == assistantId }
        val model = settings.findModelById(settings.fastModelId)
            ?: settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: error("pet_summary_model_missing")
        val providerSetting = model.findProvider(settings.providers) ?: error("pet_summary_provider_missing")
        val provider = providerManager.getProviderByType(providerSetting)
        val response = withTimeout(60_000L) {
            provider.generateText(
                providerSetting,
                listOf(
                    UIMessage.system("把桌宠短对白整理为日记元数据。只输出 JSON：{\"title\":\"不超过20字\",\"summary\":\"不超过200字\"}。不添加未发生的事实。"),
                    UIMessage.user(transcript.take(30_000)),
                ),
                TextGenerationParams(
                    model = model,
                    temperature = 0.2f,
                    maxTokens = 512,
                    tools = emptyList(),
                    reasoningLevel = ReasoningLevel.OFF,
                    omitReasoningConfigurationWhenOff = true,
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
        }
        val raw = response.choices.firstOrNull()?.message?.toText().orEmpty().trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val parsed = json.decodeFromString<PetDiaryGeneratedSummary>(raw)
        return parsed.copy(
            title = PetBubbleSanitizer.sanitizeDraft(parsed.title).take(160),
            summary = PetBubbleSanitizer.sanitizeDraft(parsed.summary).take(4_000),
        )
    }
}

class PetDiarySummaryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {
    private val dao: PetDialogueDao by inject()
    private val repository: PetDialogueRepository by inject()
    private val summarizer: PetDiarySummarizer by inject()

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(AndroidPetSummaryScheduler.KEY_SESSION_ID)
            ?: return Result.failure()
        val session = dao.getSession(sessionId) ?: return Result.success()
        if (session.status != PetDialogueSessionStatus.ARCHIVED.name ||
            session.summaryState == PetSummaryState.READY.name
        ) return Result.success()
        val transcript = dao.getTurns(sessionId).joinToString("\n") { turn ->
            "用户：${turn.userText ?: turn.interactionJson.orEmpty()}\n桌宠：${turn.assistantText.orEmpty()}"
        }
        return runCatching { summarizer.summarize(session.assistantId, transcript) }
            .fold(
                onSuccess = { generated ->
                    repository.updateMetadata(
                        sessionId,
                        session.stateVersion,
                        generated.title,
                        generated.summary,
                        session.notes,
                        session.tagsJson,
                        PetSummaryState.READY,
                        "pet-summary-worker",
                    )
                    Result.success()
                },
                onFailure = {
                    repository.updateMetadata(
                        sessionId,
                        session.stateVersion,
                        session.title,
                        session.summary,
                        session.notes,
                        session.tagsJson,
                        PetSummaryState.FAILED,
                        "pet-summary-worker",
                    )
                    Result.success()
                },
            )
    }
}
