package me.rerere.rikkahub.memory

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider

class ProviderMemoryExtractor(
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
) : MemoryExtractor {
    override suspend fun extract(request: MemoryExtractionRequest): MemoryExtractorResult {
        return try {
            val settings = settingsStore.settingsFlow.first { settings -> !settings.init }
            val model = settings.resolveMemoryExtractionModel()
                ?: return MemoryExtractorResult.Failure(
                code = "memory_extraction_model_missing",
                retryPolicy = MemoryFailureRetryPolicy.MANUAL_ONLY,
            )
            val providerSetting = model.findProvider(settings.providers)
                ?: return MemoryExtractorResult.Failure(
                    code = "memory_extraction_provider_missing",
                    retryPolicy = MemoryFailureRetryPolicy.MANUAL_ONLY,
                )
            if (!providerSetting.enabled) {
                return MemoryExtractorResult.Failure(
                    code = "memory_extraction_provider_disabled",
                    retryPolicy = MemoryFailureRetryPolicy.MANUAL_ONLY,
                )
            }
            val provider = providerManager.getProviderByType(providerSetting)
            val result = withTimeout(MEMORY_EXTRACTION_TIMEOUT_MS) {
                provider.generateText(
                    providerSetting = providerSetting,
                    messages = listOf(
                        UIMessage.system(MEMORY_EXTRACTION_SYSTEM_PROMPT.trimIndent()),
                        UIMessage.user(memoryExtractionPayload(request)),
                    ),
                    params = TextGenerationParams(
                        model = model,
                        temperature = 0.1f,
                        // A compact extraction response is JSON only. A bounded output avoids
                        // provider-side failures caused by a reasoning model reserving an
                        // unnecessarily large completion for a short structured task.
                        maxTokens = 2_048,
                        tools = emptyList(),
                        reasoningLevel = memoryExtractionReasoningLevel(model),
                        omitReasoningConfigurationWhenOff = true,
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    ),
                )
            }
            val text = result.choices.firstOrNull()?.message?.toText().orEmpty()
            // A completed 200 response with no visible answer is common when a batch contains
            // only greetings, routine tool results, or assistant chatter. Treat it as the
            // protocol's explicit empty proposal set; transport errors and malformed non-empty
            // responses still fail through the normal parser/retry path.
            MemoryExtractorResult.Success(normalizeMemoryExtractionText(text))
        } catch (cancelled: CancellationException) {
            if (cancelled is TimeoutCancellationException) {
                MemoryExtractorResult.Failure("memory_extraction_timeout")
            } else {
                throw cancelled
            }
        } catch (error: Throwable) {
            MemoryExtractorResult.Failure(
                code = "memory_extraction_provider_error",
                message = error.message,
            )
        }
    }
}

internal fun normalizeMemoryExtractionText(text: String): String =
    text.trim().ifBlank { "{\"version\":2,\"proposals\":[],\"relations\":[]}" }

/** Reasoning-only defaults can consume the whole output budget and leave no JSON answer. */
internal fun memoryExtractionReasoningLevel(model: Model): ReasoningLevel =
    if (ModelAbility.REASONING in model.abilities) ReasoningLevel.LOW else ReasoningLevel.OFF

/** A configured extraction model never silently falls back; only a null setting selects Fast. */
internal fun Settings.resolveMemoryExtractionModel() = memoryExtractionModelId.let { selectedId ->
    if (selectedId == null) findModelById(fastModelId) else providers.findModelById(selectedId)
}

private const val MEMORY_EXTRACTION_TIMEOUT_MS = 2L * 60_000L
