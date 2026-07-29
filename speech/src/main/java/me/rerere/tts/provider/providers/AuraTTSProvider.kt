package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "AuraTTSProvider"

@Serializable
private data class AuraTtsResponse(
    val audio: String = "",
    val trace_id: String? = null,
)

class AuraTTSProvider : TTSProvider<TTSProviderSetting.Aura> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.Aura,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = buildJsonObject {
            put("model", providerSetting.model)
            put("text", request.text)
            put("stream", false)
            put("output_format", "hex")
            put("voice_setting", buildJsonObject {
                put("voice_id", providerSetting.voiceId)
                put("emotion", providerSetting.emotion)
                put("speed", providerSetting.speed)
            })
        }

        Log.i(TAG, "generateSpeech: model=${providerSetting.model}, chars=${request.text.length}")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl.trimEnd('/')}/tts")
            .addHeader("Authorization", "Bearer ${providerSetting.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()
        val responseBody = response.body.string()

        if (!response.isSuccessful) {
            throw Exception(
                "Aura TTS request failed: ${response.code} ${response.message}. $responseBody"
            )
        }

        val payload = json.decodeFromString<AuraTtsResponse>(responseBody)
        if (payload.audio.isBlank()) {
            throw Exception("Aura TTS response missing audio data")
        }

        val audioData = hexStringToBytes(payload.audio)
        emit(
            AudioChunk(
                data = audioData,
                format = AudioFormat.MP3,
                sampleRate = 32000,
                isLast = true,
                metadata = mapOf(
                    "provider" to "aura",
                    "model" to providerSetting.model,
                    "voice" to providerSetting.voiceId,
                    "trace_id" to (payload.trace_id ?: ""),
                )
            )
        )
    }
}

private fun hexStringToBytes(hexString: String): ByteArray {
    val cleanHex = hexString.replace("\\s+".toRegex(), "")
    if (cleanHex.isEmpty() || cleanHex.length % 2 != 0) {
        throw IllegalArgumentException("Hex string must be non-empty and have even length")
    }

    val bytes = ByteArray(cleanHex.length / 2)
    for (i in cleanHex.indices step 2) {
        bytes[i / 2] = cleanHex.substring(i, i + 2).toInt(16).toByte()
    }
    return bytes
}
