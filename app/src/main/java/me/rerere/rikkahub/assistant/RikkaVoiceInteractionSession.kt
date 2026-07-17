package me.rerere.rikkahub.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Adapter boundary between Android's [VoiceInteractionSession] lifecycle and the main-process
 * system-assistant UI/controller. Installing an adapter never affects the lightweight
 * `:voice_interactor` process because sessions are created by [RikkaVoiceSessionService].
 */
interface SystemAssistantSessionAdapter {
    fun createContentView(session: RikkaVoiceInteractionSession): View

    fun onShow(session: RikkaVoiceInteractionSession, args: Bundle?, showFlags: Int) = Unit

    fun onHide(session: RikkaVoiceInteractionSession) = Unit

    fun onDestroy(session: RikkaVoiceInteractionSession) = Unit
}

/** Process-local installation point for the business/UI adapter added by the main app. */
object SystemAssistantSessionAdapterRegistry {
    @Volatile
    private var installed: SystemAssistantSessionAdapter? = null

    fun install(adapter: SystemAssistantSessionAdapter) {
        installed = adapter
    }

    fun reset() {
        installed = null
    }

    internal fun current(): SystemAssistantSessionAdapter = installed ?: PlaceholderSessionAdapter
}

/**
 * Minimal, model-free platform session. The real controller is supplied through
 * [SystemAssistantSessionAdapterRegistry]; until then Android can still create and display a
 * valid assistant window without touching ChatService.
 */
class RikkaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    private val adapter: SystemAssistantSessionAdapter = SystemAssistantSessionAdapterRegistry.current()

    override fun onCreateContentView(): View = runCatching {
        adapter.createContentView(this)
    }.getOrElse {
        PlaceholderSessionAdapter.createContentView(this)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        runAdapterCallback("onShow") { adapter.onShow(this, args, showFlags) }
    }

    override fun onHide() {
        runAdapterCallback("onHide") { adapter.onHide(this) }
        super.onHide()
    }

    override fun onDestroy() {
        runAdapterCallback("onDestroy") { adapter.onDestroy(this) }
        super.onDestroy()
    }

    override fun onBackPressed() {
        finish()
    }

    /**
     * MagicOS only treats services with supportsAssist=true as selectable system assistants.
     * The lightweight service disables both context flags before any session is shown; these
     * no-op handlers are the second privacy floor in case an OEM still delivers empty/legacy
     * assist callbacks.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?,
    ) = Unit

    override fun onHandleAssist(state: AssistState) = Unit

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onHandleAssistSecondary(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?,
        index: Int,
        count: Int,
    ) = Unit

    override fun onHandleScreenshot(screenshot: Bitmap?) = Unit

    private inline fun runAdapterCallback(name: String, callback: () -> Unit) {
        runCatching(callback).onFailure { error ->
            Log.e(TAG, "System assistant adapter $name failed", error)
        }
    }

    private companion object {
        const val TAG = "RikkaVoiceSession"
    }
}

private object PlaceholderSessionAdapter : SystemAssistantSessionAdapter {
    override fun createContentView(session: RikkaVoiceInteractionSession): View {
        val context = session.context
        val padding = (24 * context.resources.displayMetrics.density).toInt()
        return FrameLayout(context).apply {
            setPadding(padding, padding, padding, padding)
            addView(
                TextView(context).apply {
                    text = context.applicationInfo.loadLabel(context.packageManager)
                    gravity = Gravity.CENTER
                    textSize = 20f
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
    }
}
