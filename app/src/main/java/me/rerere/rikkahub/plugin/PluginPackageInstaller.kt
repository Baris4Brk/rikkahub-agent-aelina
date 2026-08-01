package me.rerere.rikkahub.plugin

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class PluginReviewStatus {
    NEEDS_REVIEW,
    APPROVED,
    QUARANTINED,
}

@Serializable
data class InstalledPluginRecord(
    val id: String,
    val name: String,
    val version: String,
    val manifest: PluginManifestV1,
    val sourceSha256: String,
    val permissions: Set<String>,
    val enabled: Boolean = false,
    val reviewStatus: PluginReviewStatus = PluginReviewStatus.NEEDS_REVIEW,
    val installedAtMs: Long,
    val updatedAtMs: Long,
    val failureTimestampsMs: List<Long> = emptyList(),
    /** Permission expansion awaiting the next explicit review. */
    val pendingAddedPermissions: Set<String> = emptySet(),
)

@Serializable
private data class PluginRegistryDocument(
    val schemaVersion: Int = 1,
    val installationId: String,
    val records: List<InstalledPluginRecord> = emptyList(),
)

data class PluginInstallResult(
    val record: InstalledPluginRecord,
    val addedPermissions: Set<String>,
    val modelToolNames: List<String>,
)

interface PluginRegistryStore {
    fun snapshot(): List<InstalledPluginRecord>
    fun get(pluginId: String): InstalledPluginRecord?
    fun update(pluginId: String, transform: (InstalledPluginRecord) -> InstalledPluginRecord)
    fun upsert(record: InstalledPluginRecord)
    fun remove(pluginId: String): InstalledPluginRecord? =
        throw UnsupportedOperationException("plugin_registry_remove_unsupported")
}

