package me.rerere.workspace

import java.io.File

/**
 * Single source of truth for paths exposed inside the PRoot filesystem.
 *
 * The launcher and host-side file tools must share this table. Otherwise a command can write to
 * a bind mount such as /skills while workspace_read_file incorrectly looks inside the rootfs.
 */
class WorkspaceMountResolver(
    private val extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    private val sharedStorageBindMount: WorkspaceBindMount? = null,
) {
    fun bindMounts(filesDir: File, allowSharedStorage: Boolean): List<WorkspaceBindMount> = buildList {
        add(WorkspaceBindMount(filesDir, WORKSPACE_TARGET))
        addAll(extraBindMounts.filter { it.source.exists() })
        if (allowSharedStorage) {
            sharedStorageBindMount?.takeIf { it.source.exists() }?.let(::add)
        }
    }

    fun resolve(
        filesDir: File,
        linuxDir: File,
        path: String,
        allowSharedStorage: Boolean,
    ): File {
        val normalized = normalize(path)
        require(VIRTUAL_PREFIXES.none { normalized == it || normalized.startsWith("$it/") }) {
            "Virtual Rootfs path cannot be read as a regular file: $normalized. Use workspace_shell instead."
        }

        val mount = bindMounts(filesDir, allowSharedStorage)
            .sortedByDescending { normalizedTarget(it.target).length }
            .firstOrNull { candidate ->
                val target = normalizedTarget(candidate.target)
                normalized == target || normalized.startsWith("$target/")
            }
        if (mount != null) {
            val target = normalizedTarget(mount.target)
            return resolveInside(mount.source, normalized.removePrefix(target).trimStart('/'))
        }
        return resolveInside(linuxDir, normalized.trimStart('/'))
    }

    private fun normalize(path: String): String {
        require(path.startsWith('/')) { "Rootfs path must be absolute: $path" }
        require('\u0000' !in path) { "Rootfs path contains NUL" }
        val normalized = path.replace('\\', '/').trimEnd('/').ifBlank { "/" }
        require(normalized.split('/').none { it == ".." }) { "Rootfs path escapes its mount: $path" }
        return normalized
    }

    private fun resolveInside(root: File, relativePath: String): File {
        val canonicalRoot = root.canonicalFile
        val target = if (relativePath.isBlank()) canonicalRoot else File(canonicalRoot, relativePath).canonicalFile
        require(target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator)) {
            "Resolved path escapes its Rootfs mount"
        }
        return target
    }

    private fun normalizedTarget(target: String): String = target.trimEnd('/').ifBlank { "/" }

    private companion object {
        const val WORKSPACE_TARGET = "/workspace"
        val VIRTUAL_PREFIXES = listOf("/dev", "/proc", "/sys")
    }
}
