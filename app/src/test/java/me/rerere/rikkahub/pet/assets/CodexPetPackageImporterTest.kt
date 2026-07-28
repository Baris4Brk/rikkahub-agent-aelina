package me.rerere.rikkahub.pet.assets

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodexPetPackageImporterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `imports v2 package and preserves original manifest`() {
        val root = temporaryFolder.newFolder("pets")
        val manifest = """{"id":"moon","displayName":"Moon","spriteVersionNumber":2,"spritesheetPath":"spritesheet.webp","future":true}"""
        val importer = CodexPetPackageImporter(root, PetImageProbe { PetImageInfo(1536, 2288) })

        val installed = importer.import(zipOf("pet.json" to manifest.toByteArray(), "spritesheet.webp" to byteArrayOf(1)))

        assertEquals("moon", installed.manifest.id)
        assertEquals(manifest, installed.directory.resolve("pet.json").readText())
        assertTrue(installed.directory.resolve("internal-metadata.json").isFile)
    }

    @Test
    fun `accepts BOM missing version and one folder`() {
        val root = temporaryFolder.newFolder("pets")
        val body = """{"id":"legacy","displayName":"Legacy"}""".toByteArray()
        val manifest = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body
        val importer = CodexPetPackageImporter(root, PetImageProbe { PetImageInfo(1536, 1872) })

        val installed = importer.import(
            zipOf("legacy/pet.json" to manifest, "legacy/spritesheet.webp" to byteArrayOf(1)),
        )

        assertEquals("V1", installed.manifest.resolvedVersion.name)
    }

    @Test
    fun `rejects zip slip and executable content`() {
        val root = temporaryFolder.newFolder("pets")
        val importer = CodexPetPackageImporter(root, PetImageProbe { PetImageInfo(1536, 1872) })

        val slip = runCatching { importer.import(zipOf("../pet.json" to byteArrayOf(1))) }.exceptionOrNull()
        val executable = runCatching { importer.import(zipOf("payload.js" to byteArrayOf(1))) }.exceptionOrNull()

        assertEquals("pet_zip_path_invalid", (slip as PetPackageException).code)
        assertEquals("pet_zip_file_type_forbidden", (executable as PetPackageException).code)
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
}
