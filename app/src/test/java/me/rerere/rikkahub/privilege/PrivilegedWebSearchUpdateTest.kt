package me.rerere.rikkahub.privilege

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedWebSearchUpdateTest {
    @Test
    fun `compatibility update changes only the calling assistant`() {
        val caller = Assistant(enableWebSearch = false)
        val other = Assistant(enableWebSearch = false)
        val settings = Settings(assistants = listOf(caller, other))

        val updated = settings.withAssistantWebSearch(caller.id, enabled = true)

        assertTrue(updated.assistants.single { it.id == caller.id }.enableWebSearch)
        assertFalse(updated.assistants.single { it.id == other.id }.enableWebSearch)
        assertFalse(updated.enableWebSearch)
    }
}
