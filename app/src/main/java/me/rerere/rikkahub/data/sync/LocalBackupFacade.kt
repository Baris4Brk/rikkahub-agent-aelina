package me.rerere.rikkahub.data.sync

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.sync.webdav.WebDavSync

/** One local backup path shared by the UI and Owner host runtime. */
class LocalBackupFacade(
    private val settingsStore: SettingsStore,
    private val webDavSync: WebDavSync,
    private val files: FilesManager,
) {
    suspend fun exportTemporary(): File {
        val file = webDavSync.prepareBackupFile(localConfig())
        recordBackupTime()
        return file
    }

    suspend fun exportManaged(): ManagedFileEntity = withContext(Dispatchers.IO) {
        val temporary = exportTemporary()
        try {
            files.saveManagedFromFile(
                folder = FileFolders.BACKUPS,
                source = temporary,
                displayName = temporary.name,
                mimeType = "application/zip",
            )
        } finally {
            temporary.delete()
        }
    }

    suspend fun restoreFromLocalFile(file: File) = webDavSync.restoreFromLocalFile(file, localConfig())

    private fun localConfig(): WebDavConfig = completeLocalBackupConfig(
        settingsStore.settingsFlow.value.webDavConfig,
    )

    private suspend fun recordBackupTime() {
        settingsStore.update { settings -> settings.copy(
            backupReminderConfig = settings.backupReminderConfig.copy(lastBackupTime = System.currentTimeMillis()),
        ) }
    }
}

internal fun completeLocalBackupConfig(base: WebDavConfig): WebDavConfig = base.copy(
    items = WebDavConfig.BackupItem.entries.toList(),
)
