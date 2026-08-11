package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

const val DEFAULT_USER_CONTEXT_WINDOW_TOKENS = 1_000_000

@Serializable
data class Model(
    val modelId: String = "",
    val displayName: String = "",
    val id: Uuid = Uuid.random(),
    val type: ModelType = ModelType.CHAT,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val inputModalities: List<Modality> = listOf(Modality.TEXT),
    val outputModalities: List<Modality> = listOf(Modality.TEXT),
    val abilities: List<ModelAbility> = emptyList(),
    val tools: Set<BuiltInTools> = emptySet(),
    val providerOverwrite: ProviderSetting? = null,
    // Optional capability/pricing metadata, populated from OpenRouter's /models endpoint.
    val contextLength: Int? = null,
    // User-owned policy setting. Unlike [contextLength], provider catalog refreshes never define it.
    val userContextWindowTokens: Int = DEFAULT_USER_CONTEXT_WINDOW_TOKENS,
    val supportedParameters: List<String> = emptyList(),
    val pricePromptPerToken: Double? = null,
    val priceCompletionPerToken: Double? = null,
    /**
     * Versioned/locally verified hard capability. Provider catalog values belong in
     * [contextLength], not here. A null value means enforcement uses user policy plus the app cap.
     */
    val trustedContextWindowTokens: Int? = null,
)

@Serializable
enum class ModelType {
    CHAT,
    IMAGE,
    EMBEDDING,
}

@Serializable
enum class Modality {
    TEXT,
    IMAGE,
}

@Serializable
enum class ModelAbility {
    TOOL,
    REASONING,
}

// 模型(提供商)提供的内置工具选项
@Serializable
sealed class BuiltInTools {
    // https://ai.google.dev/gemini-api/docs/google-search?hl=zh-cn
    @Serializable
    @SerialName("search")
    data object Search : BuiltInTools()

    // https://ai.google.dev/gemini-api/docs/url-context?hl=zh-cn
    @Serializable
    @SerialName("url_context")
    data object UrlContext : BuiltInTools()

    @Serializable
    @SerialName("image_generation")
    data object ImageGeneration : BuiltInTools()
}


