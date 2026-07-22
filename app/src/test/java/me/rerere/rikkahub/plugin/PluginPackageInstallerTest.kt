package me.rerere.rikkahub.plugin

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginPackageInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `valid unsigned package installs disabled and requires review`() {
        val root = temporaryFolder.newFolder("plugins")
        val marker = temporaryFolder.newFolder("no-backup")
        val registry = FilePluginRegistryStore(root, marker)
        val installer = PluginPackageInstaller(root, registry)
        val archive = zip(
            "plugin.json" to manifest(networkHosts = emptyList()),
            "index.html" to "<script>async function readState(input){return input}</script>",
        )

        val result = installer.install(archive).getOrThrow()
        val record = registry.snapshot().single()

        assertEquals(PluginReviewStatus.NEEDS_REVIEW, result.record.reviewStatus)
        assertFalse(result.record.enabled)
        assertEquals(record, result.record)
        assertEquals(64, result.record.sourceSha256.length)
        assertTrue(File(root, "packages/sample-plugin/index.html").isFile)
        assertTrue(result.modelToolNames.single().matches(Regex("plugin__[a-f0-9]{12}__read_state")))
    }

    @Test
    fun `zip slip is rejected before writing outside staging`() {
        val root = temporaryFolder.newFolder("plugins")
        val marker = temporaryFolder.newFolder("no-backup")
        val installer = PluginPackageInstaller(root, FilePluginRegistryStore(root, marker))
        val archive = zip(
            "plugin.json" to manifest(networkHosts = emptyList()),
            "../escaped.txt" to "must-not-write",
            "index.html" to "ok",
        )

        val failure = installer.install(archive).exceptionOrNull()

        assertEquals("plugin_zip_path_invalid", failure?.message)
        assertFalse(File(root.parentFile, "escaped.txt").exists())
    }

    @Test
    fun `permission expansion is recorded and cannot preserve enablement`() {
        val root = temporaryFolder.newFolder("plugins")
        val marker = temporaryFolder.newFolder("no-backup")
        val registry = FilePluginRegistryStore(root, marker)
        val installer = PluginPackageInstaller(root, registry)
        installer.install(zip(
            "plugin.json" to manifest(networkHosts = emptyList()),
            "index.html" to "v1",
        )).getOrThrow()
        registry.update("sample-plugin") { it.copy(
            enabled = true,
            reviewStatus = PluginReviewStatus.APPROVED,
        ) }

        val upgraded = installer.install(zip(
            "plugin.json" to manifest(networkHosts = listOf("api.example.com"), version = "2"),
            "index.html" to "v2",
        )).getOrThrow()

        assertFalse(upgraded.record.enabled)
        assertEquals(PluginReviewStatus.NEEDS_REVIEW, upgraded.record.reviewStatus)
        assertEquals(setOf("network:https://api.example.com"), upgraded.addedPermissions)
        assertEquals(upgraded.addedPermissions, upgraded.record.pendingAddedPermissions)
    }

    @Test
    fun `registry restored without no-backup marker disables every plugin`() {
        val root = temporaryFolder.newFolder("plugins")
        val marker = temporaryFolder.newFolder("no-backup")
        val first = FilePluginRegistryStore(root, marker)
        val installer = PluginPackageInstaller(root, first)
        installer.install(zip(
            "plugin.json" to manifest(networkHosts = emptyList()),
            "index.html" to "v1",
        )).getOrThrow()
        first.update("sample-plugin") { it.copy(
            enabled = true,
            reviewStatus = PluginReviewStatus.APPROVED,
        ) }
        File(marker, "plugin-installation-id").delete()

        val restored = FilePluginRegistryStore(root, marker).snapshot().single()

        assertFalse(restored.enabled)
        assertEquals(PluginReviewStatus.NEEDS_REVIEW, restored.reviewStatus)
    }

    private fun manifest(
        networkHosts: List<String>,
        version: String = "1",
    ): String {
        val hosts = networkHosts.joinToString(",") { "\"$it\"" }
        return """
            {
              "schemaVersion": 1,
              "id": "sample-plugin",
              "name": "Sample",
              "version": "$version",
              "entry": "index.html",
              "permissions": {
                "stateRead": true,
                "storageRead": true,
                "storageWrite": true,
                "networkHosts": [$hosts]
              },
              "tools": [{
                "slug": "read_state",
                "description": "Read bounded state",
                "handler": "readState",
                "inputSchema": {"type":"object","properties":{}}
              }]
            }
        """.trimIndent()
    }

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = temporaryFolder.newFile("plugin-${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, contents) ->
                output.putNextEntry(ZipEntry(name))
                output.write(contents.toByteArray())
                output.closeEntry()
            }
        }
        return file
    }
}