class FilePluginRegistryStore(
    private val root: File,
    markerDirectory: File,
) : PluginRegistryStore {
    private val registryFile = File(root, "registry-v1.json")
    private val markerFile = File(markerDirectory, "plugin-installation-id")
    private val records = linkedMapOf<String, InstalledPluginRecord>()
    private var installationId: String

    init {
        root.mkdirs()
        markerDirectory.mkdirs()
        val restoredDocument = readDocument()
        val existingMarker = markerFile.takeIf(File::isFile)?.readText(Charsets.UTF_8)?.trim()
            ?.takeIf(String::isNotBlank)
        installationId = existingMarker ?: UUID.randomUUID().toString().also(::writeMarker)
        val restored = restoredDocument?.installationId != null &&
            restoredDocument.installationId != installationId
        restoredDocument?.records.orEmpty().forEach { record ->
            records[record.id] = if (restored) {
                record.copy(enabled = false, reviewStatus = PluginReviewStatus.NEEDS_REVIEW)
            } else {
                record
            }
        }
        if (restored) writeLocked()
    }

    @Synchronized
    override fun snapshot(): List<InstalledPluginRecord> = records.values.toList()

    @Synchronized
    override fun get(pluginId: String): InstalledPluginRecord? = records[pluginId]

    @Synchronized
    override fun update(
        pluginId: String,
        transform: (InstalledPluginRecord) -> InstalledPluginRecord,
    ) {
        val current = records[pluginId] ?: error("plugin_not_found")
        val updated = transform(current)
        require(updated.id == pluginId) { "plugin_id_immutable" }
        records[pluginId] = updated
        writeLocked()
    }

    @Synchronized
    override fun upsert(record: InstalledPluginRecord) {
        records[record.id] = record
        writeLocked()
    }

    @Synchronized
    override fun remove(pluginId: String): InstalledPluginRecord? {
        val removed = records.remove(pluginId) ?: return null
        return try {
            writeLocked()
            removed
        } catch (failure: Throwable) {
            records[pluginId] = removed
            throw failure
        }
    }

    private fun readDocument(): PluginRegistryDocument? = runCatching {
        if (!registryFile.isFile) return@runCatching null
        JSON.decodeFromString<PluginRegistryDocument>(registryFile.readText(Charsets.UTF_8))
            .takeIf { it.schemaVersion == 1 }
    }.getOrNull()

    private fun writeMarker(value: String) {
        val temp = File(markerFile.parentFile, "${markerFile.name}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(temp.renameTo(markerFile)) { "plugin_marker_write_failed" }
    }

    private fun writeLocked() {
        val document = PluginRegistryDocument(
            installationId = installationId,
            records = records.values.toList(),
        )
        val temp = File(root, "${registryFile.name}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(JSON.encodeToString(document).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (registryFile.exists() && !registryFile.delete()) {
            temp.delete()
            error("plugin_registry_replace_failed")
        }
        check(temp.renameTo(registryFile)) { "plugin_registry_write_failed" }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    }
}

class PluginPackageInstaller(
    private val root: File,
    private val registry: PluginRegistryStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val packagesDir = File(root, "packages")
    private val stagingDir = File(root, "staging")
    private val rollbackDir = File(root, "rollback")

    fun install(archive: File): Result<PluginInstallResult> = runCatching {
        require(archive.isFile && archive.length() in 1..MAX_ARCHIVE_BYTES) {
            "plugin_archive_size_invalid"
        }
        packagesDir.mkdirs()
        stagingDir.mkdirs()
        rollbackDir.mkdirs()
        val stage = File(stagingDir, UUID.randomUUID().toString())
        check(stage.mkdir()) { "plugin_staging_failed" }
        try {
            extract(archive, stage)
            val manifestFile = File(stage, MANIFEST_NAME)
            require(manifestFile.isFile && manifestFile.length() <= MAX_MANIFEST_BYTES) {
                "plugin_manifest_missing"
            }
            val manifest = JSON.decodeFromString<PluginManifestV1>(
                manifestFile.readText(Charsets.UTF_8)
            )
            PluginManifestValidator.validate(manifest)
            require(File(stage, manifest.entry).isFile) { "plugin_entry_missing" }
            val permissions = PluginManifestValidator.permissionSet(manifest.permissions)
            val previous = registry.get(manifest.id)
            val addedPermissions = permissions - previous?.permissions.orEmpty()
            val now = nowMs()
            val record = InstalledPluginRecord(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                manifest = manifest,
                sourceSha256 = sha256(archive),
                permissions = permissions,
                enabled = false,
                reviewStatus = PluginReviewStatus.NEEDS_REVIEW,
                installedAtMs = previous?.installedAtMs ?: now,
                updatedAtMs = now,
                pendingAddedPermissions = addedPermissions,
            )
            swapIntoPlace(stage, record)
            PluginInstallResult(
                record = record,
                addedPermissions = addedPermissions,
                modelToolNames = manifest.tools.map { tool ->
                    PluginManifestValidator.modelToolName(manifest.id, tool.slug)
                },
            )
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    /** Removes one installed package with a rollback directory until its registry row is gone. */
    fun uninstall(pluginId: String): Result<InstalledPluginRecord> = runCatching {
        require(SAFE_PLUGIN_ID.matches(pluginId)) { "plugin_id_invalid" }
        val record = registry.get(pluginId) ?: error("plugin_not_found")
        val target = File(packagesDir, pluginId).canonicalFile
        val packagesRoot = packagesDir.canonicalFile
        require(target.parentFile == packagesRoot) { "plugin_path_invalid" }
        rollbackDir.mkdirs()
        val rollback = File(rollbackDir, "$pluginId-${UUID.randomUUID()}")
        val moved = target.exists() && target.renameTo(rollback)
        require(!target.exists() || moved) { "plugin_uninstall_prepare_failed" }
        try {
            registry.remove(pluginId) ?: error("plugin_not_found")
            if (rollback.exists() && !rollback.deleteRecursively()) {
                // Registry state is already authoritative; leave a non-executable rollback orphan
                // for Doctor cleanup instead of resurrecting an installed plugin.
            }
            record
        } catch (failure: Throwable) {
            registry.upsert(record)
            if (moved && !target.exists()) rollback.renameTo(target)
            throw failure
        }
    }

    private fun extract(archive: File, stage: File) {
        val rootPath = stage.canonicalFile.toPath()
        var entryCount = 0
        var totalBytes = 0L
        val names = hashSetOf<String>()
        ZipInputStream(FileInputStream(archive)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "plugin_zip_entry_limit_exceeded" }
                val name = entry.name
                require(name.isNotBlank() && '\u0000' !in name && '\\' !in name) {
                    "plugin_zip_path_invalid"
                }
                val destination = File(stage, name).canonicalFile
                require(destination.toPath().startsWith(rootPath) && names.add(name)) {
                    "plugin_zip_path_invalid"
                }
                if (entry.isDirectory) {
                    check(destination.mkdirs() || destination.isDirectory) {
                        "plugin_zip_extract_failed"
                    }
                } else {
                    destination.parentFile?.mkdirs()
                    FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var entryBytes = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            entryBytes += read
                            totalBytes += read
                            require(entryBytes <= MAX_ENTRY_BYTES && totalBytes <= MAX_EXPANDED_BYTES) {
                                "plugin_zip_size_limit_exceeded"
                            }
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                input.closeEntry()
            }
        }
    }

    private fun swapIntoPlace(stage: File, record: InstalledPluginRecord) {
        val target = File(packagesDir, record.id)
        val rollback = File(rollbackDir, "${record.id}-${UUID.randomUUID()}")
        var oldMoved = false
        try {
            if (target.exists()) {
                check(target.renameTo(rollback)) { "plugin_upgrade_prepare_failed" }
                oldMoved = true
            }
            check(stage.renameTo(target)) { "plugin_install_swap_failed" }
            try {
                registry.upsert(record)
            } catch (failure: Throwable) {
                target.deleteRecursively()
                if (oldMoved) rollback.renameTo(target)
                throw failure
            }
            if (oldMoved) rollback.deleteRecursively()
        } catch (failure: Throwable) {
            if (!target.exists() && oldMoved) rollback.renameTo(target)
            throw failure
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private companion object {
        const val MANIFEST_NAME = "plugin.json"
        const val MAX_ARCHIVE_BYTES = 8L * 1024 * 1024
        const val MAX_EXPANDED_BYTES = 16L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 4L * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 64L * 1024
        const val MAX_ENTRIES = 256
        val JSON = Json { ignoreUnknownKeys = false; explicitNulls = false }
        val SAFE_PLUGIN_ID = Regex("[a-z0-9][a-z0-9._-]{1,63}")
    }
}
