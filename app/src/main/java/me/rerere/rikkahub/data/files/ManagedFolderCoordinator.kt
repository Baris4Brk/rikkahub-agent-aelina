package me.rerere.rikkahub.data.files

import me.rerere.rikkahub.data.db.entity.ManagedFileEntity

enum class ManagedFolder(val directoryName: String) {
    Upload(FileFolders.UPLOAD),
    ;

    companion object {
        fun fromDirectoryName(value: String): ManagedFolder? = entries.firstOrNull {
            it.directoryName == value
        }
    }
}

enum class FolderSyncStatus {
    Complete,
    DirectoryMissing,
    ScanFailed,
    UnsafePath,
}

data class FolderSyncResult(
    val inserted: Int,
    val removed: Int,
    val status: FolderSyncStatus,
)

data class FolderCleanupResult(
    val deletedFiles: Int,
    val retainedFiles: Int,
    val removedRecords: Int,
    val complete: Boolean,
)

internal data class ManagedFolderDiskFile(
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
)

internal sealed interface ManagedFolderScan {
    data class Success(val files: List<ManagedFolderDiskFile>) : ManagedFolderScan
    data object DirectoryMissing : ManagedFolderScan
    data object Failed : ManagedFolderScan
    data object UnsafePath : ManagedFolderScan
}

internal enum class ManagedFileDeleteStatus {
    Deleted,
    Missing,
    Failed,
    UnsafePath,
}

internal interface ManagedFolderDisk {
    fun scan(folder: ManagedFolder): ManagedFolderScan

    fun isSafePath(folder: ManagedFolder, relativePath: String): Boolean

    fun delete(folder: ManagedFolder, relativePath: String): ManagedFileDeleteStatus
}

internal interface ManagedFileIndex {
    suspend fun list(folder: String): List<ManagedFileEntity>
    suspend fun get(id: Long): ManagedFileEntity?
    suspend fun insert(entity: ManagedFileEntity): ManagedFileEntity
    suspend fun delete(id: Long): Int
}

internal class ManagedFolderCoordinator(
    private val disk: ManagedFolderDisk,
    private val index: ManagedFileIndex,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun sync(folder: ManagedFolder): FolderSyncResult {
        val snapshot = when (val scan = disk.scan(folder)) {
            is ManagedFolderScan.Success -> scan.files
            ManagedFolderScan.DirectoryMissing -> return FolderSyncResult(
                inserted = 0,
                removed = 0,
                status = FolderSyncStatus.DirectoryMissing,
            )
            ManagedFolderScan.Failed -> return FolderSyncResult(0, 0, FolderSyncStatus.ScanFailed)
            ManagedFolderScan.UnsafePath -> return FolderSyncResult(0, 0, FolderSyncStatus.UnsafePath)
        }
        val records = index.list(folder.directoryName)
        if (!pathsAreSafe(folder, snapshot, records)) {
            return FolderSyncResult(0, 0, FolderSyncStatus.UnsafePath)
        }

        val recordsByPath = records.associateBy { it.relativePath }
        val snapshotPaths = snapshot.mapTo(mutableSetOf()) { it.relativePath }
        var inserted = 0
        snapshot.filter { it.relativePath !in recordsByPath }.forEach { file ->
            val now = clockMillis()
            index.insert(
                ManagedFileEntity(
                    folder = folder.directoryName,
                    relativePath = file.relativePath,
                    displayName = file.displayName,
                    mimeType = file.mimeType,
                    sizeBytes = file.sizeBytes,
                    createdAt = file.lastModifiedMillis.takeIf { it > 0 } ?: now,
                    updatedAt = now,
                ),
            )
            inserted += 1
        }

        var removed = 0
        records.filter { it.relativePath !in snapshotPaths }.forEach { record ->
            removed += index.delete(record.id)
        }
        return FolderSyncResult(inserted, removed, FolderSyncStatus.Complete)
    }

    suspend fun cleanup(folder: ManagedFolder): FolderCleanupResult {
        val records = index.list(folder.directoryName)
        val snapshot = when (val scan = disk.scan(folder)) {
            is ManagedFolderScan.Success -> scan.files
            ManagedFolderScan.DirectoryMissing,
            ManagedFolderScan.Failed,
            ManagedFolderScan.UnsafePath,
            -> return FolderCleanupResult(
                deletedFiles = 0,
                retainedFiles = records.size,
                removedRecords = 0,
                complete = false,
            )
        }
        if (!pathsAreSafe(folder, snapshot, records)) {
            return FolderCleanupResult(0, records.size, 0, complete = false)
        }

        val recordsByPath = records.associateBy { it.relativePath }
        val snapshotPaths = snapshot.mapTo(mutableSetOf()) { it.relativePath }
        var removedRecords = 0

        // A successful complete scan is positive proof that these indexed files are absent.
        records.filter { it.relativePath !in snapshotPaths }.forEach { record ->
            removedRecords += index.delete(record.id)
        }

        var deletedFiles = 0
        var retainedFiles = 0
        snapshot.forEach { file ->
            when (disk.delete(folder, file.relativePath)) {
                ManagedFileDeleteStatus.Deleted -> {
                    deletedFiles += 1
                    recordsByPath[file.relativePath]?.let { record ->
                        removedRecords += index.delete(record.id)
                    }
                }
                ManagedFileDeleteStatus.Missing -> {
                    recordsByPath[file.relativePath]?.let { record ->
                        removedRecords += index.delete(record.id)
                    }
                }
                ManagedFileDeleteStatus.Failed,
                ManagedFileDeleteStatus.UnsafePath,
                -> retainedFiles += 1
            }
        }
        return FolderCleanupResult(
            deletedFiles = deletedFiles,
            retainedFiles = retainedFiles,
            removedRecords = removedRecords,
            complete = retainedFiles == 0,
        )
    }

    suspend fun delete(id: Long, deleteFromDisk: Boolean): Boolean {
        val record = index.get(id) ?: return false
        val folder = ManagedFolder.fromDirectoryName(record.folder) ?: return false
        if (!disk.isSafePath(folder, record.relativePath)) return false
        if (deleteFromDisk) {
            when (disk.delete(folder, record.relativePath)) {
                ManagedFileDeleteStatus.Deleted,
                ManagedFileDeleteStatus.Missing,
                -> Unit
                ManagedFileDeleteStatus.Failed,
                ManagedFileDeleteStatus.UnsafePath,
                -> return false
            }
        }
        return index.delete(record.id) > 0
    }

    private fun pathsAreSafe(
        folder: ManagedFolder,
        snapshot: List<ManagedFolderDiskFile>,
        records: List<ManagedFileEntity>,
    ): Boolean = snapshot.all { disk.isSafePath(folder, it.relativePath) } &&
        records.all {
            it.folder == folder.directoryName && disk.isSafePath(folder, it.relativePath)
        }
}
