package me.rerere.rikkahub.data.packageinstaller

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper

class AndroidApkInstallController(
    private val context: Context,
) : ApkInstallController {
    override suspend fun requestInstall(source: String): ApkInstallResult {
        val prepared = try {
            withContext(Dispatchers.IO) { prepareSource(source) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            return ApkInstallResult.Rejected(
                "SOURCE_PREPARATION_FAILED",
                error.message ?: "Unable to prepare the APK source.",
            )
        }
        if (prepared is PreparedApk.Error) {
            return ApkInstallResult.Rejected(prepared.code, prepared.message)
        }
        prepared as PreparedApk.Ready

        if (!PermissionHelper.canRequestPackageInstalls(context)) {
            return try {
                withContext(Dispatchers.Main) {
                    context.startActivity(PermissionHelper.unknownAppSourcesIntent(context))
                }
                ApkInstallResult.ActionRequired(
                    "Allow RikkaHub to install unknown apps, then call install_apk again."
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                ApkInstallResult.Rejected(
                    "UNKNOWN_SOURCES_SETTINGS_FAILED",
                    error.message ?: "Unable to open the unknown-app-sources settings page.",
                )
            }
        }

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                prepared.file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                clipData = ClipData.newRawUri("apk", uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) { context.startActivity(intent) }
            ApkInstallResult.Launched(prepared.packageName)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            ApkInstallResult.Rejected(
                "INSTALLER_LAUNCH_FAILED",
                error.message ?: "Unable to open Android's package installer.",
            )
        }
    }

    private fun prepareSource(source: String): PreparedApk {
        cleanupOldStagedFiles()
        val uri = runCatching { source.toUri() }.getOrNull()
        val candidate = if (uri?.scheme.equals("content", ignoreCase = true)) {
            stageContentUri(uri!!)
        } else {
            prepareFilePath(source)
        }
        if (candidate is PreparedApk.Error) return candidate
        candidate as PreparedApk.Ready

        val validation = ApkFileValidator.validate(candidate.file, providerRoots())
        if (validation != ApkFileValidation.Valid) return validation.toPreparedError()
        val packageName = readArchivePackageName(candidate.file)
            ?: return PreparedApk.Error("INVALID_APK", "Android could not parse this APK package.")
        return candidate.copy(packageName = packageName)
    }

    private fun prepareFilePath(source: String): PreparedApk {
        if (source.indexOf('\u0000') >= 0) return PreparedApk.Error("INVALID_SOURCE", "The path contains a NUL character.")
        val expanded = when {
            source == "~" -> context.filesDir
            source.startsWith("~/") || source.startsWith("~\\") ->
                File(context.filesDir, source.drop(2))
            else -> File(source)
        }
        if (!expanded.isAbsolute) {
            return PreparedApk.Error("RELATIVE_PATH_NOT_ALLOWED", "Use an absolute path, ~/ path, or content:// URI.")
        }
        val validation = ApkFileValidator.validate(expanded, readableSourceRoots())
        if (validation != ApkFileValidation.Valid) return validation.toPreparedError()

        val canonical = expanded.canonicalFile
        return if (providerRoots().any { canonical.toPath().startsWith(it.canonicalFile.toPath()) }) {
            PreparedApk.Ready(canonical, null)
        } else {
            stageFile(canonical)
        }
    }

    private fun stageContentUri(uri: Uri): PreparedApk {
        if (uri.scheme != "content") return PreparedApk.Error("UNSUPPORTED_URI", "Only content:// URIs are supported.")
        val displayName = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                name to size
            }
        }
        val name = displayName?.first ?: uri.lastPathSegment.orEmpty()
        if (!name.endsWith(".apk", ignoreCase = true)) {
            return PreparedApk.Error("WRONG_EXTENSION", "The selected document must have an .apk filename.")
        }
        val declaredSize = displayName?.second
        if (declaredSize != null && declaredSize > ApkFileValidator.MAX_APK_BYTES) {
            return PreparedApk.Error("APK_TOO_LARGE", "APK exceeds the 2 GiB limit.")
        }
        val staged = newStagedFile()
        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return PreparedApk.Error("SOURCE_UNREADABLE", "The content URI could not be opened.")
            input.use { source ->
                FileOutputStream(staged).use { destination -> copyLimited(source, destination) }
            }
            PreparedApk.Ready(staged, null)
        } catch (tooLarge: ApkTooLargeException) {
            staged.delete()
            PreparedApk.Error("APK_TOO_LARGE", "APK exceeds the 2 GiB limit.")
        } catch (error: Throwable) {
            staged.delete()
            if (error is CancellationException) throw error
            PreparedApk.Error("SOURCE_UNREADABLE", error.message ?: "The content URI could not be read.")
        }
    }

    private fun stageFile(source: File): PreparedApk {
        val staged = newStagedFile()
        return try {
            source.inputStream().use { input ->
                FileOutputStream(staged).use { output -> copyLimited(input, output) }
            }
            PreparedApk.Ready(staged, null)
        } catch (tooLarge: ApkTooLargeException) {
            staged.delete()
            PreparedApk.Error("APK_TOO_LARGE", "APK exceeds the 2 GiB limit.")
        } catch (error: Throwable) {
            staged.delete()
            if (error is CancellationException) throw error
            PreparedApk.Error("STAGING_FAILED", error.message ?: "Unable to copy APK into the private install cache.")
        }
    }

    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > ApkFileValidator.MAX_APK_BYTES) throw ApkTooLargeException()
            output.write(buffer, 0, count)
        }
    }

    @Suppress("DEPRECATION")
    private fun readArchivePackageName(file: File): String? =
        context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName

    private fun readableSourceRoots(): List<File> = buildList {
        addAll(providerRoots())
        add(File(android.os.Environment.getExternalStorageDirectory(), EXCHANGE_DIR_NAME))
    }

    private fun providerRoots(): List<File> = buildList {
        add(context.filesDir)
        add(context.cacheDir)
        context.externalCacheDir?.let(::add)
        context.getExternalFilesDirs(null).filterNotNull().forEach(::add)
    }

    private fun installCacheDir(): File = File(context.cacheDir, INSTALL_CACHE_DIR).apply { mkdirs() }

    private fun newStagedFile(): File = File(
        installCacheDir(),
        "install-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}.apk",
    )

    private fun cleanupOldStagedFiles() {
        val cutoff = System.currentTimeMillis() - STAGED_FILE_TTL_MS
        installCacheDir().listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private sealed interface PreparedApk {
        data class Ready(val file: File, val packageName: String?) : PreparedApk
        data class Error(val code: String, val message: String) : PreparedApk
    }

    private class ApkTooLargeException : java.io.IOException()

    private fun ApkFileValidation.toPreparedError(): PreparedApk.Error = when (this) {
        ApkFileValidation.Valid -> error("Valid is not an error")
        ApkFileValidation.OutsideAllowedRoots -> PreparedApk.Error("SOURCE_NOT_ALLOWED", "The path is outside RikkaHub's allowed install roots.")
        ApkFileValidation.Missing -> PreparedApk.Error("SOURCE_NOT_FOUND", "The APK file does not exist.")
        ApkFileValidation.NotAFile -> PreparedApk.Error("SOURCE_NOT_FILE", "The APK source is not a regular file.")
        ApkFileValidation.NotReadable -> PreparedApk.Error("SOURCE_UNREADABLE", "The APK file is not readable.")
        ApkFileValidation.WrongExtension -> PreparedApk.Error("WRONG_EXTENSION", "The source filename must end in .apk.")
        ApkFileValidation.Empty -> PreparedApk.Error("EMPTY_APK", "The APK file is empty.")
        ApkFileValidation.TooLarge -> PreparedApk.Error("APK_TOO_LARGE", "APK exceeds the 2 GiB limit.")
        ApkFileValidation.InvalidArchive -> PreparedApk.Error("INVALID_APK", "The file is not an APK-shaped ZIP archive.")
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val INSTALL_CACHE_DIR = "apk-installs"
        private const val EXCHANGE_DIR_NAME = "RikkaHubExchange"
        private const val STAGED_FILE_TTL_MS = 24L * 60L * 60L * 1000L
    }
}
