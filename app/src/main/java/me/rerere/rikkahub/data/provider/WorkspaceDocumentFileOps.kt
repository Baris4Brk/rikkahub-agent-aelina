package me.rerere.rikkahub.data.provider

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Transaction-like file operations used by the exported Workspace DocumentsProvider. */
internal object WorkspaceDocumentFileOps {
    fun copyVerified(
        source: File,
        sourceBase: File,
        destination: File,
        destinationBase: File,
    ): File {
        val safeSource = requireContained(sourceBase, source, mustExist = true)
        val safeDestination = requireContained(destinationBase, destination, mustExist = false)
        require(!safeDestination.exists()) { "Destination already exists" }
        require(!safeSource.name.startsWith(INTERNAL_PREFIX)) { "Internal transaction files are not documents" }
        require(!isInside(safeDestination.parentFile, safeSource)) { "Cannot copy a directory into itself" }

        val temp = File(
            requireNotNull(safeDestination.parentFile),
            "$INTERNAL_PREFIX-copy-${UUID.randomUUID()}",
        )
        try {
            copyTree(safeSource, temp, safeSource)
            require(equivalentTree(safeSource, temp)) { "Copied document verification failed" }
            moveAtomically(temp, safeDestination)
            return safeDestination
        } catch (failure: Throwable) {
            deleteContained(destinationBase, temp)
            throw failure
        }
    }

    fun moveVerified(
        source: File,
        sourceBase: File,
        destination: File,
        destinationBase: File,
    ): File {
        val safeSource = requireContained(sourceBase, source, mustExist = true)
        val safeDestination = requireContained(destinationBase, destination, mustExist = false)
        require(!safeDestination.exists()) { "Destination already exists" }
        require(!isInside(safeDestination.parentFile, safeSource)) { "Cannot move a directory into itself" }

        if (sourceBase.canonicalFile == destinationBase.canonicalFile) {
            moveAtomically(safeSource, safeDestination)
            return safeDestination
        }

        copyVerified(safeSource, sourceBase, safeDestination, destinationBase)
        val tombstone = File(
            requireNotNull(safeSource.parentFile),
            "$INTERNAL_PREFIX-move-${UUID.randomUUID()}",
        )
        if (!safeSource.renameTo(tombstone)) {
            deleteContained(destinationBase, safeDestination)
            error("Unable to detach the source after copying")
        }
        // The source is now atomically absent. A failed physical cleanup leaves only a hidden
        // transaction file and never risks deleting the verified destination.
        deleteContained(sourceBase, tombstone)
        return safeDestination
    }

    private fun copyTree(source: File, destination: File, sourceRoot: File) {
        requireContained(sourceRoot, source, mustExist = true)
        require(!Files.isSymbolicLink(source.toPath())) { "Symbolic links are not exported through SAF" }
        if (source.isDirectory) {
            require(destination.mkdir()) { "Unable to create destination directory" }
            source.listFiles()
                .orEmpty()
                .filterNot { it.name.startsWith(INTERNAL_PREFIX) }
                .forEach { child -> copyTree(child, File(destination, child.name), sourceRoot) }
        } else {
            source.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
        destination.setLastModified(source.lastModified())
    }

    private fun equivalentTree(source: File, destination: File): Boolean {
        if (source.isDirectory != destination.isDirectory) return false
        if (source.isFile) {
            return source.length() == destination.length() && digest(source) == digest(destination)
        }
        val sourceChildren = source.listFiles()
            .orEmpty()
            .filterNot { it.name.startsWith(INTERNAL_PREFIX) }
            .associateBy { it.name }
        val destinationChildren = destination.listFiles().orEmpty().associateBy { it.name }
        return sourceChildren.keys == destinationChildren.keys && sourceChildren.all { (name, child) ->
            equivalentTree(child, destinationChildren.getValue(name))
        }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun moveAtomically(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        }
    }

    private fun deleteContained(base: File, target: File): Boolean {
        val safe = runCatching { requireContained(base, target, mustExist = false) }.getOrNull()
            ?: return false
        return !safe.exists() || safe.deleteRecursively()
    }

    private fun requireContained(base: File, target: File, mustExist: Boolean): File {
        val safeBase = base.canonicalFile
        val safeTarget = target.canonicalFile
        require(safeTarget.path == safeBase.path || safeTarget.path.startsWith(safeBase.path + File.separator)) {
            "Document path escapes its Workspace"
        }
        if (mustExist) require(safeTarget.exists()) { "Document does not exist" }
        return safeTarget
    }

    private fun isInside(candidate: File?, parent: File): Boolean {
        if (!parent.isDirectory || candidate == null) return false
        val candidatePath = candidate.canonicalFile.path
        val parentPath = parent.canonicalFile.path
        return candidatePath == parentPath || candidatePath.startsWith(parentPath + File.separator)
    }

    private const val INTERNAL_PREFIX = ".l2s."
}
