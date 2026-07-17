package me.rerere.rikkahub.data.packageinstaller

import java.io.File
import java.util.zip.ZipException
import java.util.zip.ZipFile

fun interface ApkInstallController {
    suspend fun requestInstall(source: String): ApkInstallResult
}

sealed interface ApkInstallResult {
    data class Launched(val packageName: String?) : ApkInstallResult
    data class ActionRequired(val message: String) : ApkInstallResult
    data class Rejected(val code: String, val message: String) : ApkInstallResult
}

sealed interface ApkFileValidation {
    data object Valid : ApkFileValidation
    data object OutsideAllowedRoots : ApkFileValidation
    data object Missing : ApkFileValidation
    data object NotAFile : ApkFileValidation
    data object NotReadable : ApkFileValidation
    data object WrongExtension : ApkFileValidation
    data object Empty : ApkFileValidation
    data object TooLarge : ApkFileValidation
    data object InvalidArchive : ApkFileValidation
}

object ApkFileValidator {
    const val MAX_APK_BYTES: Long = 2L * 1024L * 1024L * 1024L

    fun validate(file: File, allowedRoots: List<File>): ApkFileValidation {
        val canonical = runCatching { file.canonicalFile }.getOrElse {
            return ApkFileValidation.OutsideAllowedRoots
        }
        val insideAllowedRoot = allowedRoots.any { root ->
            val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
            canonical == canonicalRoot || canonical.toPath().startsWith(canonicalRoot.toPath())
        }
        if (!insideAllowedRoot) return ApkFileValidation.OutsideAllowedRoots
        if (!canonical.exists()) return ApkFileValidation.Missing
        if (!canonical.isFile) return ApkFileValidation.NotAFile
        if (!canonical.canRead()) return ApkFileValidation.NotReadable
        if (!canonical.name.endsWith(".apk", ignoreCase = true)) return ApkFileValidation.WrongExtension
        if (canonical.length() <= 0L) return ApkFileValidation.Empty
        if (canonical.length() > MAX_APK_BYTES) return ApkFileValidation.TooLarge
        return try {
            ZipFile(canonical).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") == null) ApkFileValidation.InvalidArchive
                else ApkFileValidation.Valid
            }
        } catch (_: ZipException) {
            ApkFileValidation.InvalidArchive
        } catch (_: java.io.IOException) {
            ApkFileValidation.InvalidArchive
        }
    }
}
