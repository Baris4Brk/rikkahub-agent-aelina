package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCompatibilityResolverTest {
    @Test
    fun `published supported parameters control optional request fields`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://api.x.ai/v1")
        val withSize = imageModel(
            modelId = "grok-imagine-1",
            supportedParameters = listOf("prompt", "size"),
        )
        val withoutSize = withSize.copy(supportedParameters = listOf("prompt"))

        assertFalse(ModelCompatibilityResolver.resolve(provider, withSize).omitImageSize)
        assertTrue(ModelCompatibilityResolver.resolve(provider, withoutSize).omitImageSize)
        assertFalse(ModelCompatibilityResolver.resolve(provider, withoutSize).allowTemperature)
    }

    @Test
    fun `moonshot k2 6 retains thinking and rejects temperature`() {
        val compatibility = ModelCompatibilityResolver.resolve(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.moonshot.cn/v1"),
            model = Model(
                modelId = "kimi-k2.6",
                abilities = listOf(ModelAbility.REASONING),
            ),
        )

        assertTrue(compatibility.retainThinkingHistory)
        assertFalse(compatibility.allowTemperature)
    }

    @Test
    fun `kimi k3 aliases reject temperature on compatible proxies`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://compatible.example/v1")

        assertFalse(
            ModelCompatibilityResolver.resolve(
                provider,
                Model(modelId = "moonshotai/kimi-k3-preview"),
            ).allowTemperature,
        )
    }

    @Test
    fun `grok image fallback omits size for custom provider`() {
        val compatibility = ModelCompatibilityResolver.resolve(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://compatible.example/v1"),
            model = imageModel(modelId = "grok-2-image-1212"),
        )

        assertTrue(compatibility.omitImageSize)
    }

    @Test
    fun `chat completions emits retained thinking configuration`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val params = TextGenerationParams(
            model = Model(
                modelId = "kimi-k2.6",
                abilities = listOf(ModelAbility.REASONING),
            ),
            reasoningLevel = ReasoningLevel.HIGH,
            temperature = 0.8f,
        )
        val request = invokeChatRequest(
            api = api,
            provider = ProviderSetting.OpenAI(baseUrl = "https://api.moonshot.cn/v1"),
            params = params,
        )

        assertFalse(request.containsKey("temperature"))
        assertEquals("enabled", request["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("all", request["thinking"]?.jsonObject?.get("keep")?.jsonPrimitive?.content)
    }

    @Test
    fun `responses and chat completions share temperature decision`() {
        val provider = ProviderSetting.OpenAI(baseUrl = "https://compatible.example/v1")
        val params = TextGenerationParams(
            model = Model(
                modelId = "custom-no-temperature",
                supportedParameters = listOf("top_p", "max_tokens"),
            ),
            temperature = 0.7f,
        )
        val chat = invokeChatRequest(
            api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default()),
            provider = provider,
            params = params,
        )
        val responses = ResponseAPI(OkHttpClient()).buildRequestBody(
            providerSetting = provider,
            messages = listOf(UIMessage.user("hello")),
            params = params,
            stream = false,
        )

        assertFalse(chat.containsKey("temperature"))
        assertFalse(responses.containsKey("temperature"))
    }

    private fun imageModel(
        modelId: String,
        supportedParameters: List<String> = emptyList(),
    ) = Model(
        modelId = modelId,
        type = ModelType.IMAGE,
        supportedParameters = supportedParameters,
    )

    private fun invokeChatRequest(
        api: ChatCompletionsAPI,
        provider: ProviderSetting.OpenAI,
        params: TextGenerationParams,
    ): JsonObject {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildChatCompletionRequest",
            List::class.java,
            TextGenerationParams::class.java,
            ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType!!,
        )
        method.isAccessible = true
        return method.invoke(
            api,
            listOf(UIMessage.user("hello")),
            params,
            provider,
            false,
        ) as JsonObject
    }
}
