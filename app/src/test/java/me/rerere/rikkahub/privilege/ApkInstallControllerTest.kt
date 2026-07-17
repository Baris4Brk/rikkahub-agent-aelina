package me.rerere.rikkahub.privilege

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.createInstallApkTool
import me.rerere.rikkahub.data.packageinstaller.ApkFileValidation
import me.rerere.rikkahub.data.packageinstaller.ApkFileValidator
import me.rerere.rikkahub.data.packageinstaller.ApkInstallController
import me.rerere.rikkahub.data.packageinstaller.ApkInstallResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallControllerTest {
    @Test
    fun `validator accepts an apk-shaped zip inside an allowed root`() {
        val root = createTempDirectory("apk-root-").toFile()
        val apk = File(root, "sample.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write("dex\n035\u0000".toByteArray())
            zip.closeEntry()
        }

        assertEquals(ApkFileValidation.Valid, ApkFileValidator.validate(apk, listOf(root)))
    }

    @Test
    fun `validator rejects traversal outside allowed roots and fake apk`() {
        val parent = createTempDirectory("apk-parent-").toFile()
        val root = File(parent, "allowed").apply { mkdirs() }
        val outside = File(parent, "outside.apk").apply { writeText("not a zip") }
        assertTrue(ApkFileValidator.validate(outside, listOf(root)) is ApkFileValidation.OutsideAllowedRoots)

        val fake = File(root, "fake.apk").apply { writeText("not a zip") }
        assertTrue(ApkFileValidator.validate(fake, listOf(root)) is ApkFileValidation.InvalidArchive)
    }

    @Test
    fun `install tool exposes only source and returns structured result`() = runBlocking {
        var source: String? = null
        val controller = ApkInstallController {
            source = it
            ApkInstallResult.Launched("demo.package")
        }
        val tool = createInstallApkTool(controller)

        val schema = tool.parameters()!!
        val rendered = schema.toString()
        assertTrue(rendered.contains("source"))
        assertFalse(rendered.contains("silent"))
        assertFalse(rendered.contains("installer_flags"))

        val result = tool.execute(buildJsonObject { put("source", "~/Downloads/demo.apk") })
        assertEquals("~/Downloads/demo.apk", source)
        assertTrue(result.single().toString().contains("INSTALLER_OPENED"))
    }
}
