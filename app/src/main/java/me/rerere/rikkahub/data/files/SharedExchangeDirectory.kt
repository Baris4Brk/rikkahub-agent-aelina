package me.rerere.rikkahub.data.files

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/** Shared hand-off directory visible to RikkaHub, Termux storage, and workspace proot. */
object SharedExchangeDirectory {
    const val DIRECTORY_NAME = "RikkaHubExchange"
    const val APP_PATH = "/storage/emulated/0/RikkaHubExchange"
    const val TERMUX_PATH = "~/storage/shared/RikkaHubExchange"
    const val PROOT_PATH = "/sdcard/RikkaHubExchange"

    sealed interface Status {
        data class Ready(val directory: File) : Status
        data class PermissionRequired(val directory: File) : Status
        data class Unavailable(val directory: File) : Status
    }

    @Suppress("DEPRECATION")
    fun ensure(context: Context): Status {
        val directory = File(Environment.getExternalStorageDirectory(), DIRECTORY_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            return Status.PermissionRequired(directory)
        }
        return if ((directory.isDirectory || directory.mkdirs()) && directory.canRead() && directory.canWrite()) {
            Status.Ready(directory)
        } else {
            Status.Unavailable(directory)
        }
    }
}
