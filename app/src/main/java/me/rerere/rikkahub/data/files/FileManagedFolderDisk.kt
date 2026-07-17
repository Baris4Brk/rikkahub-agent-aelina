package me.rerere.rikkahub.data.files

import java.io.File

internal class FileManagedFolderDisk(
    rootDirectory: File,
    private val mimeType: (File) -> String,
) : ManagedFolderDisk {
    private val root = rootDirectory.canonicalFile

    override fun scan(folder: ManagedFolder): ManagedFolderScan = runCatching {
        val directory = directory(folder) ?: return ManagedFolderScan.UnsafePath
        if (!directory.exists()) return ManagedFolderScan.DirectoryMissing
        if (!directory.isDirectory) return ManagedFolderScan.Failed
        val entries = directory.listFiles() ?: return ManagedFolderScan.Failed
        val files = buildList {
            entries.forEach { entry ->
                val canonical = entry.canonicalFile
                if (!canonical.toPath().startsWith(directory.toPath())) {
                    return ManagedFolderScan.UnsafePath
                }
                if (canonical.isFile) {
                    add(
                        ManagedFolderDiskFile(
                            relativePath = "${folder.directoryName}/${entry.name}",
                            displayName = entry.name,
                            mimeType = mimeType(canonical),
                            sizeBytes = canonical.length(),
                            lastModifiedMillis = canonical.lastModified(),
                        ),
                    )
                }
            }
        }
        ManagedFolderScan.Success(files)
    }.getOrElse { ManagedFolderScan.Failed }

    override fun isSafePath(folder: ManagedFolder, relativePath: String): Boolean = runCatching {
        val directory = directory(folder) ?: return false
        if (!relativePath.replace('\\', '/').startsWith("${folder.directoryName}/")) return false
        val target = File(root, relativePath).canonicalFile
        target != directory &&
            target.parentFile == directory &&
            target.toPath().startsWith(directory.toPath())
    }.getOrDefault(false)

    override fun delete(
        folder: ManagedFolder,
        relativePath: String,
    ): ManagedFileDeleteStatus {
        if (!isSafePath(folder, relativePath)) return ManagedFileDeleteStatus.UnsafePath
        return runCatching {
            val target = File(root, relativePath).canonicalFile
            when {
                !target.exists() -> ManagedFileDeleteStatus.Missing
                !target.isFile -> ManagedFileDeleteStatus.Failed
                target.delete() -> ManagedFileDeleteStatus.Deleted
                else -> ManagedFileDeleteStatus.Failed
            }
        }.getOrDefault(ManagedFileDeleteStatus.Failed)
    }

    private fun directory(folder: ManagedFolder): File? {
        val directory = File(root, folder.directoryName).canonicalFile
        return directory.takeIf { it.toPath().startsWith(root.toPath()) && it != root }
    }
}
