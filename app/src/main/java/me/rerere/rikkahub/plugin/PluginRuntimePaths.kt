package me.rerere.rikkahub.plugin

import android.content.Context
import java.io.File

/** Keeps plugin package, storage, and restore-detection paths consistent across processes. */
internal object PluginRuntimePaths {
    private const val ROOT_DIRECTORY = "plugin-runtime-v1"

    fun root(context: Context): File = File(context.filesDir, ROOT_DIRECTORY)

    fun packagesRoot(context: Context): File = File(root(context), "packages")

    fun packageRoot(context: Context, pluginId: String): File =
        File(packagesRoot(context), pluginId)

    fun storageRoot(context: Context): File = File(root(context), "storage")

    /** This marker is deliberately excluded from user file backups. */
    fun installationMarkerDirectory(context: Context): File =
        File(context.noBackupFilesDir, ROOT_DIRECTORY)
}
