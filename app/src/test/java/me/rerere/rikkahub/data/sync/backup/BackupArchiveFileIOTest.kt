package me.rerere.rikkahub.data.sync.backup

import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupArchiveFileIOTest {
    @Test
    fun `writer puts strict manifest first and reader verifies every entry`() {
        val root = createTempDirectory("backup-v1-").toFile()
        try {
            val database = root.resolve("source.db").apply { writeBytes(ByteArray(512) { 7 }) }
            val payload = root.resolve("payload.bin").apply { writeBytes("hello".toByteArray()) }
            val archive = root.resolve("backup.zip")
            val stream = BackupAuthorityStreamV1(UUID.randomUUID().toString(), 1L)

            BackupArchiveV1FileIO.write(
                destination = archive,
                components = setOf(
                    BackupArchiveComponent.DATABASE,
                    BackupArchiveComponent.SETTINGS,
                    BackupArchiveComponent.FILES,
                ),
                mainStream = stream,
                sources = listOf(
                    BackupArchiveSourceV1.Bytes(BACKUP_ARCHIVE_SETTINGS_ENTRY, "{}".toByteArray()),
                    BackupArchiveSourceV1.FileSource(BACKUP_ARCHIVE_MAIN_DATABASE_ENTRY, database),
                    BackupArchiveSourceV1.FileSource("upload/payload.bin", payload),
                ),
            )

            val first = ZipInputStream(archive.inputStream().buffered()).use { it.nextEntry?.name }
            assertEquals(BACKUP_ARCHIVE_MANIFEST_ENTRY, first)
            val verified = BackupArchiveV1FileIO.inspectForRestore(archive)
            assertEquals(BackupArchiveOrigin.MANIFEST_V1, verified.origin)
            assertEquals(stream, verified.manifest.mainStream)
            assertEquals("{}", BackupArchiveV1FileIO.readSmallEntry(
                verified,
                BACKUP_ARCHIVE_SETTINGS_ENTRY,
                1024,
            ).toString(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `narrow legacy archive is marked v0 with unknown declared stream`() {
        val root = createTempDirectory("backup-legacy-").toFile()
        try {
            val archive = root.resolve("legacy.zip")
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry(BACKUP_ARCHIVE_SETTINGS_ENTRY))
                zip.write("{}".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(BACKUP_ARCHIVE_MAIN_DATABASE_ENTRY))
                zip.write(ByteArray(512) { 3 })
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("rikka_hub-wal"))
                zip.closeEntry()
            }

            val verified = BackupArchiveV1FileIO.inspectForRestore(archive)
            assertEquals(BackupArchiveOrigin.LEGACY_V0, verified.origin)
            assertNull(verified.manifest.mainStream)
            assertTrue(BackupArchiveComponent.DATABASE in verified.manifest.components)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test(expected = BackupArchiveFileException::class)
    fun `legacy nonempty wal is refused instead of silently dropping committed frames`() {
        val root = createTempDirectory("backup-legacy-wal-").toFile()
        try {
            val archive = root.resolve("legacy.zip")
            ZipOutputStream(FileOutputStream(archive)).use { zip ->
                zip.putNextEntry(ZipEntry(BACKUP_ARCHIVE_SETTINGS_ENTRY))
                zip.write("{}".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(BACKUP_ARCHIVE_MAIN_DATABASE_ENTRY))
                zip.write(ByteArray(512) { 3 })
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("rikka_hub-wal"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
            BackupArchiveV1FileIO.inspectForRestore(archive)
        } finally {
            root.deleteRecursively()
        }
    }
}
