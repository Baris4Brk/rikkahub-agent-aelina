package me.rerere.rikkahub.data.sync.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val SHA_256 = "SHA-256"
private const val MAX_MANIFEST_FILE_BYTES = 1_048_576
private const val MAX_ARCHIVE_ENTRY_COUNT = 4_097

enum class BackupArchiveFileFailure {
    ARCHIVE_PATH_UNSAFE,
    ARCHIVE_TOO_LARGE,
    ARCHIVE_CHANGED,
    MANIFEST_NOT_FIRST,
    MANIFEST_REJECTED,
    UNSAFE_ENTRY,
    DUPLICATE_ENTRY,
    UNDECLARED_ENTRY,
    DECLARED_ENTRY_MISSING,
    ENTRY_IDENTITY_MISMATCH,
    SOURCE_PATH_UNSAFE,
    DESTINATION_PATH_UNSAFE,
    ATOMIC_MOVE_UNSUPPORTED,
    IO_FAILED,
}

class BackupArchiveFileException(
    val failure: BackupArchiveFileFailure,
    cause: Throwable? = null,
) : Exception(failure.name, cause)

enum class BackupArchiveOrigin {
    MANIFEST_V1,
    /** Historical app-created ZIP. Its stream was not declared and must be proven or regenerated. */
    LEGACY_V0,
}

sealed interface BackupArchiveSourceV1 {
    val name: String

    data class Bytes(
        override val name: String,
        val bytes: ByteArray,
    ) : BackupArchiveSourceV1

    data class FileSource(
        override val name: String,
        val file: File,
    ) : BackupArchiveSourceV1
}

/** A byte-verified archive identity. Paths and payloads are deliberately absent from toString. */
class VerifiedBackupArchiveV1 internal constructor(
    internal val archivePath: Path,
    val archiveSize: Long,
    val archiveSha256: String,
    val manifest: BackupArchiveManifestV1,
    val origin: BackupArchiveOrigin,
) {
    val archiveFile: File get() = archivePath.toFile()

    override fun toString(): String =
        "VerifiedBackupArchiveV1(origin=$origin, bytes=$archiveSize, " +
            "archive=<redacted>, digest=<redacted>)"
}

/**
 * Shared v1 writer/reader used by every backup transport.
 *
 * The manifest is always the first ZIP entry. Every later entry is bounded by its manifest size
 * while being read, and its digest is checked before the archive is accepted. The writer hashes
 * file sources both before and while writing so a changing source can never produce a manifest
 * that authenticates different bytes.
 */
