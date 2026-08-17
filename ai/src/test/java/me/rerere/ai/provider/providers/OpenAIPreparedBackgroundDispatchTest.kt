package me.rerere.ai.provider.providers

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.BackgroundProviderDispatchCallback
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.RemoteBackgroundApiFamily
import me.rerere.ai.provider.RemoteBackgroundDispatchAttestation
import me.rerere.ai.provider.RemoteBackgroundDispatchContext
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIPreparedBackgroundDispatchTest {
    @Test
    fun `dispatch callback failure sends zero HTTP bytes`() = runBlocking {
        val networkAttempts = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor {
                networkAttempts.incrementAndGet()
                error("network dispatch must not be reached")
            }
            .build()
        val provider = OpenAIProvider(client)
        val model = Model(modelId = "deepseek-v4-flash")
        val setting = ProviderSetting.OpenAI(
            baseUrl = "https://opencode.ai/zen/go/v1",
            apiKey = "test-key",
            chatCompletionsPath = "/chat/completions",
            useResponseApi = false,
        )
        val context = RemoteBackgroundDispatchContext(
            providerIdentitySha256 = "a".repeat(64),
            modelIdentitySha256 = "b".repeat(64),
            configurationIdentitySha256 = "c".repeat(64),
            templateVersion = "reflection-v1",
            inputIdentitySha256 = "d".repeat(64),
            providerRequestKey = "learning-provider-v1:${"e".repeat(64)}",
            maxOutputTokens = 256,
        )
        val attestation = RemoteBackgroundDispatchAttestation(
            apiFamily = RemoteBackgroundApiFamily.OPENCODE_GO_CHAT_COMPLETIONS_V1,
            context = context,
        )
        val prepared = provider.prepareRemoteBackgroundTextGeneration(
            providerSetting = setting,
            messages = listOf(UIMessage.system("system"), UIMessage.user("payload")),
            params = TextGenerationParams(
                model = model,
                maxTokens = context.maxOutputTokens,
                stableProviderIdempotencyKey = context.providerRequestKey,
                remoteBackgroundDispatchContext = context,
            ),
            expectedAttestation = attestation,
        )

        var rejected = false
        try {
            prepared.streamText(
                BackgroundProviderDispatchCallback { throw IllegalStateException("ledger") },
            ).collect()
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(0, networkAttempts.get())
    }
}
