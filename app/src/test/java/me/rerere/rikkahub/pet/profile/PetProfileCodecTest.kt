package me.rerere.rikkahub.pet.profile

import java.io.File
import java.nio.file.Files
import me.rerere.rikkahub.pet.CodexPetVersion
import me.rerere.rikkahub.pet.action.CorePetActions
import me.rerere.rikkahub.pet.assets.CodexPetManifest
import me.rerere.rikkahub.pet.assets.PetImageInfo
import me.rerere.rikkahub.pet.assets.PetImageProbe
import me.rerere.rikkahub.pet.assets.PetPackageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PetProfileCodecTest {
    private val v2Manifest = CodexPetManifest(
        id = "codex.v2",
        displayName = "V2",
        spriteVersionNumber = 2,
    )

    @Test
    fun `v2 default profile exposes exactly the stable nine core rows`() {
        val profile = PetProfileCodec.defaultProfile(v2Manifest).profile

        assertEquals(9, profile.bindings.size)
        assertFalse(profile.bindings.values.any { it.row >= 9 })
        assertEquals(11, profile.sheets.getValue("base").rows)
    }

    @Test
    fun `validated composite profile can bind an explicit extra static sheet`() {
        val root = Files.createTempDirectory("pet-profile").toFile()
        try {
            File(root, "extra.png").writeBytes(byteArrayOf(1))
            val profile = PetProfileCodec.validate(
                document = PetProfileDocument(
                    id = "package.extra",
                    renderer = "composite_sprite",
                    sheets = mapOf(
                        "extra" to PetProfileSheetDocument(
                            path = "extra.png",
                            frameWidth = 64,
                            frameHeight = 64,
                            columns = 1,
                            rows = 1,
                        ),
                    ),
                    actions = mapOf(
                        "custom.pose" to PetProfileClipDocument(sheet = "extra", row = 0, frames = 1),
                    ),
                ),
                manifest = v2Manifest,
                packageRoot = root,
                imageProbe = probeFor("extra.png", 64, 64),
            )

            assertEquals("composite_sprite", profile.profile.rendererType)
            assertEquals("extra", profile.profile.bindings.getValue(me.rerere.rikkahub.pet.action.PetActionId("custom.pose")).sheetId)
            assertTrue(profile.profile.capabilities.supportedActions.any { it.value == "custom.pose" })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `profile rejects path escape and fallback loops`() {
        val root = Files.createTempDirectory("pet-profile-invalid").toFile()
        try {
            val escaped = runCatching {
                PetProfileCodec.validate(
                    PetProfileDocument(
                        renderer = "composite_sprite",
                        sheets = mapOf(
                            "extra" to PetProfileSheetDocument("../escape.png", 64, 64, 1, 1),
                        ),
                    ),
                    v2Manifest,
                    root,
                    probeFor("extra.png", 64, 64),
                )
            }.exceptionOrNull() as? PetPackageException
            assertEquals("pet_profile_path_invalid", escaped?.code)

            val cycle = runCatching {
                PetProfileCodec.validate(
                    PetProfileDocument(
                        aliases = mapOf(
                            CorePetActions.SPEAKING.value to CorePetActions.DIALOGUE_NEUTRAL.value,
                            CorePetActions.DIALOGUE_NEUTRAL.value to CorePetActions.SPEAKING.value,
                        ),
                    ),
                    v2Manifest,
                    root,
                    probeFor("unused.png", 1, 1),
                )
            }.exceptionOrNull() as? PetPackageException
            assertEquals("pet_profile_fallback_cycle", cycle?.code)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun probeFor(name: String, width: Int, height: Int) = PetImageProbe { file ->
        if (file.name == name) PetImageInfo(width, height) else null
    }
}
