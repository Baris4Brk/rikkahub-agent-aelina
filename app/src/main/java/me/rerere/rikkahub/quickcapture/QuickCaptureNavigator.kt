package me.rerere.rikkahub.quickcapture

import android.content.Context
import android.content.Intent
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.base64Encode
import kotlin.uuid.Uuid

/** Navigation stays outside the capture/service state machine so it is testable without Android. */
interface QuickCaptureNavigator {
    fun openConversation(conversationId: Uuid)

    fun openDraft(conversationId: Uuid, prompt: String, imageUris: List<String>)

    fun openSettings()
}

class AndroidQuickCaptureNavigator(
    context: Context,
) : QuickCaptureNavigator {
    private val appContext = context.applicationContext

    override fun openConversation(conversationId: Uuid) {
        appContext.startActivity(baseIntent().apply {
            putExtra(RouteActivity.EXTRA_CONVERSATION_ID, conversationId.toString())
        })
    }

    override fun openDraft(conversationId: Uuid, prompt: String, imageUris: List<String>) {
        appContext.startActivity(baseIntent().apply {
            putExtra(RouteActivity.EXTRA_CONVERSATION_ID, conversationId.toString())
            // Screen.Chat has historically carried draft text as Base64 so Navigation can
            // preserve arbitrary Unicode and punctuation. Keep the overlay on that contract.
            putExtra(RouteActivity.EXTRA_QUICK_CAPTURE_DRAFT_TEXT, prompt.base64Encode())
            putStringArrayListExtra(
                RouteActivity.EXTRA_QUICK_CAPTURE_DRAFT_FILES,
                ArrayList(imageUris),
            )
        })
    }

    override fun openSettings() {
        appContext.startActivity(baseIntent().apply {
            putExtra(RouteActivity.EXTRA_OPEN_QUICK_CAPTURE_SETTINGS, true)
        })
    }

    private fun baseIntent(): Intent = Intent(appContext, RouteActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
