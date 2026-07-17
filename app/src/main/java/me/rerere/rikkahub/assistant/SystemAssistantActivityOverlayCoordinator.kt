package me.rerere.rikkahub.assistant

import android.app.Activity
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.data.ai.tools.local.VERIFIED_ACCESSIBILITY_TOOL_NAMES

object SystemAssistantActivityOverlayCoordinator {
    private val visibleActivity = AtomicReference<WeakReference<Activity>?>(null)

    fun attach(activity: Activity) {
        visibleActivity.set(WeakReference(activity))
    }

    fun detach(activity: Activity) {
        val current = visibleActivity.get()
        if (current?.get() === activity) visibleActivity.compareAndSet(current, null)
    }

    suspend fun dismissAndAwait(timeoutMs: Long = 1_500L): Boolean {
        val activity = visibleActivity.get()?.get() ?: return true
        withContext(Dispatchers.Main.immediate) { activity.finish() }
        return withTimeoutOrNull(timeoutMs) {
            while (visibleActivity.get()?.get() === activity) delay(25)
            true
        } == true
    }
}

object ActivityOverlayToolHandoffPolicy {
    private val legacyScreenTools = setOf(
        "read_window_tree",
        "find_node",
        "tap",
        "click_node",
        "long_press",
        "swipe",
        "set_text",
        "scroll",
        "global_action",
        "take_screenshot",
        "wake_screen",
    )

    fun requiresOverlayDismissal(toolName: String): Boolean =
        toolName in legacyScreenTools ||
            toolName in VERIFIED_ACCESSIBILITY_TOOL_NAMES ||
            toolName.startsWith("keyboard_") ||
            toolName.startsWith("browser_")
}