object BackupArchiveV1FileIO {
    @Throws(BackupArchiveFileException::class)
    fun write(
        destination: File,
        components: Set<BackupArchiveComponent>,
        mainStream: BackupAuthorityStreamV1?,
        sources: List<BackupArchiveSourceV1>,
    ): BackupArchiveManifestV1 {
        val destinationPath = destination.toPath().toAbsolutePath().normalize()
        val parent = destinationPath.parent
            ?: throw failure(BackupArchiveFileFailure.DESTINATION_PATH_UNSAFE)
        requireSafeDirectory(parent, BackupArchiveFileFailure.DESTINATION_PATH_UNSAFE)
        if (destination.path.isBlank() || !destination.isAbsolute ||
            Files.isSymbolicLink(destinationPath) ||
            Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw failure(BackupArchiveFileFailure.DESTINATION_PATH_UNSAFE)
        }
        if (sources.isEmpty() || sources.map { it.name }.distinct().size != sources.size) {
            throw failure(BackupArchiveFileFailure.MANIFEST_REJECTED)
        }

        val sortedSources = sources.sortedBy { it.name }
        val entries = linkedMapOf<String, BackupArchiveEntryV1>()
        for (source in sortedSources) {
            if (!isSafeBackupEntryName(source.name) ||
                source.name == BACKUP_ARCHIVE_MANIFEST_ENTRY
            ) {
                throw failure(BackupArchiveFileFailure.UNSAFE_ENTRY)
            }
            entries[source.name] = identityOf(source)
        }
        val manifest = BackupArchiveManifestV1(
            formatVersion = BACKUP_ARCHIVE_FORMAT_VERSION,
            learningDbExcluded = true,
            components = components.sortedBy { it.ordinal },
            mainStream = mainStream,
            entries = entries,
        )
        val manifestBytes = try {
            BackupArchiveManifestCodec.encode(manifest)
        } catch (error: Exception) {
            throw failure(BackupArchiveFileFailure.MANIFEST_REJECTED, error)
        }

        val temporary = try {
            Files.createTempFile(parent, ".backup_archive_", ".part")
        } catch (error: Exception) {
            throw failure(BackupArchiveFileFailure.IO_FAILED, error)
        }
        try {
            ZipOutputStream(FileOutputStream(temporary.toFile()).buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(BACKUP_ARCHIVE_MANIFEST_ENTRY))
                zip.write(manifestBytes)
                zip.closeEntry()

                for (source in sortedSources) {
                    zip.putNextEntry(ZipEntry(source.name))
                    val actual = writeSourceAndMeasure(source, zip)
                    zip.closeEntry()
                    if (actual != entries[source.name]) {
                        throw failure(BackupArchiveFileFailure.ARCHIVE_CHANGED)
                    }
                }
            }
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            if (Files.size(temporary) <= 0L || Files.size(temporary) > MAX_BACKUP_ARCHIVE_BYTES) {
                throw failure(BackupArchiveFileFailure.ARCHIVE_TOO_LARGE)
            }
            try {
                Files.move(temporary, destinationPath, StandardCopyOption.ATOMIC_MOVE)
            } catch (error: AtomicMoveNotSupportedException) {
                throw failure(BackupArchiveFileFailure.ATOMIC_MOVE_UNSUPPORTED, error)
            }
            return manifest
        } catch (error: BackupArchiveFileException) {
            throw error
        } catch (error: Exception) {
            throw failure(BackupArchiveFileFailure.IO_FAILED, error)
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    @Throws(BackupArchiveFileException::class)
    fun inspect(archive: File): VerifiedBackupArchiveV1 {
        val archivePath = requireSafeArchive(archive)
        val sizeBefore = safeSize(archivePath)
        if (sizeBefore <= 0L || sizeBefore > MAX_BACKUP_ARCHIVE_BYTES) {
            throw failure(BackupArchiveFileFailure.ARCHIVE_TOO_LARGE)
        }
        val digestBefore = sha256(archivePath)
        val manifest = scanAndVerifyArchive(archivePath)
        val sizeAfter = safeSize(archivePath)
        val digestAfter = sha256(archivePath)
        if (sizeAfter != sizeBefore || !constantTimeHexEquals(digestBefore, digestAfter)) {
            throw failure(BackupArchiveFileFailure.ARCHIVE_CHANGED)
        }
        return VerifiedBackupArchiveV1(
            archivePath = archivePath,
            archiveSize = sizeAfter,
            archiveSha256 = digestAfter,
            manifest = manifest,
            origin = BackupArchiveOrigin.MANIFEST_V1,
        )
    }

    /**
     * Restore-only compatibility entry. A ZIP whose first item is the v1 manifest is never
     * downgraded to legacy parsing when that manifest is malformed. A manifest-less archive is
     * accepted only by the narrow historical allowlist in [scanAndVerifyLegacyArchive].
     */
    @Throws(BackupArchiveFileException::class)
    fun inspectForRestore(archive: File): VerifiedBackupArchiveV1 {
        val path = requireSafeArchive(archive)
        val firstName = try {
            ZipInputStream(FileInputStream(path.toFile()).buffered()).use { it.nextEntry?.name }
        } catch (error: Exception) {
            throw failure(BackupArchiveFileFailure.IO_FAILED, error)
        }
        if (firstName == BACKUP_ARCHIVE_MANIFEST_ENTRY) return inspect(archive)

        val sizeBefore = safeSize(path)
        if (sizeBefore <= 0L || sizeBefore > MAX_BACKUP_ARCHIVE_BYTES) {
            throw failure(BackupArchiveFileFailure.ARCHIVE_TOO_LARGE)
        }
        val digestBefore = sha256(path)
        val legacyManifest = scanAndVerifyLegacyArchive(path)
        val sizeAfter = safeSize(path)
        val digestAfter = sha256(path)
        if (sizeBefore != sizeAfter || !constantTimeHexEquals(digestBefore, digestAfter)) {
            throw failure(BackupArchiveFileFailure.ARCHIVE_CHANGED)
        }
        return VerifiedBackupArchiveV1(
            archivePath = path,
            archiveSize = sizeAfter,
            archiveSha256 = digestAfter,
            manifest = legacyManifest,
            origin = BackupArchiveOrigin.LEGACY_V0,
        )
    }

    /** Reads one already-authenticated small entry and rechecks archive identity around the read. */
    @Throws(BackupArchiveFileException::class)
    fun readSmallEntry(
        archive: VerifiedBackupArchiveV1,
        name: String,
        maxBytes: Int,
    ): ByteArray {
        require(maxBytes > 0)
        val declared = archive.manifest.entries[name]
            ?: throw failure(BackupArchiveFileFailure.DECLARED_ENTRY_MISSING)
        if (declared.size > maxBytes.toLong()) {
            throw failure(BackupArchiveFileFailure.ENTRY_IDENTITY_MISMATCH)
        }
        requireArchiveIdentity(archive)
        var found: ByteArray? = null
        ZipInputStream(FileInputStream(archive.archiveFile).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) {
                    if (found != null || entry.isDirectory) {
                        throw failure(BackupArchiveFileFailure.DUPLICATE_ENTRY)
                    }
                    val bytes = readBounded(zip, declared.size, maxBytes.toLong())
                    if (bytes.size.toLong() != declared.size ||
                        !constantTimeHexEquals(declared.sha256, sha256(bytes))
                    ) {
                        throw failure(BackupArchiveFileFailure.ENTRY_IDENTITY_MISMATCH)
                    }
                    found = bytes
                }
                zip.closeEntry()
            }
        }
        requireArchiveIdentity(archive)
        return found ?: throw failure(BackupArchiveFileFailure.DECLARED_ENTRY_MISSING)
    }

    /**
     * Replays only FILES entries to caller-resolved exact targets. Each target is written through
     * a same-directory temporary file and atomically replaced; no recursive deletion is used.
     */
    @Throws(BackupArchiveFileException::class)
    fun restoreFileEntries(
        archive: VerifiedBackupArchiveV1,
        targetForEntry: (String) -> File?,
    ) {
        if (BackupArchiveComponent.FILES !in archive.manifest.components) return
        requireArchiveIdentity(archive)
        val restored = mutableSetOf<String>()
        ZipInputStream(FileInputStream(archive.archiveFile).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val declared = archive.manifest.entries[entry.name]
                if (declared != null && isFilePayloadEntry(entry.name)) {
                    if (!restored.add(entry.name)) {
                        throw failure(BackupArchiveFileFailure.DUPLICATE_ENTRY)
                    }
                    val target = targetForEntry(entry.name)
                        ?: throw failure(BackupArchiveFileFailure.DESTINATION_PATH_UNSAFE)
                    restoreOneEntry(zip, declared, target)
                }
                zip.closeEntry()
            }
        }
        val expected = archive.manifest.entries.keys.filter(::isFilePayloadEntry).toSet()
        if (restored != expected) {
            throw failure(BackupArchiveFileFailure.DECLARED_ENTRY_MISSING)
        }
        requireArchiveIdentity(archive)
    }

    /** Extracts one exact authenticated entry through a same-directory atomic move. */
    @Throws(BackupArchiveFileException::class)
    fun extractEntry(
        archive: VerifiedBackupArchiveV1,
        name: String,
        destination: File,
    ) {
        val declared = archive.manifest.entries[name]
            ?: throw failure(BackupArchiveFileFailure.DECLARED_ENTRY_MISSING)
        if (destination.exists() || Files.isSymbolicLink(destination.toPath())) {
            throw failure(BackupArchiveFileFailure.DESTINATION_PATH_UNSAFE)
        }
        requireArchiveIdentity(archive)
        var found = false
        ZipInputStream(FileInputStream(archive.archiveFile).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == name) {
                    if (found || entry.isDirectory) {
                        throw failure(BackupArchiveFileFailure.DUPLICATE_ENTRY)
                    }
                    restoreOneEntry(zip, declared, destination)
                    found = true
                }
                zip.closeEntry()
            }
        }
        if (!found) throw failure(BackupArchiveFileFailure.DECLARED_ENTRY_MISSING)
        requireArchiveIdentity(archive)
    }

    private fun scanAndVerifyArchive(path: Path): BackupArchiveManifestV1 {
        var manifest: BackupArchiveManifestV1? = null
        val seen = mutableSetOf<String>()
        var entryCount = 0
        ZipInputStream(FileInputStream(path.toFile()).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount = Math.addExact(entryCount, 1)
                if (entryCount > MAX_ARCHIVE_ENTRY_COUNT || entry.isDirectory ||
                    entry.name.endsWith('/') || !isSafeBackupEntryName(entry.name)
                ) {
                    throw failure(BackupArchiveFileFailure.UNSAFE_ENTRY)
                }
                if (!seen.add(entry.name)) {
                    throw failure(BackupArchiveFileFailure.DUPLICATE_ENTRY)
                }
                if (entryCount == 1) {
                    if (entry.name != BACKUP_ARCHIVE_MANIFEST_ENTRY) {
                        throw failure(BackupArchiveFileFailure.MANIFEST_NOT_FIRST)
                    }
                    val manifestBytes = readBounded(
                        zip,
                        MAX_MANIFEST_FILE_BYTES.toLong(),
                        MAX_MANIFEST_FILE_BYTES.toLong(),
                    )
                    manifest = when (val decoded = BackupArchiveManifestCodec.decode(manifestBytes)) {
                        is BackupArchiveManifestDecodeResult.Rejected ->
                            throw failure(BackupArchiveFileFailure.MANIFEST_REJECTED)
                        is BackupArchiveManifestDecodeResult.Verified -> decoded.manifest
                    }
                } else {
                    val verifiedManifest = manifest
                        ?: throw failure(BackupArchiveFileFailure.MANIFEST_NOT_FIRST)
                    val declared = verifiedManifest.entries[entry.name]
                        ?: throw failure(BackupArchiveFileFailure.UNDECLARED_ENTRY)
                    val actual = scanEntry(zip, declared.size)
                    if (actual != declared) {
                        throw failure(BackupArchiveFileFailure.ENTRY_IDENTITY_MISMATCH)
                    }
                }
                zip.closeEntry()
            }
        }
        val verifiedManifest = manifest
            ?: throw failure(BackupArchiveFileFailure.MANIFEST_NOT_FIRST)
        val payloadNames = seen - BACKUP_ARCHIVE_MANIFEST_ENTRY
        if (payloadNames != verifiedManifest.entries.keys) {
            throw failure(BackupArchiveFileFailure.DECLARED_ENTRY_MISSING)
        }
        return verifiedManifest
    }

    private fun scanAndVerifyLegacyArchive(path: Path): BackupArchiveManifestV1 {
        val entries = linkedMapOf<String, BackupArchiveEntryV1>()
        var count = 0
        var totalPayload = 0L
        ZipInputStream(FileInputStream(path.toFile()).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                count = Math.addExact(count, 1)
                if (count > MAX_ARCHIVE_ENTRY_COUNT || entry.isDirectory || entry.name.endsWith('/') ||
                    !isSafeBackupEntryName(entry.name) || !isAllowedLegacyEntry(entry.name)
                ) {
                    throw failure(BackupArchiveFileFailure.UNSAFE_ENTRY)
                }
                if (entry.name == BACKUP_ARCHIVE_MANIFEST_ENTRY || entry.name in entries) {
                    throw failure(BackupArchiveFileFailure.DUPLICATE_ENTRY)
                }
                val limit = legacyEntryLimit(entry.name)
                val actual = scanEntryBounded(zip, limit)
                entries[entry.name] = actual
                totalPayload = Math.addExact(totalPayload, actual.size)
                if (totalPayload > MAX_BACKUP_ARCHIVE_BYTES) {
                    throw failure(BackupArchiveFileFailure.ARCHIVE_TOO_LARGE)
                }
                zip.closeEntry()
            }
        }
        if (BACKUP_ARCHIVE_SETTINGS_ENTRY !in entries || entries.isEmpty()) {
            throw failure(BackupArchiveFileFailure.DECLARED_ENTRY_MISSING)
        }
        // Historical writer copied sidecars after checkpoint(TRUNCATE). A non-empty WAL means the
        // main file alone is not proven complete and is therefore refused. SHM is coordination
        // metadata only and is ignored after its strict small bound/checksum scan.
        if ((entries[LEGACY_MAIN_WAL_ENTRY]?.size ?: 0L) != 0L) {
            throw failure(BackupArchiveFileFailure.ENTRY_IDENTITY_MISMATCH)
        }
        val components = buildList {
            if (BACKUP_ARCHIVE_MAIN_DATABASE_ENTRY in entries) add(BackupArchiveComponent.DATABASE)
            add(BackupArchiveComponent.SETTINGS)
            if (entries.keys.any(::isFilePayloadEntry)) add(BackupArchiveComponent.FILES)
        }
        return BackupArchiveManifestV1(
            formatVersion = BACKUP_ARCHIVE_FORMAT_VERSION,
            learningDbExcluded = true,
            components = components,
            // LEGACY_V0 never asserted an authority stream. The service must prove or create it
            // on a private staged current-v46 file before constructing a canonical v1 archive.
            mainStream = null,
            entries = entries,
        )
    }

    private fun restoreOneEntry(
        input: InputStream,
        declared: BackupArchiveEntryV1,
        destination: File,
    ) {
        if (!destination.isAbsolute || destination.path.isBlank()) {
            throw failure(BackupArchiveFileFailure.DESTINATION_PATH_UNSAFE)
        }
        val destinationPath = destination.toPath().toAbsolutePath().normalize()
        val parent = destinationPath.parent
            ?: throw failure(BackupArchiveFileFailure.DESTINATION_PATH_UNSAFE)
        requireSafeDirectory(parent, BackupArchiveFileFailure.DESTINATION_PATH_UNSAFE)
        if (Files.isSymbolicLink(destinationPath) ||
            (Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isRegularFile(destinationPath, LinkOption.NOFOLLOW_LINKS))
        ) {
            throw failure(BackupArchiveFileFailure.DESTINATION_PATH_UNSAFE)
        }
        val temporary = try {
            Files.createTempFile(parent, ".restore_entry_", ".part")
        } catch (error: Exception) {
            throw failure(BackupArchiveFileFailure.IO_FAILED, error)
        }
        try {
            val digest = MessageDigest.getInstance(SHA_256)
            var total = 0L
            FileOutputStream(temporary.toFile()).buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total = Math.addExact(total, count.toLong())
                    if (total > declared.size) {
                        throw failure(BackupArchiveFileFailure.ENTRY_IDENTITY_MISMATCH)
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
                output.flush()
            }
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            if (total != declared.size ||
                !constantTimeHexEquals(declared.sha256, digest.digest().toHex())
            ) {
                throw failure(BackupArchiveFileFailure.ENTRY_IDENTITY_MISMATCH)
            }
            try {
                Files.move(
                    temporary,
                    destinationPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (error: AtomicMoveNotSupportedException) {
                throw failure(BackupArchiveFileFailure.ATOMIC_MOVE_UNSUPPORTED, error)
            }
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }
}

private fun identityOf(source: BackupArchiveSourceV1): BackupArchiveEntryV1 = when (source) {
    is BackupArchiveSourceV1.Bytes -> BackupArchiveEntryV1(
        size = source.bytes.size.toLong(),
        sha256 = sha256(source.bytes),
    )
    is BackupArchiveSourceV1.FileSource -> {
        val path = requireSafeSourceFile(source.file)
        BackupArchiveEntryV1(size = safeSize(path), sha256 = sha256(path))
    }
}

private fun writeSourceAndMeasure(
    source: BackupArchiveSourceV1,
    output: ZipOutputStream,
): BackupArchiveEntryV1 {
    val digest = MessageDigest.getInstance(SHA_256)
    var total = 0L
    when (source) {
        is BackupArchiveSourceV1.Bytes -> {
            digest.update(source.bytes)
            output.write(source.bytes)
            total = source.bytes.size.toLong()
        }
        is BackupArchiveSourceV1.FileSource -> {
            val path = requireSafeSourceFile(source.file)
            FileInputStream(path.toFile()).buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    total = Math.addExact(total, count.toLong())
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
    }
    return BackupArchiveEntryV1(total, digest.digest().toHex())
}

private fun scanEntry(input: InputStream, declaredSize: Long): BackupArchiveEntryV1 {
    val digest = MessageDigest.getInstance(SHA_256)
    var total = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total = Math.addExact(total, count.toLong())
        if (total > declaredSize) {
            throw failure(BackupArchiveFileFailure.ENTRY_IDENTITY_MISMATCH)
        }
        digest.update(buffer, 0, count)
    }
    return BackupArchiveEntryV1(total, digest.digest().toHex())
}

private fun scanEntryBounded(input: InputStream, hardLimit: Long): BackupArchiveEntryV1 {
    val digest = MessageDigest.getInstance(SHA_256)
    var total = 0L
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total = Math.addExact(total, count.toLong())
        if (total > hardLimit) {
            throw failure(BackupArchiveFileFailure.ENTRY_IDENTITY_MISMATCH)
        }
        digest.update(buffer, 0, count)
    }
    return BackupArchiveEntryV1(total, digest.digest().toHex())
}

private fun readBounded(input: InputStream, declaredSize: Long, hardLimit: Long): ByteArray {
    if (declaredSize < 0L || declaredSize > hardLimit) {
        throw failure(BackupArchiveFileFailure.ENTRY_IDENTITY_MISMATCH)
    }
    val output = ByteArrayOutputStream(minOf(declaredSize, 8_192L).toInt())
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total = Math.addExact(total, count.toLong())
        if (total > declaredSize || total > hardLimit) {
            throw failure(BackupArchiveFileFailure.ENTRY_IDENTITY_MISMATCH)
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun requireSafeArchive(file: File): Path {
    if (!file.isAbsolute || file.path.isBlank()) {
        throw failure(BackupArchiveFileFailure.ARCHIVE_PATH_UNSAFE)
    }
    val path = file.toPath().toAbsolutePath().normalize()
    val canonical = runCatching { file.canonicalFile.toPath().normalize() }.getOrNull()
    if (canonical != path || Files.isSymbolicLink(path) ||
        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)
    ) {
        throw failure(BackupArchiveFileFailure.ARCHIVE_PATH_UNSAFE)
    }
    return path
}

private fun requireSafeSourceFile(file: File): Path {
    if (!file.isAbsolute || file.path.isBlank()) {
        throw failure(BackupArchiveFileFailure.SOURCE_PATH_UNSAFE)
    }
    val path = file.toPath().toAbsolutePath().normalize()
    val canonical = runCatching { file.canonicalFile.toPath().normalize() }.getOrNull()
    if (canonical != path || Files.isSymbolicLink(path) ||
        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)
    ) {
        throw failure(BackupArchiveFileFailure.SOURCE_PATH_UNSAFE)
    }
    return path
}

private fun requireSafeDirectory(path: Path, reason: BackupArchiveFileFailure) {
    val canonical = runCatching { path.toFile().canonicalFile.toPath().normalize() }.getOrNull()
    if (canonical != path || Files.isSymbolicLink(path) ||
        !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
    ) {
        throw failure(reason)
    }
}

private fun requireArchiveIdentity(archive: VerifiedBackupArchiveV1) {
    val path = requireSafeArchive(archive.archiveFile)
    if (safeSize(path) != archive.archiveSize ||
        !constantTimeHexEquals(archive.archiveSha256, sha256(path))
    ) {
        throw failure(BackupArchiveFileFailure.ARCHIVE_CHANGED)
    }
}

private fun safeSize(path: Path): Long = try {
    Files.size(path)
} catch (error: Exception) {
    throw failure(BackupArchiveFileFailure.IO_FAILED, error)
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance(SHA_256)
    FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = channel.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer.array(), 0, count)
            buffer.clear()
        }
    }
    return digest.digest().toHex()
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance(SHA_256).digest(bytes).toHex()

