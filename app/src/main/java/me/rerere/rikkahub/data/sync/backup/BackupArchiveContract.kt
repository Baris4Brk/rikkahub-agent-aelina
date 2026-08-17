package me.rerere.rikkahub.data.sync.backup

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val BACKUP_ARCHIVE_FORMAT_VERSION: Int = 1
const val BACKUP_ARCHIVE_MANIFEST_ENTRY: String = "backup_manifest.json"
/**
 * The single whole-AppDatabase snapshot. Content-free policy-grant heads and their revision
 * journal travel only inside this entry; they are never duplicated into settings or file entries.
 * The manifest never claims that a grant is rebound: after LearningDatabase rebuild, runtime
 * eligibility still requires an exact stream/scope/consumer/policy-revision/artifact match.
 */
const val BACKUP_ARCHIVE_MAIN_DATABASE_ENTRY: String = "rikka_hub.db"
const val BACKUP_ARCHIVE_SETTINGS_ENTRY: String = "settings.json"

internal const val MAX_BACKUP_ARCHIVE_BYTES: Long = 16L * 1_024L * 1_024L * 1_024L
internal const val MAX_BACKUP_MAIN_DATABASE_BYTES: Long = 8L * 1_024L * 1_024L * 1_024L

private const val MAX_MANIFEST_BYTES = 1_048_576
private const val MAX_MANIFEST_ENTRIES = 4_096
private const val MAX_ENTRY_NAME_CHARS = 512
private const val MAX_ENTRY_SEGMENT_CHARS = 128
private const val MAX_SETTINGS_BYTES = 4L * 1_024L * 1_024L
private const val MAX_FILE_ENTRY_BYTES = 2L * 1_024L * 1_024L * 1_024L
private const val MIN_SQLITE_DATABASE_BYTES = 100L

@Serializable
enum class BackupArchiveComponent {
    DATABASE,
    SETTINGS,
    FILES,
}

@Serializable
data class BackupArchiveEntryV1(
    val size: Long,
    val sha256: String,
)

@Serializable
data class BackupAuthorityStreamV1(
    val streamId: String,
    val headSeq: Long,
)

/**
 * Versioned, content-free contract shared by every backup transport.
 *
 * Security-critical fields intentionally have no serialization defaults. An omitted
 * `learningDbExcluded`, stream identity, component list, or entry map is malformed rather than
 * silently upgraded to a permissive value.
 */
@Serializable
data class BackupArchiveManifestV1(
    val formatVersion: Int,
    val learningDbExcluded: Boolean,
    val components: List<BackupArchiveComponent>,
    val mainStream: BackupAuthorityStreamV1?,
    val entries: Map<String, BackupArchiveEntryV1>,
)

enum class BackupArchiveManifestFailure {
    EMPTY_PAYLOAD,
    PAYLOAD_TOO_LARGE,
    INVALID_UTF8,
    MALFORMED_JSON,
    FORMAT_VERSION_UNSUPPORTED,
    LEARNING_EXCLUSION_NOT_DECLARED,
    COMPONENTS_EMPTY,
    DUPLICATE_COMPONENT,
    DATABASE_ENTRY_MISSING,
    DATABASE_ENTRY_NOT_SELECTED,
    SETTINGS_ENTRY_MISSING,
    SETTINGS_ENTRY_NOT_SELECTED,
    FILE_ENTRY_NOT_SELECTED,
    MAIN_STREAM_MISSING,
    MAIN_STREAM_UNEXPECTED,
    MAIN_STREAM_ID_INVALID,
    MAIN_STREAM_HEAD_INVALID,
    TOO_MANY_ENTRIES,
    UNSAFE_ENTRY_NAME,
    UNSUPPORTED_ENTRY,
    ENTRY_SIZE_INVALID,
    ENTRY_TOO_LARGE,
    TOTAL_SIZE_TOO_LARGE,
    ENTRY_CHECKSUM_INVALID,
}

