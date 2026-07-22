package me.rerere.rikkahub.plugin

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginBuiltInExampleAssetTest {
    @Test
    fun `bundled example contains a valid manifest and entry page`() {
        val root = File("src/main/assets/plugin-example-v1")
        val manifestFile = File(root, "plugin.json")
        val entryFile = File(root, "index.html")

        assertTrue(manifestFile.isFile)
        assertTrue(entryFile.isFile)

        val manifest = Json { ignoreUnknownKeys = false }
            .decodeFromString<PluginManifestV1>(manifestFile.readText(Charsets.UTF_8))
        PluginManifestValidator.validate(manifest)
        assertTrue(File(root, manifest.entry).isFile)
    }
}
