package me.rerere.rikkahub.pet.assets

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.pet.CodexPetVersion

@Serializable
data class CodexPetManifest(
    val id: String,
    val displayName: String,
    val description: String? = null,
    val spriteVersionNumber: Int? = null,
    val version: Int? = null,
    val spritesheetPath: String? = null,
) {
    val resolvedVersion: CodexPetVersion
        get() = when (spriteVersionNumber ?: version ?: 1) {
            1 -> CodexPetVersion.V1
            2 -> CodexPetVersion.V2
            else -> throw PetPackageException("pet_manifest_version_unsupported")
        }

    val resolvedSpritesheetPath: String
        get() = spritesheetPath?.takeIf(String::isNotBlank) ?: "spritesheet.webp"
}

data class PetImageInfo(
    val width: Int,
    val height: Int,
)

fun interface PetImageProbe {
    fun inspect(file: java.io.File): PetImageInfo?
}

class PetPackageException(val code: String) : IllegalArgumentException(code)

internal val SAFE_PET_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")

internal const val CODEX_FRAME_WIDTH = 192
internal const val CODEX_FRAME_HEIGHT = 208
internal const val CODEX_ATLAS_COLUMNS = 8

internal fun CodexPetVersion.expectedRows(): Int = when (this) {
    CodexPetVersion.V1 -> 9
    CodexPetVersion.V2 -> 11
}