private fun constantTimeHexEquals(expected: String, actual: String): Boolean {
    if (!isCanonicalBackupSha256(expected) || !isCanonicalBackupSha256(actual)) return false
    return MessageDigest.isEqual(expected.hexToBytes(), actual.hexToBytes())
}

private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
    substring(index * 2, index * 2 + 2).toInt(16).toByte()
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private fun isFilePayloadEntry(name: String): Boolean =
    name.startsWith("upload/") || name.startsWith("fonts/") || name.startsWith("skills/")

private fun isAllowedLegacyEntry(name: String): Boolean = when {
    name == BACKUP_ARCHIVE_MAIN_DATABASE_ENTRY -> true
    name == BACKUP_ARCHIVE_SETTINGS_ENTRY -> true
    name == LEGACY_MAIN_WAL_ENTRY -> true
    name == LEGACY_MAIN_SHM_ENTRY -> true
    name.startsWith("upload/") && name.count { it == '/' } == 1 -> true
    name.startsWith("fonts/") && name.count { it == '/' } == 1 -> true
    name.startsWith("skills/") && name.count { it == '/' } >= 2 -> true
    else -> false
}

private fun legacyEntryLimit(name: String): Long = when (name) {
    BACKUP_ARCHIVE_MAIN_DATABASE_ENTRY -> MAX_BACKUP_MAIN_DATABASE_BYTES
    BACKUP_ARCHIVE_SETTINGS_ENTRY -> 4L * 1_024L * 1_024L
    LEGACY_MAIN_WAL_ENTRY -> 0L
    LEGACY_MAIN_SHM_ENTRY -> 1L * 1_024L * 1_024L
    else -> 2L * 1_024L * 1_024L * 1_024L
}

private const val LEGACY_MAIN_WAL_ENTRY = "rikka_hub-wal"
private const val LEGACY_MAIN_SHM_ENTRY = "rikka_hub-shm"

private fun failure(
    reason: BackupArchiveFileFailure,
    cause: Throwable? = null,
) = BackupArchiveFileException(reason, cause)
