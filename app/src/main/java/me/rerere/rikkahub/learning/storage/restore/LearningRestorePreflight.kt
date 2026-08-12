package me.rerere.rikkahub.learning.storage.restore

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val RESTORE_MANIFEST_ENTRY = "backup_manifest.json"
private const val RESTORE_MAIN_DATABASE_ENTRY = "rikka_hub.db"
private const val RESTORE_MANIFEST_VERSION = 1
private const val SHA_256_ALGORITHM = "SHA-256"
private const val RESTORE_SQLITE_HEADER_BYTES = 100
private val RESTORE_SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

/** Components selected by the user for one backup restore. */
enum class LearningRestoreComponent {
    DATABASE,
    SETTINGS,
    FILES,
}

enum class LearningRestorePreflightFailure {
    ARCHIVE_PATH_INVALID,
    ARCHIVE_NOT_REGULAR_FILE,
    ARCHIVE_SYMBOLIC_LINK,
    ARCHIVE_UNREADABLE,
    ARCHIVE_CHANGED_DURING_PREFLIGHT,
    UNSAFE_ZIP_ENTRY,
    DUPLICATE_ZIP_ENTRY,
    MANIFEST_MISSING,
    MANIFEST_TOO_LARGE,
    MANIFEST_INVALID,
    MANIFEST_VERSION_UNSUPPORTED,
    LEARNING_EXCLUSION_NOT_DECLARED,
    MAIN_DATABASE_MISSING,
    MAIN_DATABASE_TOO_LARGE,
    MAIN_DATABASE_INVALID,
    MAIN_DATABASE_CHECKSUM_MISSING,
    MAIN_DATABASE_CHECKSUM_MISMATCH,
    ARCHIVE_IO_FAILED,
}

/**
 * Fail-closed result of inspecting the exact archive that a sync adapter intends to restore.
 *
 * [NoDatabaseSelected] deliberately does not inspect or open the archive. Settings/files-only
 * restores must not stop Learning or move its database files.
 */
sealed interface LearningRestorePreflight {
    data object NoDatabaseSelected : LearningRestorePreflight

    data class Rejected(val failure: LearningRestorePreflightFailure) : LearningRestorePreflight

    class VerifiedDatabase internal constructor(
        val archiveFile: File,
        val archiveSha256: String,
        val mainDatabaseSha256: String,
        val mainDatabaseSizeBytes: Long,
        val manifestFormatVersion: Int,
    ) : LearningRestorePreflight {
        init {
            require(archiveFile.isAbsolute) { "Archive path must be absolute" }
            require(archiveSha256.isSha256()) { "Invalid archive digest" }
            require(mainDatabaseSha256.isSha256()) { "Invalid database digest" }
            require(mainDatabaseSizeBytes >= RESTORE_SQLITE_HEADER_BYTES) { "Invalid database size" }
            require(manifestFormatVersion == RESTORE_MANIFEST_VERSION) {
                "Unsupported manifest version"
            }
        }

        /**
         * Sync integration must restore this exact file, not another path supplied after preflight.
         * The full archive digest is retained so an integration can stage/recheck it if needed.
         */
        fun isArchiveIdentityCurrent(): Boolean = runCatching {
            val path = archiveFile.toPath().toAbsolutePath().normalize()
            !Files.isSymbolicLink(path) &&
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                constantTimeDigestEquals(archiveSha256, sha256File(archiveFile))
        }.getOrDefault(false)

        override fun toString(): String =
            "VerifiedDatabase(format=$manifestFormatVersion, bytes=$mainDatabaseSizeBytes, " +
                "archive=<redacted>, digests=<redacted>)"
    }
}

/**
 * Strict v1 archive preflight used before any process state is changed.
 *
 * Required manifest entry (`backup_manifest.json`):
 * ```json
 * {
 *   "formatVersion": 1,
 *   "learningDbExcluded": true,
 *   "entries": {
 *     "rikka_hub.db": { "size": 123, "sha256": "<64 lowercase hex>" }
 *   }
 * }
 * ```
 *
 * Existing legacy backups without this manifest are intentionally rejected for coordinated
 * database restore. The WebDAV/S3 integration must first emit the manifest and provide a real
 * all-application main-database write gate.
 */
