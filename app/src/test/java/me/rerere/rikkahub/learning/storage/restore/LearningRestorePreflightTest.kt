package me.rerere.rikkahub.learning.storage.restore

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LearningRestorePreflightTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun settingsAndFilesOnlyDoesNotOpenArchive() {
        val missing = File(temporaryFolder.root, "does-not-exist.zip")
        val result = LearningRestoreArchivePreflightVerifier().inspect(
            archiveFile = missing,
            selectedComponents = setOf(
                LearningRestoreComponent.SETTINGS,
                LearningRestoreComponent.FILES,
            ),
        )

        assertEquals(LearningRestorePreflight.NoDatabaseSelected, result)
        assertFalse(missing.exists())
    }

    @Test
    fun verifiedArchiveRequiresSQLiteHeaderSizeAndMatchingManifestChecksum() {
        val archive = createVerifiedArchive(temporaryFolder.newFile("valid.zip"))

        val result = LearningRestoreArchivePreflightVerifier().inspect(
            archive,
            setOf(LearningRestoreComponent.DATABASE),
        )

        assertTrue(result is LearningRestorePreflight.VerifiedDatabase)
        result as LearningRestorePreflight.VerifiedDatabase
        assertEquals(512L, result.mainDatabaseSizeBytes)
        assertEquals(1, result.manifestFormatVersion)
        assertTrue(result.isArchiveIdentityCurrent())
        assertFalse(result.toString().contains(temporaryFolder.root.absolutePath))
    }

    @Test
    fun checksumMismatchFailsClosed() {
        val archive = createVerifiedArchive(
            temporaryFolder.newFile("bad-checksum.zip"),
            declaredDigest = "0".repeat(64),
        )

        val result = LearningRestoreArchivePreflightVerifier().inspect(
            archive,
            setOf(LearningRestoreComponent.DATABASE),
        )

        assertEquals(
            LearningRestorePreflight.Rejected(
                LearningRestorePreflightFailure.MAIN_DATABASE_CHECKSUM_MISMATCH,
            ),
            result,
        )
    }

    @Test
    fun anyTraversalEntryRejectsEntireArchiveBeforeRestore() {
        val archive = createVerifiedArchive(
            temporaryFolder.newFile("traversal.zip"),
            extraEntries = mapOf("../databases/learning_runtime.db" to byteArrayOf(1, 2, 3)),
        )

        val result = LearningRestoreArchivePreflightVerifier().inspect(
            archive,
            setOf(LearningRestoreComponent.DATABASE),
        )

        assertEquals(
            LearningRestorePreflight.Rejected(LearningRestorePreflightFailure.UNSAFE_ZIP_ENTRY),
            result,
        )
    }

    @Test
    fun archiveMutationInvalidatesVerifiedIdentity() {
        val archive = createVerifiedArchive(temporaryFolder.newFile("mutated.zip"))
        val verified = LearningRestoreArchivePreflightVerifier().inspect(
            archive,
            setOf(LearningRestoreComponent.DATABASE),
        ) as LearningRestorePreflight.VerifiedDatabase

        archive.appendBytes(byteArrayOf(0))

        assertFalse(verified.isArchiveIdentityCurrent())
    }
}

internal fun createVerifiedArchive(
    archive: File,
    databaseBytes: ByteArray = validSQLiteBytes(),
    declaredDigest: String = sha256(databaseBytes),
    extraEntries: Map<String, ByteArray> = emptyMap(),
): File {
    val manifest = """
        {
          "formatVersion": 1,
          "learningDbExcluded": true,
          "entries": {
            "rikka_hub.db": {
              "size": ${databaseBytes.size},
              "sha256": "$declaredDigest"
            }
          }
        }
    """.trimIndent().toByteArray(Charsets.UTF_8)

    ZipOutputStream(FileOutputStream(archive)).use { zip ->
        zip.writeEntry("backup_manifest.json", manifest)
        zip.writeEntry("rikka_hub.db", databaseBytes)
        extraEntries.forEach { (name, bytes) -> zip.writeEntry(name, bytes) }
    }
    return archive
}

internal fun validSQLiteBytes(): ByteArray = ByteArray(512).also { bytes ->
    "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes)
    bytes[16] = 0x02
    bytes[17] = 0x00
    bytes[18] = 0x02
    bytes[19] = 0x02
}

private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(name))
    write(bytes)
    closeEntry()
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }
