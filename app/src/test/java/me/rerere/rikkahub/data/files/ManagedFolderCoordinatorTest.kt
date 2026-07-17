package me.rerere.rikkahub.data.files

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedFolderCoordinatorTest {
    @Test
    fun `successful sync inserts disk files and removes confirmed orphan records`() = runBlocking {
        val index = FakeManagedFileIndex(
            mutableListOf(record(id = 1, path = "upload/old.txt")),
        )
        val disk = FakeManagedFolderDisk(
            scan = ManagedFolderScan.Success(
                listOf(diskFile(path = "upload/new.txt")),
            ),
        )

        val result = ManagedFolderCoordinator(disk, index, clockMillis = { 123L })
            .sync(ManagedFolder.Upload)

        assertEquals(FolderSyncResult(1, 1, FolderSyncStatus.Complete), result)
        assertEquals(listOf("upload/new.txt"), index.records.map { it.relativePath })
    }

    @Test
    fun `missing or failed directory scan never removes database records`() = runBlocking {
        listOf(
            ManagedFolderScan.DirectoryMissing to FolderSyncStatus.DirectoryMissing,
            ManagedFolderScan.Failed to FolderSyncStatus.ScanFailed,
        ).forEach { (scan, expectedStatus) ->
            val index = FakeManagedFileIndex(mutableListOf(record(1, "upload/keep.txt")))

            val result = ManagedFolderCoordinator(FakeManagedFolderDisk(scan), index)
                .sync(ManagedFolder.Upload)

            assertEquals(expectedStatus, result.status)
            assertEquals(listOf("upload/keep.txt"), index.records.map { it.relativePath })
        }
    }

    @Test
    fun `unsafe indexed path aborts sync before any insert or removal`() = runBlocking {
        val index = FakeManagedFileIndex(mutableListOf(record(1, "upload/../database.db")))
        val disk = FakeManagedFolderDisk(
            scan = ManagedFolderScan.Success(listOf(diskFile("upload/new.txt"))),
            safePaths = setOf("upload/new.txt"),
        )

        val result = ManagedFolderCoordinator(disk, index).sync(ManagedFolder.Upload)

        assertEquals(FolderSyncStatus.UnsafePath, result.status)
        assertEquals(listOf("upload/../database.db"), index.records.map { it.relativePath })
    }

    @Test
    fun `partial cleanup removes only absent or successfully deleted records`() = runBlocking {
        val index = FakeManagedFileIndex(
            mutableListOf(
                record(1, "upload/orphan.txt"),
                record(2, "upload/deleted.txt"),
                record(3, "upload/retained.txt"),
            ),
        )
        val disk = FakeManagedFolderDisk(
            scan = ManagedFolderScan.Success(
                listOf(
                    diskFile("upload/deleted.txt"),
                    diskFile("upload/retained.txt"),
                ),
            ),
            deletes = mutableMapOf(
                "upload/deleted.txt" to ManagedFileDeleteStatus.Deleted,
                "upload/retained.txt" to ManagedFileDeleteStatus.Failed,
            ),
        )

        val result = ManagedFolderCoordinator(disk, index).cleanup(ManagedFolder.Upload)

        assertEquals(FolderCleanupResult(1, 1, 2, complete = false), result)
        assertEquals(listOf("upload/retained.txt"), index.records.map { it.relativePath })
    }

    @Test
    fun `single delete retains the record on disk failure but removes confirmed missing file`() = runBlocking {
        val index = FakeManagedFileIndex(mutableListOf(record(1, "upload/file.txt")))
        val disk = FakeManagedFolderDisk(
            scan = ManagedFolderScan.Success(emptyList()),
            deletes = mutableMapOf("upload/file.txt" to ManagedFileDeleteStatus.Failed),
        )
        val coordinator = ManagedFolderCoordinator(disk, index)

        assertFalse(coordinator.delete(1, deleteFromDisk = true))
        assertEquals(1, index.records.size)

        disk.deletes["upload/file.txt"] = ManagedFileDeleteStatus.Missing
        assertTrue(coordinator.delete(1, deleteFromDisk = true))
        assertTrue(index.records.isEmpty())
    }

    @Test
    fun `file disk adapter rejects traversal outside the managed upload directory`() {
        val root = kotlin.io.path.createTempDirectory("managed-folder-test").toFile()
        try {
            val disk = FileManagedFolderDisk(root) { "application/octet-stream" }

            assertFalse(disk.isSafePath(ManagedFolder.Upload, "upload/../database.db"))
            assertFalse(disk.isSafePath(ManagedFolder.Upload, "upload/nested/file.txt"))
            assertTrue(disk.isSafePath(ManagedFolder.Upload, "upload/file.txt"))
        } finally {
            root.deleteRecursively()
        }
    }

    private class FakeManagedFileIndex(
        val records: MutableList<ManagedFileEntity> = mutableListOf(),
    ) : ManagedFileIndex {
        private var nextId = (records.maxOfOrNull { it.id } ?: 0L) + 1L

        override suspend fun list(folder: String): List<ManagedFileEntity> =
            records.filter { it.folder == folder }

        override suspend fun get(id: Long): ManagedFileEntity? = records.firstOrNull { it.id == id }

        override suspend fun insert(entity: ManagedFileEntity): ManagedFileEntity {
            val stored = entity.copy(id = nextId++)
            records += stored
            return stored
        }

        override suspend fun delete(id: Long): Int =
            if (records.removeIf { it.id == id }) 1 else 0
    }

    private class FakeManagedFolderDisk(
        var scan: ManagedFolderScan,
        private val safePaths: Set<String>? = null,
        val deletes: MutableMap<String, ManagedFileDeleteStatus> = mutableMapOf(),
    ) : ManagedFolderDisk {
        override fun scan(folder: ManagedFolder): ManagedFolderScan = scan

        override fun isSafePath(folder: ManagedFolder, relativePath: String): Boolean =
            safePaths?.contains(relativePath) ?: relativePath.startsWith("${folder.directoryName}/")

        override fun delete(
            folder: ManagedFolder,
            relativePath: String,
        ): ManagedFileDeleteStatus = deletes[relativePath] ?: ManagedFileDeleteStatus.Deleted
    }

    private companion object {
        fun record(id: Long, path: String) = ManagedFileEntity(
            id = id,
            folder = FileFolders.UPLOAD,
            relativePath = path,
            displayName = path.substringAfterLast('/'),
            mimeType = "text/plain",
            sizeBytes = 1,
            createdAt = 1,
            updatedAt = 1,
        )

        fun diskFile(path: String) = ManagedFolderDiskFile(
            relativePath = path,
            displayName = path.substringAfterLast('/'),
            mimeType = "text/plain",
            sizeBytes = 1,
            lastModifiedMillis = 1,
        )
    }
}
