package me.rerere.rikkahub.ui.pages.backup

import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.completeLocalBackupConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalBackupConfigTest {
    @Test
    fun `local archive always contains database and app files`() {
        val cloudChoice = WebDavConfig(items = emptyList())

        val local = completeLocalBackupConfig(cloudChoice)

        assertEquals(WebDavConfig.BackupItem.entries.toList(), local.items)
    }
}
