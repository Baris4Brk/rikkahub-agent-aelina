package me.rerere.ai.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage

// 提供商实现
// 采用无状态设计，使用时除了需要传入需要的参数外，还需要传入provider setting作为参数
interface Provider<T : ProviderSetting> {
    suspend fun listModels(providerSetting: T): List<Model>

    /**
     * Returns a provider-owned hard capability only when it is locally/versionedly trusted.
     * Remote catalog metadata must remain in [Model.contextLength] and must not be promoted by
     * the default implementation. Providers whose capability depends on local runtime settings
     * may resolve it at request time.
     */
    suspend fun resolveTrustedContextWindowTokens(
        providerSetting: T,
        model: Model,
    ): Int? = model.trustedContextWindowTokens?.takeIf { it > 0 }

    suspend fun getBalance(providerSetting: T): String {
        return "TODO"
    }

    suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): MessageChunk

    suspend fun streamText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<MessageChunk>

    suspend fun generateEmbedding(
        providerSetting: T,
        params: EmbeddingGenerationParams,
    ): EmbeddingGenerationResult {
        error("Embedding generation is not supported")
    }

    suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem>

    suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> {
        error("Image edit is not supported")
    }
}

/**
 * Deliberate host capability marker for provider transports whose cancellation boundary has a
 * versioned fence. Merely returning a cancellable [Flow] is not sufficient: background work may
 * be pre-empted by foreground Chat, so late native/network callbacks must be unable to publish
 * output into a later request.
 *
 * Implementations must only advertise an ABI after that fence has deterministic cancellation and
 * late-callback tests. Background callers fail closed when this marker is absent or blank.
 */
interface FencedTextGenerationProvider {
    val cancellationFenceAbi: String
}

/**
 * Opaque, privacy-safe namespace for provider/local prefix caches.
 *
 * The app must hash conversation, assistant, memory-scope, and final injected-memory identities
 * before crossing this API. Raw UUIDs and memory IDs are deliberately rejected here and must never
 * appear in cache traces. [compilerRevision] invalidates a prefix when prompt rendering changes.
 */
class ProviderCacheIdentity private constructor(
    private val opaqueDigest: String,
    private val compilerRevision: String,
) {
    fun redactedPrefix(): String = opaqueDigest.take(12)

    override fun equals(other: Any?): Boolean =
        other is ProviderCacheIdentity &&
            opaqueDigest == other.opaqueDigest &&
            compilerRevision == other.compilerRevision

    override fun hashCode(): Int = 31 * opaqueDigest.hashCode() + compilerRevision.hashCode()

    override fun toString(): String =
        "ProviderCacheIdentity(${redactedPrefix()}..., revision=$compilerRevision)"

    companion object {
        private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
        private val REVISION = Regex("^[A-Za-z0-9._-]{1,64}$")

        fun fromOpaqueDigest(
            opaqueSha256: String,
            compilerRevision: String,
        ): ProviderCacheIdentity {
            require(SHA256_HEX.matches(opaqueSha256)) {
                "Provider cache identity must be an opaque SHA-256 hex digest, never a raw ID"
            }
            require(REVISION.matches(compilerRevision)) {
                "compilerRevision must be a non-empty, privacy-safe revision label"
            }
            return ProviderCacheIdentity(
                opaqueDigest = opaqueSha256.lowercase(),
                compilerRevision = compilerRevision,
            )
        }
    }
}

@Serializable
data class TextGenerationParams(
    val model: Model,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val tools: List<Tool> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
    /**
     * For non-chat system calls, omit a disabled reasoning field instead of sending a
     * provider-specific disabled value. The default preserves ordinary chat request shapes.
     */
    @Transient
    val omitReasoningConfigurationWhenOff: Boolean = false,
    /**
     * Retry hint for network-backed providers. A provider may use an isolated connection pool
     * for this attempt so a stream that was pinned to a degraded keep-alive connection is not
     * retried on that same transport. It is deliberately transient and never enters a request
     * body.
     */
    @Transient
    val freshConnection: Boolean = false,
    /** Local/provider cache namespace only. It is never serialized into an API request. */
    @Transient
    val providerCacheIdentity: ProviderCacheIdentity? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class ImageGenerationParams(
    val model: Model,
    val prompt: String,
    val numOfImages: Int = 1,
    val aspectRatio: ImageAspectRatio = ImageAspectRatio.SQUARE,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class ImageEditParams(
    val model: Model,
    val prompt: String,
    val images: List<String>,
    val numOfImages: Int = 1,
    val aspectRatio: ImageAspectRatio = ImageAspectRatio.SQUARE,
    val partialImages: Int = 2,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationParams(
    val model: Model,
    val input: List<String>,
    val dimensions: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBody: List<CustomBody> = emptyList(),
)

@Serializable
data class EmbeddingGenerationResult(
    val model: String,
    val embeddings: List<List<Float>>,
)

@Serializable
data class CustomHeader(
    val name: String,
    val value: String
)

@Serializable
data class CustomBody(
    val key: String,
    val value: JsonElement
)
