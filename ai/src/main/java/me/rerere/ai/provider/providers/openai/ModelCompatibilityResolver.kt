package me.rerere.ai.provider.providers.openai

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.registry.ModelRegistry
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Request-only compatibility decisions for OpenAI-compatible providers.
 *
 * Nothing resolved here is persisted back to [Model] or [ProviderSetting]. Catalog capability
 * metadata wins when it is available; host and model-id fallbacks keep user-defined providers
 * working when their `/models` endpoint does not publish supported parameters.
 */
internal data class ModelCompatibility(
    val allowTemperature: Boolean,
    val retainThinkingHistory: Boolean,
    val omitImageSize: Boolean,
)

internal object ModelCompatibilityResolver {
    fun resolve(
        providerSetting: ProviderSetting,
        model: Model,
    ): ModelCompatibility {
        val host = when (providerSetting) {
            is ProviderSetting.OpenAI -> providerSetting.baseUrl.toHttpUrlOrNull()?.host.orEmpty()
            else -> ""
        }.lowercase()
        val modelId = model.modelId.lowercase()
        val supported = model.supportedParameters
            .asSequence()
            .map(::normalizeParameter)
            .filter(String::isNotEmpty)
            .toSet()
        val hasPublishedParameters = supported.isNotEmpty()

        val knownTemperatureIncompatible =
            ModelRegistry.OPENAI_O_MODELS.match(model.modelId) ||
                ModelRegistry.GPT_5.match(model.modelId) ||
                isKimiTemperatureIncompatible(modelId)
        val allowTemperature = when {
            knownTemperatureIncompatible -> false
            hasPublishedParameters -> "temperature" in supported
            else -> true
        }

        val isMoonshotProvider = host == "api.moonshot.cn" || host.endsWith(".moonshot.cn")
        val retainThinkingHistory =
            model.abilities.contains(ModelAbility.REASONING) &&
                isMoonshotProvider &&
                isKimiK26(modelId)

        val imageCapable = model.type == ModelType.IMAGE ||
            model.outputModalities.any { it.name == "IMAGE" }
        val isXaiProvider = host == "api.x.ai" || host.endsWith(".x.ai")
        val omitImageSize = imageCapable && when {
            hasPublishedParameters -> "size" !in supported
            isXaiProvider -> true
            else -> modelId.contains("grok")
        }

        return ModelCompatibility(
            allowTemperature = allowTemperature,
            retainThinkingHistory = retainThinkingHistory,
            omitImageSize = omitImageSize,
        )
    }

    private fun normalizeParameter(value: String): String = value
        .trim()
        .lowercase()
        .removePrefix("parameters.")
        .removePrefix("request.")

    private fun isKimiTemperatureIncompatible(modelId: String): Boolean {
        val normalized = modelId.substringAfterLast('/')
        return normalized.startsWith("kimi-k2.5") ||
            normalized.startsWith("kimi-k2.6") ||
            normalized == "kimi-k3" ||
            normalized.startsWith("kimi-k3-")
    }

    private fun isKimiK26(modelId: String): Boolean =
        modelId.substringAfterLast('/').startsWith("kimi-k2.6")
}
