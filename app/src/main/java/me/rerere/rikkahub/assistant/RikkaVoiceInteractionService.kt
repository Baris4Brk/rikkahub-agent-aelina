package me.rerere.rikkahub.assistant

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight system entry point used by Android to discover and activate RikkaHub as the
 * selected voice interaction service.
 *
 * This component intentionally lives in the dedicated private app process declared in the manifest.
 * It never resolves the application dependency graph or starts a conversation; the platform
 * binds [RikkaVoiceSessionService] in the main process when it needs an interactive session.
 */
class RikkaVoiceInteractionService : VoiceInteractionService() {
    private var honorAiKeyBindingGuard: HonorAiKeyBindingGuard? = null

    override fun onReady() {
        super.onReady()
        // supportsAssist=true is required for ROLE_ASSISTANT to activate a VoiceInteractionService.
        // Disable both context channels so phase 1 still receives no AssistStructure or screenshot.
        setDisabledShowContext(
            VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
        )
        activeService.set(this)
        honorAiKeyBindingGuard?.close()
        honorAiKeyBindingGuard = createHonorAiKeyBindingGuard(this).also { it.start() }
        Log.i(TAG, "Voice interaction service is ready")
    }

    override fun onShutdown() {
        closeHonorAiKeyBindingGuard()
        activeService.compareAndSet(this, null)
        Log.i(TAG, "Voice interaction service is shutting down")
        super.onShutdown()
    }

    override fun onDestroy() {
        closeHonorAiKeyBindingGuard()
        activeService.compareAndSet(this, null)
        super.onDestroy()
    }

    private fun closeHonorAiKeyBindingGuard() {
        honorAiKeyBindingGuard?.close()
        honorAiKeyBindingGuard = null
    }

    companion object {
        private const val TAG = "RikkaVoiceService"
        private val activeService = AtomicReference<RikkaVoiceInteractionService?>(null)

        internal fun showLocalSession(): Boolean {
            val service = activeService.get() ?: return false
            return runCatching {
                service.showSession(null, 0)
                true
            }.onFailure { error ->
                Log.w(TAG, "Unable to show local voice session", error)
            }.getOrDefault(false)
        }
    }
}
