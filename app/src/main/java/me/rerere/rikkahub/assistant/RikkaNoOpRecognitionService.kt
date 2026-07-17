package me.rerere.rikkahub.assistant

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Compatibility declaration required by the voice-interaction framework on older Android builds.
 * Phase 1 deliberately implements no speech recognition; every request fails closed immediately.
 */
class RikkaNoOpRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }
}
