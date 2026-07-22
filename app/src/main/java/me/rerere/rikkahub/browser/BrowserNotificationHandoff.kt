package me.rerere.rikkahub.browser

import java.security.MessageDigest

/** Opaque notification payload used to select an already-running AI browser page. */
data class BrowserNotificationTarget(
    val pageId: String?,
)

sealed interface BrowserNotificationResolution {
    data class ShowRegisteredPage(val pageId: String) : BrowserNotificationResolution
    data class TargetMissing(val pageId: String?) : BrowserNotificationResolution
}

/**
 * Pure policy for notification-to-page handoff. Android callers use the result to attach the
 * existing WebView; they must never replace a missing AI page with a new blank tab.
 */
object BrowserNotificationHandoff {
    fun resolve(
        target: BrowserNotificationTarget,
        registeredPageIds: Set<String>,
    ): BrowserNotificationResolution {
        val pageId = target.pageId
        return if (pageId != null && pageId in registeredPageIds) {
            BrowserNotificationResolution.ShowRegisteredPage(pageId)
        } else {
            BrowserNotificationResolution.TargetMissing(pageId)
        }
    }

    fun pageIdFor(conversationId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(conversationId.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "ai-$digest"
    }
}
