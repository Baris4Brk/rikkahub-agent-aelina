package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatCompletionsAPIReasoningTest {
    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    @Test
    fun `memory extraction omits generic reasoning effort when reasoning is off`() {
        val request = buildRequest(
            provider = ProviderSetting.OpenAI(baseUrl = "https://compatible.example/v1"),
            params = TextGenerationParams(
                model = reasoningModel(),
                reasoningLevel = ReasoningLevel.OFF,
                omitReasoningConfigurationWhenOff = true,
            ),
        )

        assertFalse(request.containsKey("reasoning_effort"))
    }

    @Test
    fun `ordinary generic chat retains existing low reasoning effort compatibility`() {
        val request = buildRequest(
            provider = ProviderSetting.OpenAI(baseUrl = "https://compatible.example/v1"),
            params = TextGenerationParams(
                model = reasoningModel(),
                reasoningLevel = ReasoningLevel.OFF,
            ),
        )

        assertEquals("low", request["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `memory extraction omits disabled reasoning effort for OpenCode`() {
        val request = buildRequest(
            provider = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/v1"),
            params = TextGenerationParams(
                model = reasoningModel(),
                reasoningLevel = ReasoningLevel.OFF,
                omitReasoningConfigurationWhenOff = true,
            ),
        )

        assertFalse(request.containsKey("reasoning_effort"))
    }

    @Test
    fun `ordinary OpenCode chat retains disabled reasoning effort`() {
        val request = buildRequest(
            provider = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/v1"),
            params = TextGenerationParams(
                model = reasoningModel(),
                reasoningLevel = ReasoningLevel.OFF,
            ),
        )

        assertTrue(request.containsKey("reasoning_effort"))
        assertEquals("none", request["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `OpenCode DeepSeek V4 maps xhigh to native max effort`() {
        val request = buildRequest(
            provider = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/v1"),
            params = TextGenerationParams(
                model = Model(
                    modelId = "deepseek-v4-flash",
                    abilities = listOf(ModelAbility.REASONING),
                ),
                reasoningLevel = ReasoningLevel.XHIGH,
            ),
        )

        assertEquals("max", request["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `OpenCode non DeepSeek models retain xhigh effort`() {
        val request = buildRequest(
            provider = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/v1"),
            params = TextGenerationParams(
                model = reasoningModel(),
                reasoningLevel = ReasoningLevel.XHIGH,
            ),
        )

        assertEquals("xhigh", request["reasoning_effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `memory extraction prevents custom body from restoring disabled reasoning effort`() {
        val request = buildRequest(
            provider = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/v1"),
            params = TextGenerationParams(
                model = reasoningModel(),
                reasoningLevel = ReasoningLevel.OFF,
                omitReasoningConfigurationWhenOff = true,
                customBody = listOf(CustomBody("reasoning_effort", JsonPrimitive("none"))),
            ),
        )

        assertFalse(request.containsKey("reasoning_effort"))
    }

    @Test
    fun `memory extraction prevents custom body from restoring reasoning object`() {
        val request = buildRequest(
            provider = ProviderSetting.OpenAI(baseUrl = "https://compatible.example/v1"),
            params = TextGenerationParams(
                model = reasoningModel(),
                reasoningLevel = ReasoningLevel.OFF,
                omitReasoningConfigurationWhenOff = true,
                customBody = listOf(CustomBody("reasoning", JsonPrimitive("must-not-send"))),
            ),
        )

        assertFalse(request.containsKey("reasoning"))
    }

    private fun reasoningModel() = Model(
        modelId = "reasoning-model",
        abilities = listOf(ModelAbility.REASONING),
    )

    private fun buildRequest(
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
            listOf(UIMessage.user("summarize this turn")),
            params,
            provider,
            false,
        ) as JsonObject
    }
}