class LearningRestoreArchivePreflightVerifier(
    private val maxMainDatabaseBytes: Long = DEFAULT_MAX_MAIN_DATABASE_BYTES,
    private val maxManifestBytes: Int = DEFAULT_MAX_MANIFEST_BYTES,
) {
    init {
        require(maxMainDatabaseBytes >= RESTORE_SQLITE_HEADER_BYTES)
        require(maxManifestBytes in 1_024..1_048_576)
    }

    fun inspect(
        archiveFile: File,
        selectedComponents: Set<LearningRestoreComponent>,
    ): LearningRestorePreflight {
        if (LearningRestoreComponent.DATABASE !in selectedComponents) {
            return LearningRestorePreflight.NoDatabaseSelected
        }

        val absoluteArchive = archiveFile.toPath().toAbsolutePath().normalize()
        if (!absoluteArchive.isAbsolute || archiveFile.path.isBlank()) {
            return rejected(LearningRestorePreflightFailure.ARCHIVE_PATH_INVALID)
        }
        val archiveCanonicalMatches = runCatching {
            absoluteArchive.toFile().canonicalFile.toPath().normalize() == absoluteArchive
        }.getOrDefault(false)
        if (Files.isSymbolicLink(absoluteArchive) || !archiveCanonicalMatches) {
            return rejected(LearningRestorePreflightFailure.ARCHIVE_SYMBOLIC_LINK)
        }
        if (!Files.isRegularFile(absoluteArchive, LinkOption.NOFOLLOW_LINKS)) {
            return rejected(LearningRestorePreflightFailure.ARCHIVE_NOT_REGULAR_FILE)
        }
        if (!Files.isReadable(absoluteArchive)) {
            return rejected(LearningRestorePreflightFailure.ARCHIVE_UNREADABLE)
        }

        return try {
            val archiveDigestBefore = sha256File(absoluteArchive.toFile())
            val scan = scanArchive(absoluteArchive.toFile())
                ?: return rejected(LearningRestorePreflightFailure.ARCHIVE_IO_FAILED)
            val archiveDigestAfter = sha256File(absoluteArchive.toFile())
            if (!constantTimeDigestEquals(archiveDigestBefore, archiveDigestAfter)) {
                return rejected(LearningRestorePreflightFailure.ARCHIVE_CHANGED_DURING_PREFLIGHT)
            }
            val manifestBytes = scan.manifestBytes
                ?: return rejected(LearningRestorePreflightFailure.MANIFEST_MISSING)
            val database = scan.database
                ?: return rejected(LearningRestorePreflightFailure.MAIN_DATABASE_MISSING)
            val manifest = parseManifest(manifestBytes)
                ?: return rejected(LearningRestorePreflightFailure.MANIFEST_INVALID)

            when {
                manifest.formatVersion != RESTORE_MANIFEST_VERSION ->
                    rejected(LearningRestorePreflightFailure.MANIFEST_VERSION_UNSUPPORTED)

                !manifest.learningDbExcluded ->
                    rejected(LearningRestorePreflightFailure.LEARNING_EXCLUSION_NOT_DECLARED)

                manifest.databaseSha256 == null || manifest.databaseSizeBytes == null ->
                    rejected(LearningRestorePreflightFailure.MAIN_DATABASE_CHECKSUM_MISSING)

                manifest.databaseSizeBytes != database.sizeBytes ||
                    !constantTimeDigestEquals(manifest.databaseSha256, database.sha256) ->
                    rejected(LearningRestorePreflightFailure.MAIN_DATABASE_CHECKSUM_MISMATCH)

                else -> LearningRestorePreflight.VerifiedDatabase(
                    archiveFile = absoluteArchive.toFile(),
                    archiveSha256 = archiveDigestAfter,
                    mainDatabaseSha256 = database.sha256,
                    mainDatabaseSizeBytes = database.sizeBytes,
                    manifestFormatVersion = manifest.formatVersion,
                )
            }
        } catch (_: ManifestTooLargeException) {
            rejected(LearningRestorePreflightFailure.MANIFEST_TOO_LARGE)
        } catch (_: MainDatabaseTooLargeException) {
            rejected(LearningRestorePreflightFailure.MAIN_DATABASE_TOO_LARGE)
        } catch (_: UnsafeEntryException) {
            rejected(LearningRestorePreflightFailure.UNSAFE_ZIP_ENTRY)
        } catch (_: DuplicateEntryException) {
            rejected(LearningRestorePreflightFailure.DUPLICATE_ZIP_ENTRY)
        } catch (_: InvalidMainDatabaseException) {
            rejected(LearningRestorePreflightFailure.MAIN_DATABASE_INVALID)
        } catch (_: Exception) {
            rejected(LearningRestorePreflightFailure.ARCHIVE_IO_FAILED)
        }
    }

    private fun scanArchive(file: File): ArchiveScan? {
        var manifest: ByteArray? = null
        var database: DatabaseScan? = null
        val names = mutableSetOf<String>()

        ZipInputStream(FileInputStream(file).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (!isSafeEntryName(name, entry.isDirectory)) throw UnsafeEntryException()
                if (!names.add(name)) throw DuplicateEntryException()

                if (!entry.isDirectory) {
                    when (name) {
                        RESTORE_MANIFEST_ENTRY -> {
                            manifest = readBounded(
                                input = zip,
                                limit = maxManifestBytes.toLong(),
                                tooLarge = ::ManifestTooLargeException,
                            )
                        }

                        RESTORE_MAIN_DATABASE_ENTRY -> database = scanMainDatabase(zip)
                    }
                }
                zip.closeEntry()
            }
        }
        return ArchiveScan(manifestBytes = manifest, database = database)
    }

    private fun scanMainDatabase(zip: ZipInputStream): DatabaseScan {
        val digest = MessageDigest.getInstance(SHA_256_ALGORITHM)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val header = ByteArray(RESTORE_SQLITE_HEADER_BYTES)
        var headerBytes = 0
        var total = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total = Math.addExact(total, count.toLong())
            if (total > maxMainDatabaseBytes) throw MainDatabaseTooLargeException()
            if (headerBytes < header.size) {
                val copyCount = minOf(count, header.size - headerBytes)
                buffer.copyInto(header, destinationOffset = headerBytes, endIndex = copyCount)
                headerBytes += copyCount
            }
            digest.update(buffer, 0, count)
        }
        val rawPageSize = ((header[16].toInt() and 0xff) shl 8) or (header[17].toInt() and 0xff)
        val pageSize = if (rawPageSize == 1) 65_536 else rawPageSize
        val validPageSize = pageSize == 65_536 ||
            (pageSize in 512..32_768 && pageSize and (pageSize - 1) == 0)
        val validJournalVersions = (header[18].toInt() and 0xff) in 1..2 &&
            (header[19].toInt() and 0xff) in 1..2
        if (headerBytes != RESTORE_SQLITE_HEADER_BYTES ||
            !header.copyOfRange(0, RESTORE_SQLITE_MAGIC.size).contentEquals(RESTORE_SQLITE_MAGIC) ||
            !validPageSize ||
            !validJournalVersions ||
            total % pageSize != 0L
        ) {
            throw InvalidMainDatabaseException()
        }
        return DatabaseScan(total, digest.digest().toHex())
    }

    private fun parseManifest(bytes: ByteArray): ManifestContract? = runCatching {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val root = Json.parseToJsonElement(decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString())
            .jsonObject
        val database = root["entries"]?.jsonObject?.get(RESTORE_MAIN_DATABASE_ENTRY)?.jsonObject
        ManifestContract(
            formatVersion = root["formatVersion"]?.jsonPrimitive?.intOrNull ?: return null,
            learningDbExcluded = root["learningDbExcluded"]?.jsonPrimitive?.booleanOrNull ?: false,
            databaseSha256 = database?.get("sha256")?.jsonPrimitive?.content
                ?.takeIf { it.isSha256() },
            databaseSizeBytes = database?.get("size")?.jsonPrimitive?.longOrNull
                ?.takeIf { it >= RESTORE_SQLITE_HEADER_BYTES },
        )
    }.getOrNull()

    private fun readBounded(
        input: ZipInputStream,
        limit: Long,
        tooLarge: () -> RuntimeException,
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8_192L).toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total = Math.addExact(total, count.toLong())
            if (total > limit) throw tooLarge()
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isSafeEntryName(name: String, directory: Boolean): Boolean {
        if (name.isBlank() || name.indexOf('\u0000') >= 0 || '\\' in name || name.startsWith('/')) {
            return false
        }
        if (WINDOWS_ABSOLUTE.matches(name)) return false
        val normalized = if (directory) name.removeSuffix("/") else name
        if (normalized.isBlank()) return false
        val segments = normalized.split('/')
        return segments.none { it.isBlank() || it == "." || it == ".." }
    }

    private data class ArchiveScan(
        val manifestBytes: ByteArray?,
        val database: DatabaseScan?,
    )

    private data class DatabaseScan(val sizeBytes: Long, val sha256: String)

    private data class ManifestContract(
        val formatVersion: Int,
        val learningDbExcluded: Boolean,
        val databaseSha256: String?,
        val databaseSizeBytes: Long?,
    )

    private class ManifestTooLargeException : RuntimeException()

    private class MainDatabaseTooLargeException : RuntimeException()

    private class UnsafeEntryException : RuntimeException()

    private class DuplicateEntryException : RuntimeException()

    private class InvalidMainDatabaseException : RuntimeException()

    private companion object {
        const val DEFAULT_MAX_MANIFEST_BYTES = 64 * 1_024
        const val DEFAULT_MAX_MAIN_DATABASE_BYTES = 8L * 1_024L * 1_024L * 1_024L
        val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:.*")
    }
}

private fun rejected(failure: LearningRestorePreflightFailure) =
    LearningRestorePreflight.Rejected(failure)

private fun String.isSha256(): Boolean = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance(SHA_256_ALGORITHM)
    FileInputStream(file).buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    return digest.digest().toHex()
}

private fun constantTimeDigestEquals(expected: String, actual: String): Boolean {
    if (!expected.isSha256() || !actual.isSha256()) return false
    return MessageDigest.isEqual(expected.hexToBytes(), actual.hexToBytes())
}

private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
    substring(index * 2, index * 2 + 2).toInt(16).toByte()
}