sealed interface BackupArchiveManifestDecodeResult {
    data class Verified(val manifest: BackupArchiveManifestV1) :
        BackupArchiveManifestDecodeResult

    data class Rejected(val failure: BackupArchiveManifestFailure) :
        BackupArchiveManifestDecodeResult
}

/** Strict and bounded manifest codec. It never logs or includes archive contents in failures. */
object BackupArchiveManifestCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    fun encode(manifest: BackupArchiveManifestV1): ByteArray {
        val canonical = manifest.copy(
            components = manifest.components.sortedBy { it.ordinal },
            entries = manifest.entries.toSortedMap(),
        )
        val failure = validate(canonical)
        require(failure == null) { "Invalid backup manifest: ${failure?.name}" }
        val bytes = json.encodeToString(canonical).toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_MANIFEST_BYTES) { "Backup manifest exceeds its size limit" }
        return bytes
    }

    fun decode(bytes: ByteArray): BackupArchiveManifestDecodeResult {
        if (bytes.isEmpty()) {
            return rejected(BackupArchiveManifestFailure.EMPTY_PAYLOAD)
        }
        if (bytes.size > MAX_MANIFEST_BYTES) {
            return rejected(BackupArchiveManifestFailure.PAYLOAD_TOO_LARGE)
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            return rejected(BackupArchiveManifestFailure.INVALID_UTF8)
        }
        val manifest = try {
            json.decodeFromString<BackupArchiveManifestV1>(text)
        } catch (_: Exception) {
            return rejected(BackupArchiveManifestFailure.MALFORMED_JSON)
        }
        return validate(manifest)?.let(::rejected)
            ?: BackupArchiveManifestDecodeResult.Verified(manifest)
    }

    fun validate(manifest: BackupArchiveManifestV1): BackupArchiveManifestFailure? {
        if (manifest.formatVersion != BACKUP_ARCHIVE_FORMAT_VERSION) {
            return BackupArchiveManifestFailure.FORMAT_VERSION_UNSUPPORTED
        }
        if (!manifest.learningDbExcluded) {
            return BackupArchiveManifestFailure.LEARNING_EXCLUSION_NOT_DECLARED
        }
        if (manifest.components.isEmpty()) {
            return BackupArchiveManifestFailure.COMPONENTS_EMPTY
        }
        if (manifest.components.distinct().size != manifest.components.size) {
            return BackupArchiveManifestFailure.DUPLICATE_COMPONENT
        }
        if (manifest.entries.size > MAX_MANIFEST_ENTRIES) {
            return BackupArchiveManifestFailure.TOO_MANY_ENTRIES
        }

        val databaseSelected = BackupArchiveComponent.DATABASE in manifest.components
        val settingsSelected = BackupArchiveComponent.SETTINGS in manifest.components
        val filesSelected = BackupArchiveComponent.FILES in manifest.components
        val databaseEntry = manifest.entries[BACKUP_ARCHIVE_MAIN_DATABASE_ENTRY]
        val settingsEntry = manifest.entries[BACKUP_ARCHIVE_SETTINGS_ENTRY]

        if (databaseSelected && databaseEntry == null) {
            return BackupArchiveManifestFailure.DATABASE_ENTRY_MISSING
        }
        if (!databaseSelected && databaseEntry != null) {
            return BackupArchiveManifestFailure.DATABASE_ENTRY_NOT_SELECTED
        }
        if (settingsSelected && settingsEntry == null) {
            return BackupArchiveManifestFailure.SETTINGS_ENTRY_MISSING
        }
        if (!settingsSelected && settingsEntry != null) {
            return BackupArchiveManifestFailure.SETTINGS_ENTRY_NOT_SELECTED
        }

        if (databaseSelected) {
            val stream = manifest.mainStream
                ?: return BackupArchiveManifestFailure.MAIN_STREAM_MISSING
            if (!isCanonicalStreamId(stream.streamId)) {
                return BackupArchiveManifestFailure.MAIN_STREAM_ID_INVALID
            }
            if (stream.headSeq <= 0L) {
                return BackupArchiveManifestFailure.MAIN_STREAM_HEAD_INVALID
            }
        } else if (manifest.mainStream != null) {
            return BackupArchiveManifestFailure.MAIN_STREAM_UNEXPECTED
        }

        var totalSize = 0L
        for ((name, entry) in manifest.entries) {
            if (!isSafeBackupEntryName(name)) {
                return BackupArchiveManifestFailure.UNSAFE_ENTRY_NAME
            }
            val component = componentForEntry(name)
                ?: return BackupArchiveManifestFailure.UNSUPPORTED_ENTRY
            if (component == BackupArchiveComponent.FILES && !filesSelected) {
                return BackupArchiveManifestFailure.FILE_ENTRY_NOT_SELECTED
            }
            if (entry.size < 0L) {
                return BackupArchiveManifestFailure.ENTRY_SIZE_INVALID
            }
            val entryLimit = when (component) {
                BackupArchiveComponent.DATABASE -> MAX_BACKUP_MAIN_DATABASE_BYTES
                BackupArchiveComponent.SETTINGS -> MAX_SETTINGS_BYTES
                BackupArchiveComponent.FILES -> MAX_FILE_ENTRY_BYTES
            }
            if (entry.size > entryLimit ||
                (component == BackupArchiveComponent.DATABASE &&
                    entry.size < MIN_SQLITE_DATABASE_BYTES)
            ) {
                return BackupArchiveManifestFailure.ENTRY_TOO_LARGE
            }
            if (!isCanonicalBackupSha256(entry.sha256)) {
                return BackupArchiveManifestFailure.ENTRY_CHECKSUM_INVALID
            }
            totalSize = try {
                Math.addExact(totalSize, entry.size)
            } catch (_: ArithmeticException) {
                return BackupArchiveManifestFailure.TOTAL_SIZE_TOO_LARGE
            }
            if (totalSize > MAX_BACKUP_ARCHIVE_BYTES) {
                return BackupArchiveManifestFailure.TOTAL_SIZE_TOO_LARGE
            }
        }
        return null
    }
}

