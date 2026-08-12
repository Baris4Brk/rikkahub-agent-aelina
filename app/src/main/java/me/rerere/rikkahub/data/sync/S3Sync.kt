package me.rerere.rikkahub.data.sync

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
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.sync.backup.BackupArchiveService
import me.rerere.rikkahub.data.sync.backup.BackupRestoreDisposition
import me.rerere.rikkahub.data.sync.s3.S3Client
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.utils.fileSizeToString

private const val TAG = "S3Sync"
private const val BACKUP_PREFIX = "rikkahub_backups/"

class S3Sync(
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

    private fun getS3Client(config: S3Config): S3Client = S3Client(config, httpClient)

    suspend fun testS3(config: S3Config) = withContext(Dispatchers.IO) {
        getS3Client(config).listObjects(maxKeys = 1).getOrThrow()
        Log.i(TAG, "testS3: Connection successful")
    }

    suspend fun backupToS3(config: S3Config) = withContext(Dispatchers.IO) {
        val file = prepareBackupFile(config)
        try {
            getS3Client(config).putObject(
                key = "$BACKUP_PREFIX${file.name}",
                file = file,
                contentType = "application/zip",
            ).getOrThrow()
            Log.i(TAG, "backupToS3: Uploaded ${file.name} (${file.length().fileSizeToString()})")
        } finally {
            file.delete()
        }
    }

    suspend fun listBackupFiles(config: S3Config): List<S3BackupItem> =
        withContext(Dispatchers.IO) {
            getS3Client(config).listObjects(prefix = BACKUP_PREFIX, maxKeys = 1000)
                .getOrThrow().objects
                .filter { item ->
                    item.key.startsWith("${BACKUP_PREFIX}backup_") && item.key.endsWith(".zip")
                }
                .map { item ->
                    S3BackupItem(
                        key = item.key,
                        displayName = item.key.substringAfterLast('/'),
                        size = item.size,
                        lastModified = item.lastModified ?: Instant.EPOCH,
                    )
                }
                .sortedByDescending { it.lastModified }
        }

    suspend fun restoreFromS3(config: S3Config, item: S3BackupItem) =
        withContext(Dispatchers.IO) {
            val backupFile = File(context.cacheDir, item.displayName)
            try {
                Log.i(TAG, "restoreFromS3: Downloading ${item.displayName}")
                getS3Client(config).downloadObjectToFile(item.key, backupFile).getOrThrow()
                Log.i(TAG, "restoreFromS3: Downloaded ${backupFile.length().fileSizeToString()}")
                restoreFromBackupFile(backupFile, config)
            } finally {
                if (backupFile.exists()) backupFile.delete()
            }
        }

    suspend fun deleteS3BackupFile(config: S3Config, item: S3BackupItem) =
        withContext(Dispatchers.IO) {
            getS3Client(config).deleteObject(item.key).getOrThrow()
            Log.i(TAG, "deleteS3BackupFile: Deleted ${item.key}")
        }

    suspend fun prepareBackupFile(config: S3Config): File = withContext(Dispatchers.IO) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val backupFile = File(context.cacheDir, "backup_$timestamp.zip")
        if (backupFile.exists() && !backupFile.delete()) {
            error("Unable to replace an existing temporary backup")
        }
        archiveService.createBackup(
            destination = backupFile,
            includeDatabase = S3Config.BackupItem.DATABASE in config.items,
            includeFiles = S3Config.BackupItem.FILES in config.items,
        )
        Log.i(
            TAG,
            "prepareBackupFile: Created ${backupFile.name} " +
                "(${backupFile.length().fileSizeToString()})",
        )
        backupFile
    }

    private suspend fun restoreFromBackupFile(backupFile: File, config: S3Config) {
        when (archiveService.restore(
            archiveFile = backupFile,
            includeDatabase = S3Config.BackupItem.DATABASE in config.items,
            includeFiles = S3Config.BackupItem.FILES in config.items,
        )) {
            BackupRestoreDisposition.CompletedInCurrentProcess ->
                Log.i(TAG, "restore: Settings/files restore completed")
            BackupRestoreDisposition.ColdRestartRequired ->
                Log.i(TAG, "restore: Database staged; full process restart required")
        }
    }
}

data class S3BackupItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)
