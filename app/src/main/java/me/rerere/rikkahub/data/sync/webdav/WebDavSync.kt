package me.rerere.rikkahub.data.sync.webdav

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.sync.backup.BackupArchiveService
import me.rerere.rikkahub.data.sync.backup.BackupRestoreDisposition
import me.rerere.rikkahub.utils.fileSizeToString

private const val TAG = "WebDavSync"

class WebDavSync(
    settingsStore: SettingsStore,
    json: Json,
    private val context: Context,
    private val httpClient: HttpClient,
    appDatabase: AppDatabase,
) {
    private val archiveService = BackupArchiveService(
        settingsStore = settingsStore,
        json = json,
        context = context,
        appDatabase = appDatabase,
    )

    private fun getClient(config: WebDavConfig): WebDavClient = WebDavClient(config, httpClient)

    suspend fun testConnection(config: WebDavConfig) = withContext(Dispatchers.IO) {
        getClient(config).propfind(depth = 0).getOrThrow()
        Log.i(TAG, "testConnection: Connection successful")
    }

    suspend fun backup(config: WebDavConfig) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config)
        try {
            val client = getClient(config)
            client.ensureCollectionExists().getOrThrow()
            client.put(
                path = file.name,
                file = file,
                contentType = "application/zip",
            ).getOrThrow()
            Log.i(TAG, "backup: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } finally {
            file.delete()
        }
    }

    suspend fun listBackupFiles(config: WebDavConfig): List<WebDavBackupItem> =
        withContext(Dispatchers.IO) {
            val client = getClient(config)
            client.ensureCollectionExists().getOrThrow()
            client.list().getOrThrow()
                .filter { resource ->
                    !resource.isCollection && resource.displayName.startsWith("backup_") &&
                        resource.displayName.endsWith(".zip")
                }
                .map { resource ->
                    WebDavBackupItem(
                        href = resource.href,
                        displayName = resource.displayName,
                        size = resource.contentLength,
                        lastModified = resource.lastModified ?: Instant.EPOCH,
                    )
                }
                .sortedByDescending { it.lastModified }
        }

    suspend fun restore(config: WebDavConfig, item: WebDavBackupItem) =
        withContext(Dispatchers.IO) {
            val backupFile = File(context.cacheDir, item.displayName)
            try {
                Log.i(TAG, "restore: Downloading ${item.displayName}")
                getClient(config).downloadToFile(item.displayName, backupFile).getOrThrow()
                Log.i(TAG, "restore: Downloaded ${backupFile.length().fileSizeToString()}")
                restoreFromBackupFile(backupFile, config)
            } finally {
                if (backupFile.exists()) backupFile.delete()
            }
        }

    suspend fun deleteBackupFile(config: WebDavConfig, item: WebDavBackupItem) =
        withContext(Dispatchers.IO) {
            getClient(config).delete(item.displayName).getOrThrow()
            Log.i(TAG, "deleteBackupFile: Deleted ${item.displayName}")
        }

    suspend fun restoreFromLocalFile(file: File, config: WebDavConfig) =
        withContext(Dispatchers.IO) {
            require(file.exists()) { "Backup file does not exist" }
            require(file.canRead()) { "Cannot read backup file" }
            restoreFromBackupFile(file, config)
        }

    suspend fun prepareBackupFile(config: WebDavConfig): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")
        if (backupFile.exists() && !backupFile.delete()) {
            error("Unable to replace an existing temporary backup")
        }
        archiveService.createBackup(
            destination = backupFile,
            includeDatabase = WebDavConfig.BackupItem.DATABASE in config.items,
            includeFiles = WebDavConfig.BackupItem.FILES in config.items,
        )
        Log.i(
            TAG,
            "prepareBackupFile: Created ${backupFile.name} " +
                "(${backupFile.length().fileSizeToString()})",
        )
        backupFile
    }

    private suspend fun restoreFromBackupFile(backupFile: File, config: WebDavConfig) {
        when (archiveService.restore(
            archiveFile = backupFile,
            includeDatabase = WebDavConfig.BackupItem.DATABASE in config.items,
            includeFiles = WebDavConfig.BackupItem.FILES in config.items,
        )) {
            BackupRestoreDisposition.CompletedInCurrentProcess ->
                Log.i(TAG, "restore: Settings/files restore completed")
            BackupRestoreDisposition.ColdRestartRequired ->
                Log.i(TAG, "restore: Database staged; full process restart required")
        }
    }
}

data class WebDavBackupItem(
    val href: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
