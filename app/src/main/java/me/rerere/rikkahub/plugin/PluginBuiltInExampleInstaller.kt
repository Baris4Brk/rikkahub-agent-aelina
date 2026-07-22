package me.rerere.rikkahub.plugin

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginBuiltInExampleInstaller(
    context: Context,
    private val installer: PluginPackageInstaller,
) {
    private val appContext = context.applicationContext

    fun install(): Result<PluginInstallResult> = runCatching {
        val archive = File.createTempFile(
            "bounded-example-",
            ".zip",
            File(appContext.cacheDir, "plugin-imports").apply { mkdirs() },
        )
        try {
            ZipOutputStream(FileOutputStream(archive)).use { output ->
                ASSET_FILES.forEach { name ->
                    output.putNextEntry(ZipEntry(name))
                    appContext.assets.open("plugin-example-v1/$name").use { input ->
                        input.copyTo(output)
                    }
                    output.closeEntry()
                }
            }
            installer.install(archive).getOrThrow()
        } finally {
            archive.delete()
        }
    }

    private companion object {
        val ASSET_FILES = listOf("plugin.json", "index.html")
    }
}
