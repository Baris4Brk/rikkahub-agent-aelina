package me.rerere.rikkahub.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BrowserNotificationHandoffTest {
    @Test
    fun `notification opens the registered AI page and never substitutes a blank tab`() {
        val target = BrowserNotificationTarget(
            pageId = "ai-page-77",
        )

        assertEquals(
            BrowserNotificationResolution.ShowRegisteredPage("ai-page-77"),
            BrowserNotificationHandoff.resolve(target, registeredPageIds = setOf("ui-1", "ai-page-77")),
        )

        val missing = BrowserNotificationHandoff.resolve(target, registeredPageIds = setOf("ui-1"))
        assertEquals(BrowserNotificationResolution.TargetMissing("ai-page-77"), missing)
        assertFalse(missing is BrowserNotificationResolution.ShowRegisteredPage)
    }

    @Test
    fun `AI page ids are stable without exposing the conversation id`() {
        val first = BrowserNotificationHandoff.pageIdFor("private-conversation-123")
        val replay = BrowserNotificationHandoff.pageIdFor("private-conversation-123")
        val other = BrowserNotificationHandoff.pageIdFor("private-conversation-456")

        assertEquals(first, replay)
        assertFalse(first == other)
        assertFalse(first.contains("private-conversation"))
    }
}
