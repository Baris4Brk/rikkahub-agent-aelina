package me.rerere.rikkahub.owner

import android.content.Context
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.pet.PetOverlaySelection
import me.rerere.rikkahub.pet.resolvePetProfileForPackage
import me.rerere.rikkahub.pet.assets.AndroidPetImageProbe
import me.rerere.rikkahub.pet.assets.CodexPetManifest
import me.rerere.rikkahub.pet.assets.CodexPetPackageImporter
import me.rerere.rikkahub.pet.assets.PetPackageException
import me.rerere.rikkahub.pet.overlay.DesktopPetService
import me.rerere.rikkahub.plugin.PluginPackageInstaller
import me.rerere.rikkahub.plugin.PluginRegistryStore
import me.rerere.rikkahub.privilege.PrivilegedSessionContext
import kotlin.uuid.Uuid

/** Private-package Facade shared by Owner tools and suitable for reuse by settings UI. */
class OwnerPackageControlHandler(
    context: Context,
    private val settingsStore: SettingsStore,
    private val files: FilesManager,
    private val pluginInstaller: PluginPackageInstaller,
    private val plugins: PluginRegistryStore,
) : OwnerOperationHandler {
    private val appContext = context.applicationContext
    private val petsRoot = File(context.filesDir, "pets")
    private val petImporter = CodexPetPackageImporter(petsRoot, AndroidPetImageProbe)

    override fun supports(request: OwnerOperationRequest, action: OwnerAction): Boolean = when (request.family) {
        OwnerToolFamily.PLUGIN -> action.type in PLUGIN_ACTIONS
        OwnerToolFamily.PET -> action.type in PET_ACTIONS
        else -> false
    }

    override suspend fun validate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        val allowed = FIELDS[action.type]
            ?: return invalid("OWNER_ACTION_UNSUPPORTED", "Package action is not supported.")
        val unknown = action.arguments.keys - allowed
        if (unknown.isNotEmpty()) return invalid("OWNER_UNSUPPORTED_FIELD", "Unsupported package fields: ${unknown.sorted().joinToString()}.")
        return OwnerActionValidation(true, "OWNER_ACTION_VALID", "Private package action validated.")
    }

    override suspend fun apply(
        index: Int,
        request: OwnerOperationRequest,
        action: OwnerAction,
        context: PrivilegedSessionContext,
    ): OwnerAppliedAction = runCatching {
        when (action.type) {
            "plugin_install_managed" -> pluginInstall(index, action)
            "plugin_uninstall" -> pluginUninstall(index, action)
            "pet_list" -> petList(index, action)
            "pet_import_managed" -> petImport(index, action)
            "pet_select" -> petSelect(index, request, action)
            "pet_delete" -> petDelete(index, request, action)
            else -> failure(index, action, "OWNER_ACTION_UNSUPPORTED", "Package action is not supported.")
        }
    }.getOrElse { failure(index, action, (it as? PetPackageException)?.code ?: it.safePackageCode(), "Private package operation failed.") }

    override suspend fun verify(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerActionValidation {
        if (!applied.result.ok) return invalid(applied.result.code, applied.result.message)
        val ok = when (val receipt = applied.compensationReceipt) {
            is Receipt.PluginInstalled -> plugins.get(receipt.pluginId) != null
            is Receipt.PetInstalled -> installedPet(receipt.packageId) != null
            is Receipt.PetSelectionChanged -> settingsStore.settingsFlow.value.petOverlaySelection != receipt.previous
            else -> when (action.type) {
                "plugin_uninstall" -> action.arguments.string("plugin_id")?.let { plugins.get(it) == null } == true
                "pet_delete" -> action.arguments.string("package_id")?.let { installedPet(it) == null } == true
                "pet_list" -> true
                else -> false
            }
        }
        return if (ok) OwnerActionValidation(true, "PACKAGE_STATE_VERIFIED", "Private package state was read back.")
        else invalid("PACKAGE_VERIFY_FAILED", "Private package state did not match the requested operation.")
    }

    override suspend fun compensate(
        request: OwnerOperationRequest,
        action: OwnerAction,
        applied: OwnerAppliedAction,
        context: PrivilegedSessionContext,
    ): OwnerCompensationResult = runCatching {
        when (val receipt = applied.compensationReceipt) {
            is Receipt.PluginInstalled -> OwnerCompensationResult(
                pluginInstaller.uninstall(receipt.pluginId).isSuccess,
                "PLUGIN_INSTALL_ROLLED_BACK",
            )
            is Receipt.PetInstalled -> OwnerCompensationResult(
                receipt.createdNew && deleteInstalledPet(receipt.packageId),
                if (receipt.createdNew) "PET_INSTALL_ROLLED_BACK" else "PET_REPLACEMENT_NOT_REVERSIBLE",
            )
            is Receipt.PetSelectionChanged -> {
                settingsStore.update { it.copy(petOverlaySelection = receipt.previous) }
                OwnerCompensationResult(true, "PET_SELECTION_RESTORED")
            }
            else -> OwnerCompensationResult(action.risk == OwnerOperationRisk.READ_ONLY, "PACKAGE_COMPENSATION_UNAVAILABLE")
        }
    }.getOrElse { OwnerCompensationResult(false, "PACKAGE_COMPENSATION_FAILED") }

    private suspend fun pluginInstall(index: Int, action: OwnerAction): OwnerAppliedAction {
        val managedId = action.arguments.long("managed_file_id")
            ?: return failure(index, action, "MANAGED_FILE_ID_REQUIRED", "managed_file_id is required.")
        val entity = files.get(managedId) ?: return failure(index, action, "MANAGED_FILE_NOT_FOUND", "Managed plugin archive was not found.")
        val archive = files.getFile(entity)
        if (!archive.isFile) return failure(index, action, "MANAGED_FILE_MISSING", "Managed plugin archive is missing on disk.")
        val installed = pluginInstaller.install(archive).getOrElse { throw it }
        return success(
            index, action, "PLUGIN_INSTALLED", "Plugin installed disabled and ready for Owner review.",
            buildJsonObject {
                put("plugin_id", installed.record.id); put("name", installed.record.name.take(160))
                put("version", installed.record.version.take(80)); put("added_permission_count", installed.addedPermissions.size)
            },
            Receipt.PluginInstalled(installed.record.id),
        )
    }

    private suspend fun pluginUninstall(index: Int, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.string("plugin_id") ?: return failure(index, action, "PLUGIN_ID_REQUIRED", "plugin_id is required.")
        val before = settingsStore.settingsFlow.value
        settingsStore.update { current -> current.copy(assistants = current.assistants.map { assistant ->
            assistant.copy(enabledPluginIds = assistant.enabledPluginIds - id)
        }) }
        val removed = pluginInstaller.uninstall(id).getOrElse { failure ->
            settingsStore.update { current -> current.copy(assistants = current.assistants.map { assistant ->
                val previous = before.assistants.firstOrNull { it.id == assistant.id }
                if (previous == null) assistant else assistant.copy(enabledPluginIds = previous.enabledPluginIds)
            }) }
            throw failure
        }
        return success(index, action, "PLUGIN_UNINSTALLED", "Plugin was unbound and uninstalled.", buildJsonObject {
            put("plugin_id", removed.id)
        })
    }

    private fun petList(index: Int, action: OwnerAction): OwnerAppliedAction {
        val selection = settingsStore.settingsFlow.value.petOverlaySelection
        return success(index, action, "PET_PACKAGES_LISTED", "Private pet package library rebuilt from installed manifests.", buildJsonObject {
            put("selection", ownerPetSelectionData(selection))
            put("items", buildJsonArray { installedPets().forEach { manifest -> add(buildJsonObject {
                put("package_id", manifest.id); put("name", manifest.displayName.take(160))
                put("version", manifest.resolvedVersion.name); put("selected", selection?.packageId == manifest.id)
            }) } })
        })
    }

    private suspend fun petImport(index: Int, action: OwnerAction): OwnerAppliedAction {
        val managedId = action.arguments.long("managed_file_id")
            ?: return failure(index, action, "MANAGED_FILE_ID_REQUIRED", "managed_file_id is required.")
        val source = files.get(managedId) ?: return failure(index, action, "MANAGED_FILE_NOT_FOUND", "Managed pet archive was not found.")
        val archive = files.getFile(source)
        if (!archive.isFile || source.sizeBytes !in 1..MAX_PET_ARCHIVE_BYTES) {
            return failure(index, action, "PET_ARCHIVE_INVALID", "Managed pet archive is missing or outside the size limit.")
        }
        val storedOriginal = if (source.folder == FileFolders.PET_PACKAGES) null else {
            files.saveManagedFromBytes(
                folder = FileFolders.PET_PACKAGES,
                bytes = archive.readBytes(),
                displayName = source.displayName.take(160).ifBlank { "pet-package.zip" },
                mimeType = "application/zip",
            )
        }
        val replace = action.arguments.boolean("replace") ?: false
        var packageId: String? = null
        val createdNew: Boolean
        try {
            val existingIds = installedPets().mapTo(hashSetOf()) { it.id }
            val installed = archive.inputStream().use { petImporter.import(it, replaceExisting = replace) }
            packageId = installed.manifest.id
            createdNew = installed.manifest.id !in existingIds
            return success(
                index, action, "PET_PACKAGE_IMPORTED", "Pet package validated and installed in private storage.",
                buildJsonObject { put("package_id", installed.manifest.id); put("name", installed.manifest.displayName.take(160)) },
                Receipt.PetInstalled(installed.manifest.id, createdNew),
            )
        } catch (failure: Throwable) {
            storedOriginal?.let { files.deleteManagedFile(it) }
            if (packageId != null && !replace) deleteInstalledPet(packageId)
            throw failure
        }
    }

    private suspend fun petSelect(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.string("package_id") ?: return failure(index, action, "PET_PACKAGE_ID_REQUIRED", "package_id is required.")
        if (installedPet(id) == null) return failure(index, action, "PET_PACKAGE_NOT_FOUND", "Pet package is not installed.")
        val assistantId = runCatching { Uuid.parse(request.assistantId) }.getOrNull()
            ?: return failure(index, action, "ASSISTANT_ID_INVALID", "Owner assistant ID is invalid.")
        val conversationId = runCatching { Uuid.parse(request.conversationId) }.getOrNull()
            ?: return failure(index, action, "CONVERSATION_ID_INVALID", "Owner conversation ID is invalid.")
        val previous = settingsStore.settingsFlow.value.petOverlaySelection
        val requestedProfileId = action.arguments.string("profile_id")
        val next = (previous ?: PetOverlaySelection(assistantId, conversationId)).copy(
            ownerAssistantId = assistantId,
            privilegedConversationId = conversationId,
            enabled = action.arguments.boolean("enabled") ?: true,
            packageId = id,
            profileId = resolvePetProfileForPackage(
                previousPackageId = previous?.packageId,
                previousProfileId = previous?.profileId,
                nextPackageId = id,
                requestedProfileId = requestedProfileId,
            ),
        ).normalized()
        settingsStore.update { current ->
            current.copy(
                petOverlaySelection = next,
                // Keep the legacy Assistant fields as a compatibility mirror. The global
                // selection remains authoritative, but older settings surfaces still read these
                // fields and must not write a stale package back over an Owner selection.
                assistants = current.assistants.map { assistant ->
                    when {
                        assistant.id == assistantId -> assistant.copy(
                            petEnabled = next.enabled,
                            petPackageId = next.packageId,
                            petScale = next.scale,
                            petAnimationFps = next.animationFps,
                            petHeadBoundary = next.headBoundary,
                            petBodyBoundary = next.bodyBoundary,
                            petIdlePoolEnabled = next.idlePoolEnabled,
                        )
                        next.enabled && assistant.petEnabled -> assistant.copy(petEnabled = false)
                        else -> assistant
                    }
                },
            )
        }
        // Also covers a replaced ZIP with the same package id: the persisted selection is
        // intentionally identical, so the Settings flow alone cannot invalidate the renderer.
        DesktopPetService.reload(appContext)
        return success(index, action, "PET_SELECTED", "Pet package selected for the active Owner.", receipt = Receipt.PetSelectionChanged(previous))
    }

    private suspend fun petDelete(index: Int, request: OwnerOperationRequest, action: OwnerAction): OwnerAppliedAction {
        val id = action.arguments.string("package_id") ?: return failure(index, action, "PET_PACKAGE_ID_REQUIRED", "package_id is required.")
        val target = installedPetDirectory(id) ?: return failure(index, action, "PET_PACKAGE_NOT_FOUND", "Pet package is not installed.")
        val before = settingsStore.settingsFlow.value.petOverlaySelection
        val isSelected = before?.packageId == id
        val replacementId = action.arguments.string("replacement_package_id")
        if (isSelected && replacementId == null) {
            return failure(index, action, "PET_REPLACEMENT_REQUIRED", "Deleting the selected pet requires replacement_package_id in the same action.")
        }
        if (replacementId != null && installedPet(replacementId) == null) {
            return failure(index, action, "PET_REPLACEMENT_NOT_FOUND", "Replacement pet package is not installed.")
        }
        if (isSelected && (before.ownerAssistantId.toString() != request.assistantId || before.privilegedConversationId.toString() != request.conversationId)) {
            return failure(index, action, "PET_OWNER_MISMATCH", "Selected pet does not belong to the active Owner identity.")
        }
        val tombstone = File(petsRoot, ".delete-$id-${Uuid.random()}")
        if (!target.renameTo(tombstone)) return failure(index, action, "PET_DELETE_PREPARE_FAILED", "Pet package could not be staged for deletion.")
        try {
            if (isSelected) settingsStore.update { current -> current.copy(
                petOverlaySelection = requireNotNull(before).copy(
                    packageId = replacementId,
                    profileId = resolvePetProfileForPackage(
                        previousPackageId = before.packageId,
                        previousProfileId = before.profileId,
                        nextPackageId = replacementId,
                    ),
                ).normalized(),
            ) }
            if (!tombstone.deleteRecursively()) error("pet_delete_failed")
        } catch (failure: Throwable) {
            if (!target.exists()) tombstone.renameTo(target)
            settingsStore.update { it.copy(petOverlaySelection = before) }
            throw failure
        }
        return success(index, action, "PET_PACKAGE_DELETED", "Pet package deleted after selection was safely transitioned.")
    }

    private fun installedPets(): List<CodexPetManifest> = petsRoot.listFiles().orEmpty()
        .asSequence().filter { it.isDirectory && !it.name.startsWith(".") }
        .mapNotNull { directory -> runCatching {
            JSON.decodeFromString<CodexPetManifest>(File(directory, "pet.json").readText(Charsets.UTF_8))
        }.getOrNull() }.sortedBy { it.displayName.lowercase() }.toList()

    private fun installedPet(packageId: String): CodexPetManifest? = installedPetDirectory(packageId)?.let { directory ->
        runCatching { JSON.decodeFromString<CodexPetManifest>(File(directory, "pet.json").readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun installedPetDirectory(packageId: String): File? {
        if (!SAFE_ID.matches(packageId)) return null
        val root = petsRoot.canonicalFile
        val target = File(root, packageId).canonicalFile
        return target.takeIf { it.parentFile == root && it.isDirectory && File(it, "pet.json").isFile }
    }

    private fun deleteInstalledPet(packageId: String): Boolean = installedPetDirectory(packageId)?.deleteRecursively() ?: true

    private fun success(index: Int, action: OwnerAction, code: String, message: String, data: JsonObject? = null, receipt: Receipt? = null) =
        OwnerAppliedAction(OwnerActionResult(index, action.type, true, code, message, data), receipt)
    private fun failure(index: Int, action: OwnerAction, code: String, message: String) =
        OwnerAppliedAction(OwnerActionResult(index, action.type, false, code, message.take(500)))
    private fun invalid(code: String, message: String) = OwnerActionValidation(false, code, message.take(500))

    private sealed interface Receipt {
        data class PluginInstalled(val pluginId: String) : Receipt
        data class PetInstalled(val packageId: String, val createdNew: Boolean) : Receipt
        data class PetSelectionChanged(val previous: PetOverlaySelection?) : Receipt
    }

    private companion object {
        const val MAX_PET_ARCHIVE_BYTES = 32L * 1024 * 1024
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        val JSON = Json { ignoreUnknownKeys = true }
        val PLUGIN_ACTIONS = setOf("plugin_install_managed", "plugin_uninstall")
        val PET_ACTIONS = setOf("pet_list", "pet_import_managed", "pet_select", "pet_delete")
        val FIELDS = mapOf(
            "plugin_install_managed" to setOf("managed_file_id"),
            "plugin_uninstall" to setOf("plugin_id"),
            "pet_list" to emptySet(),
            "pet_import_managed" to setOf("managed_file_id", "replace"),
            "pet_select" to setOf("package_id", "profile_id", "enabled"),
            "pet_delete" to setOf("package_id", "replacement_package_id"),
        )
    }
}

internal fun ownerPetSelectionData(selection: PetOverlaySelection?): JsonObject = buildJsonObject {
    put("configured", selection != null)
    selection?.normalized()?.let { current ->
        put("enabled", current.enabled)
        put("package_id", current.packageId.orEmpty())
        put("profile_id", current.profileId.orEmpty())
        put("scale", current.scale)
        put("fps", current.animationFps)
        current.normalizedX?.let { put("x", it) }
        current.normalizedY?.let { put("y", it) }
        put("idle_pool_enabled", current.idlePoolEnabled)
    }
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.boolean(name: String): Boolean? = string(name)?.toBooleanStrictOrNull()
private fun JsonObject.long(name: String): Long? = string(name)?.toLongOrNull()
private fun Throwable.safePackageCode(): String = message?.takeIf { it.matches(Regex("[a-z0-9_]{3,80}")) }?.uppercase() ?: "PACKAGE_OPERATION_FAILED"
