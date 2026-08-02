package me.rerere.rikkahub.ui.pages.assistant.detail

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.pet.assets.CodexPetManifest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PetSettingsDialogTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `installed catalog ignores broken directories and sorts display names`() {
        val root = temporaryFolder.newFolder("pets")
        writeManifest(root, CodexPetManifest(id = "zeta", displayName = "Zeta"))
        writeManifest(root, CodexPetManifest(id = "alpha", displayName = "Alpha"))
        File(root, "broken").mkdirs()
        File(root, ".staging-hidden").mkdirs()

        val installed = listInstalledPetManifests(root)

        assertEquals(listOf("alpha", "zeta"), installed.map { it.id })
    }

    private fun writeManifest(root: File, manifest: CodexPetManifest) {
        val directory = File(root, manifest.id).apply { mkdirs() }
        File(directory, "pet.json").writeText(Json.encodeToString(manifest), Charsets.UTF_8)
    }
}
