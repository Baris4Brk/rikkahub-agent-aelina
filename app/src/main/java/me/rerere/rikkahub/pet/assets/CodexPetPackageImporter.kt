package me.rerere.rikkahub.pet.assets

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import kotlin.uuid.Uuid
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.pet.profile.PetProfileCodec
import me.rerere.rikkahub.pet.profile.ValidatedPetProfile

data class InstalledPetPackage(
    val manifest: CodexPetManifest,
    val directory: File,
    val spritesheet: File,
    val imageInfo: PetImageInfo,
    val profile: ValidatedPetProfile,
)

/** Imports declarative image/JSON-only Codex Pet packages into app-private storage. */
class CodexPetPackageImporter(
    private val petsRoot: File,
    private val imageProbe: PetImageProbe,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun import(input: InputStream, replaceExisting: Boolean = false): InstalledPetPackage {
        petsRoot.mkdirs()
        val staging = File(petsRoot, ".staging-${Uuid.random()}")
        check(staging.mkdir()) { "pet_staging_unavailable" }
        try {
            extractSafely(input, staging)
            val contentRoot = resolveContentRoot(staging)
            val manifestFile = File(contentRoot, MANIFEST_NAME)
            if (!manifestFile.isFile) throw PetPackageException("pet_manifest_missing")
            val rawManifest = manifestFile.readBytes()
            if (rawManifest.size > MAX_JSON_BYTES) throw PetPackageException("pet_manifest_too_large")
            val manifest = runCatching {
                json.decodeFromString<CodexPetManifest>(rawManifest.decodeUtf8BomAware())
            }.getOrElse { throw PetPackageException("pet_manifest_invalid") }
            validateManifest(manifest)

            val spritesheet = safeChild(contentRoot, manifest.resolvedSpritesheetPath)
            if (!spritesheet.isFile) throw PetPackageException("pet_spritesheet_missing")
            val image = imageProbe.inspect(spritesheet)
                ?: throw PetPackageException("pet_spritesheet_decode_failed")
            val version = manifest.resolvedVersion
            if (image.width != CODEX_FRAME_WIDTH * CODEX_ATLAS_COLUMNS ||
                image.height != CODEX_FRAME_HEIGHT * version.expectedRows()
            ) {
                throw PetPackageException("pet_spritesheet_dimensions_invalid")
            }
            val profileFile = File(contentRoot, PetProfileCodec.PROFILE_FILE_NAME)
            val profile = if (profileFile.isFile) {
                val rawProfile = profileFile.readBytes()
                if (rawProfile.size > MAX_PROFILE_BYTES) throw PetPackageException("pet_profile_too_large")
                PetProfileCodec.decodeAndValidate(
                    raw = rawProfile.decodeUtf8BomAware(),
                    manifest = manifest,
                    packageRoot = contentRoot,
                    imageProbe = imageProbe,
                )
            } else {
                PetProfileCodec.defaultProfile(manifest)
            }

            val target = File(petsRoot, manifest.id)
            if (target.exists() && !replaceExisting) throw PetPackageException("pet_id_exists")
            val installSource = if (contentRoot == staging) staging else contentRoot
            val backup = File(petsRoot, ".backup-${manifest.id}-${Uuid.random()}")
            if (target.exists() && !target.renameTo(backup)) {
                throw PetPackageException("pet_replace_backup_failed")
            }
            try {
                moveDirectory(installSource, target)
                File(target, INTERNAL_METADATA_NAME).writeText(
                    json.encodeToString(
                        InternalPetMetadata(
                            formatVersion = version.name,
                            width = image.width,
                            height = image.height,
                        ),
                    ),
                )
                backup.deleteRecursively()
            } catch (failure: Throwable) {
                target.deleteRecursively()
                if (backup.exists()) backup.renameTo(target)
                throw failure
            }
            return InstalledPetPackage(
                manifest = manifest,
                directory = target,
                spritesheet = safeChild(target, manifest.resolvedSpritesheetPath),
                imageInfo = image,
                profile = profile,
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extractSafely(input: InputStream, staging: File) {
        var files = 0
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                files += 1
                if (files > MAX_FILES) throw PetPackageException("pet_zip_too_many_files")
                val normalized = normalizeEntryName(entry.name)
                val extension = normalized.substringAfterLast('.', "").lowercase()
                if (extension !in ALLOWED_EXTENSIONS) throw PetPackageException("pet_zip_file_type_forbidden")
                val destination = safeChild(staging, normalized)
                destination.parentFile?.mkdirs()
                var entryBytes = 0L
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        total += read
                        if (entryBytes > MAX_SINGLE_FILE_BYTES) {
                            throw PetPackageException("pet_zip_entry_too_large")
                        }
                        if (total > MAX_TOTAL_BYTES) throw PetPackageException("pet_zip_too_large")
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        if (files == 0) throw PetPackageException("pet_zip_empty")
    }

    private fun resolveContentRoot(staging: File): File {
        if (File(staging, MANIFEST_NAME).isFile) return staging
        val children = staging.listFiles()?.filterNot { it.name.startsWith(".") }.orEmpty()
        if (children.size == 1 && children.single().isDirectory &&
            File(children.single(), MANIFEST_NAME).isFile
        ) return children.single()
        throw PetPackageException("pet_package_layout_invalid")
    }

    private fun validateManifest(manifest: CodexPetManifest) {
        if (!SAFE_PET_ID.matches(manifest.id)) throw PetPackageException("pet_manifest_id_invalid")
        if (manifest.displayName.isBlank() || manifest.displayName.codePointCount(0, manifest.displayName.length) > 80) {
            throw PetPackageException("pet_manifest_name_invalid")
        }
        manifest.resolvedVersion
        normalizeEntryName(manifest.resolvedSpritesheetPath)
        val extension = manifest.resolvedSpritesheetPath.substringAfterLast('.', "").lowercase()
        if (extension !in IMAGE_EXTENSIONS) throw PetPackageException("pet_spritesheet_type_invalid")
    }

    private fun safeChild(root: File, relative: String): File {
        val child = File(root, normalizeEntryName(relative)).canonicalFile
        val canonicalRoot = root.canonicalFile
        if (child.path != canonicalRoot.path && !child.path.startsWith(canonicalRoot.path + File.separator)) {
            throw PetPackageException("pet_zip_path_escape")
        }
        return child
    }

    private fun normalizeEntryName(name: String): String {
        val clean = name.replace('\\', '/')
        if (clean.startsWith('/') || DRIVE_PREFIX.containsMatchIn(clean)) {
            throw PetPackageException("pet_zip_absolute_path")
        }
        val parts = clean.split('/').filter(String::isNotEmpty)
        if (parts.isEmpty() || parts.any { it == "." || it == ".." }) {
            throw PetPackageException("pet_zip_path_invalid")
        }
        return parts.joinToString(File.separator)
    }

    private fun moveDirectory(source: File, target: File) {
        if (source.renameTo(target)) return
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun ByteArray.decodeUtf8BomAware(): String {
        val offset = if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() &&
            this[2] == 0xBF.toByte()
        ) 3 else 0
        return decodeToString(offset, size)
    }

    @kotlinx.serialization.Serializable
    private data class InternalPetMetadata(
        val formatVersion: String,
        val width: Int,
        val height: Int,
    )

    private companion object {
        const val MANIFEST_NAME = "pet.json"
        const val INTERNAL_METADATA_NAME = "internal-metadata.json"
        const val MAX_FILES = 32
        const val MAX_JSON_BYTES = 512 * 1024
        const val MAX_PROFILE_BYTES = 128 * 1024
        const val MAX_SINGLE_FILE_BYTES = 20L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
        val DRIVE_PREFIX = Regex("^[a-zA-Z]:")
        val IMAGE_EXTENSIONS = setOf("png", "webp")
        val ALLOWED_EXTENSIONS = IMAGE_EXTENSIONS + "json"
    }
}
