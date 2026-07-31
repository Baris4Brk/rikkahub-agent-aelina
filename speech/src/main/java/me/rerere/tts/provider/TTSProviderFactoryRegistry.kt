package me.rerere.tts.provider

import android.content.Context
import kotlinx.coroutines.flow.Flow
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.TTSRequest

fun interface TTSProviderFactory {
    fun generate(
        context: Context,
        setting: TTSProviderSetting,
        request: TTSRequest,
    ): Flow<AudioChunk>?
}
/** Extensible fixed-factory dispatch without reflection or model-supplied class names. */
class TTSProviderFactoryRegistry(
    private val factories: List<TTSProviderFactory>,
) {
    fun generate(context: Context, setting: TTSProviderSetting, request: TTSRequest): Flow<AudioChunk> =
        factories.firstNotNullOfOrNull { it.generate(context, setting, request) }
            ?: error("Unsupported TTS provider type: ${setting::class.simpleName}")
}