internal fun isCanonicalBackupSha256(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

internal fun isCanonicalStreamId(value: String): Boolean = runCatching {
    value != NIL_UUID && UUID.fromString(value).toString() == value
}.getOrDefault(false)

internal fun isSafeBackupEntryName(name: String): Boolean {
    if (name.isBlank() || name.length > MAX_ENTRY_NAME_CHARS || '\u0000' in name ||
        '\\' in name || name.startsWith('/') || WINDOWS_ABSOLUTE.matches(name)
    ) {
        return false
    }
    val segments = name.split('/')
    return segments.all { segment ->
        segment.isNotBlank() && segment != "." && segment != ".." &&
            segment.length <= MAX_ENTRY_SEGMENT_CHARS
    }
}

private fun componentForEntry(name: String): BackupArchiveComponent? = when {
    name == BACKUP_ARCHIVE_MAIN_DATABASE_ENTRY -> BackupArchiveComponent.DATABASE
    name == BACKUP_ARCHIVE_SETTINGS_ENTRY -> BackupArchiveComponent.SETTINGS
    name.startsWith("upload/") && name.count { it == '/' } == 1 ->
        BackupArchiveComponent.FILES
    name.startsWith("fonts/") && name.count { it == '/' } == 1 ->
        BackupArchiveComponent.FILES
    name.startsWith("skills/") && name.count { it == '/' } >= 2 ->
        BackupArchiveComponent.FILES
    else -> null
}

private fun rejected(failure: BackupArchiveManifestFailure) =
    BackupArchiveManifestDecodeResult.Rejected(failure)

private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:.*")
private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
