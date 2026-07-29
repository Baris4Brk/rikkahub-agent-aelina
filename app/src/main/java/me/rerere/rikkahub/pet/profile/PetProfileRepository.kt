package me.rerere.rikkahub.pet.profile

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.pet.action.PetActionProfile
import me.rerere.rikkahub.pet.assets.AndroidPetImageProbe
import me.rerere.rikkahub.pet.assets.CodexPetManifest
import me.rerere.rikkahub.pet.assets.PetImageProbe
import me.rerere.rikkahub.pet.assets.PetPackageException
import me.rerere.rikkahub.pet.render.CodexPetAtlas
import me.rerere.rikkahub.pet.render.CompositePetSpriteAtlas
import me.rerere.rikkahub.pet.render.PetSpriteAtlas
import me.rerere.rikkahub.pet.render.StaticPetSpriteAtlas

@Serializable
data class PetVisualProfileOverride(
    val schemaVersion: Int = PetProfileCodec.PROFILE_SCHEMA_VERSION,
    val actions: Map<String, PetProfileClipDocument> = emptyMap(),
    val aliases: Map<String, String> = emptyMap(),
    val fallbacks: Map<String, List<String>> = emptyMap(),
    val touchMappings: Map<String, String> = emptyMap(),
    val idlePool: PetProfileIdlePoolDocument? = null,
)

data class LoadedPetRendererResources(
    val profile: PetActionProfile,
    val atlas: PetSpriteAtlas,
)

/**
 * Loads an immutable ZIP profile plus a separately stored constrained app override. The override
 * has no package path, renderer, script, reflection, class name or network field, so it cannot
 * expand the package's authority or modify the original ZIP.
 */
class PetProfileRepository(
    private val petsRoot: File,
    private val overridesRoot: File,
    private val imageProbe: PetImageProbe = AndroidPetImageProbe,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    fun load(packageId: String, expectedProfileId: String? = null): LoadedPetRendererResources {
        require(PACKAGE_ID.matches(packageId)) { "pet_package_id_invalid" }
        val packageRoot = File(petsRoot, packageId)
        val manifestFile = File(packageRoot, "pet.json")
        if (!manifestFile.isFile) throw PetPackageException("pet_manifest_missing")
        val manifest = runCatching {
            json.decodeFromString<CodexPetManifest>(manifestFile.readText(Charsets.UTF_8).removeBom())
        }.getOrElse { throw PetPackageException("pet_manifest_invalid") }
        val baseDocument = File(packageRoot, PetProfileCodec.PROFILE_FILE_NAME)
            .takeIf(File::isFile)
            ?.readText(Charsets.UTF_8)
            ?.removeBom()
            ?.let { raw ->
                runCatching { json.decodeFromString<PetProfileDocument>(raw) }
                    .getOrElse { throw PetPackageException("pet_profile_invalid") }
            }
            ?: PetProfileDocument(
                id = "builtin.codex.${manifest.resolvedVersion.name.lowercase()}",
                renderer = "codex_sprite",
            )
        val merged = merge(baseDocument, readOverride(packageId))
        val validated = PetProfileCodec.validate(merged, manifest, packageRoot, imageProbe)
        if (expectedProfileId != null && validated.profile.profileId != expectedProfileId) {
            throw PetPackageException("pet_profile_selection_missing")
        }
        val baseAtlas = CodexPetAtlas.decode(
            file = File(packageRoot, manifest.resolvedSpritesheetPath),
            version = manifest.resolvedVersion,
        )
        try {
            val atlas: PetSpriteAtlas = if (validated.extraSheetPaths.isEmpty()) {
                baseAtlas
            } else {
                val allSheets = mutableMapOf<String, PetSpriteAtlas>("base" to baseAtlas)
                validated.extraSheetPaths.forEach { (sheetId, relativePath) ->
                    val sheet = validated.profile.sheets.getValue(sheetId)
                    allSheets[sheetId] = StaticPetSpriteAtlas.decode(File(packageRoot, relativePath), sheet)
                }
                CompositePetSpriteAtlas(allSheets)
            }
            return LoadedPetRendererResources(validated.profile, atlas)
        } catch (error: Throwable) {
            baseAtlas.close()
            throw error
        }
    }

    /** Saves only a validated visual overlay after merging it with the immutable package data. */
    fun saveOverride(packageId: String, override: PetVisualProfileOverride) {
        val packageRoot = File(petsRoot, packageId)
        val manifest = json.decodeFromString<CodexPetManifest>(File(packageRoot, "pet.json").readText().removeBom())
        val base = File(packageRoot, PetProfileCodec.PROFILE_FILE_NAME)
            .takeIf(File::isFile)
            ?.readText()
            ?.removeBom()
            ?.let { json.decodeFromString<PetProfileDocument>(it) }
            ?: PetProfileDocument(id = "builtin.codex.${manifest.resolvedVersion.name.lowercase()}")
        // Validation checks every action/fallback/touch binding against the immutable package.
        PetProfileCodec.validate(merge(base, override), manifest, packageRoot, imageProbe)
        overridesRoot.mkdirs()
        val target = File(overridesRoot, "$packageId.json")
        val temporary = File(overridesRoot, ".$packageId.tmp")
        temporary.writeText(json.encodeToString(PetVisualProfileOverride.serializer(), override), Charsets.UTF_8)
        if (target.exists()) target.delete()
        check(temporary.renameTo(target)) { "pet_profile_override_write_failed" }
    }

    fun loadOverride(packageId: String): PetVisualProfileOverride? = readOverride(packageId)

    private fun readOverride(packageId: String): PetVisualProfileOverride? {
        val file = File(overridesRoot, "$packageId.json")
        if (!file.isFile || file.length() > MAX_OVERRIDE_BYTES) return null
        return runCatching { json.decodeFromString<PetVisualProfileOverride>(file.readText(Charsets.UTF_8).removeBom()) }
            .getOrNull()
            ?.takeIf { it.schemaVersion == PetProfileCodec.PROFILE_SCHEMA_VERSION }
    }

    private fun merge(base: PetProfileDocument, override: PetVisualProfileOverride?): PetProfileDocument {
        if (override == null) return base
        return base.copy(
            actions = base.actions + override.actions,
            aliases = base.aliases + override.aliases,
            fallbacks = base.fallbacks + override.fallbacks,
            touchMappings = base.touchMappings + override.touchMappings,
            idlePool = override.idlePool ?: base.idlePool,
        )
    }

    private fun String.removeBom(): String = removePrefix("\uFEFF")

    private companion object {
        val PACKAGE_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")
        const val MAX_OVERRIDE_BYTES = 128 * 1024L
    }
}
