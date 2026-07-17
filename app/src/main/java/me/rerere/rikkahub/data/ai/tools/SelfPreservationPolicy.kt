package me.rerere.rikkahub.data.ai.tools

/**
 * Permanent floor that prevents model-driven tools from disabling RikkaHub or mutating
 * its durable private state. Callers receive a stable reason and never the raw command.
 */
class SelfPreservationPolicy(
    applicationId: String,
    appDataRoots: Set<String>,
) {
    data class Violation(
        val code: String,
        val reason: String,
    )

    private val protectedPackage = applicationId.trim().lowercase().also {
        require(it.isNotEmpty()) { "applicationId must not be blank" }
    }
    private val protectedPackagePattern =
        "(?<![a-z0-9_.])${Regex.escape(protectedPackage)}(?![a-z0-9_.])"

    private val protectedDataRoots = appDataRoots.mapTo(linkedSetOf()) { root ->
        normalizePath(root).also { require(it.startsWith('/')) { "appDataRoots must be absolute" } }
    }.also {
        require(it.isNotEmpty()) { "appDataRoots must not be empty" }
    }

    private val destructivePackageCommands = listOf(
        Regex(
            "\\b(?:pm|cmd\\s+package)\\s+" +
                "(?:uninstall(?:-existing)?|clear|disable(?:-user)?|suspend|hide|revoke|" +
                "set-component-enabled-setting|set-distracting-restriction)\\b" +
                "[^\\n;&|]*$protectedPackagePattern",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            "\\b(?:am|cmd\\s+activity)\\s+force-stop\\b[^\\n;&|]*$protectedPackagePattern",
            RegexOption.IGNORE_CASE,
        ),
        Regex(
            "\\bappops\\s+(?:set|reset)\\b[^\\n;&|]*$protectedPackagePattern",
            RegexOption.IGNORE_CASE,
        ),
    )

    private val directMutationCommand = Regex(
        "(?:^|[;&|\\n]|\\$\\()\\s*" +
            "(?:(?:sudo|exec|nohup|setsid|time)\\s+)*(?:busybox\\s+)?" +
            "(?:[\\w./_-]*/)?(?:rm|unlink|rmdir|mv|cp|truncate|touch|chmod|chown|ln)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val sqliteMutation = Regex(
        "\\b(?:drop|delete|update|insert|replace|alter|vacuum|reindex|create|attach|detach)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Package-level seam for structured package mutation tools. */
    fun checkPackageMutation(targetPackage: String?): Violation? {
        if (!targetPackage.orEmpty().trim().equals(protectedPackage, ignoreCase = true)) return null
        return packageViolation()
    }

    /** Path-level seam for file tools that already know an operation is mutating. */
    fun checkAppPrivateMutation(path: String?): Violation? {
        val normalized = path?.let(::normalizePath) ?: return null
        if (!isProtectedDataPath(normalized)) return null
        return dataViolation()
    }

    fun checkShellCommand(command: String?): Violation? {
        if (command.isNullOrBlank()) return null
        if (destructivePackageCommands.any { it.containsMatchIn(command) }) return packageViolation()

        val mutatesData = command.lowercase()
            .split(Regex("[;&|\\n]+"))
            .any { segment ->
                containsProtectedDataReference(segment) && isMutatingShellSegment(segment)
            }
        return if (mutatesData) dataViolation() else null
    }

    private fun isMutatingShellSegment(segment: String): Boolean =
        directMutationCommand.containsMatchIn(segment) ||
            Regex(">>?\\s*['\"]?/data/", RegexOption.IGNORE_CASE).containsMatchIn(segment) ||
            Regex("\\bdd\\b[^\\n]*\\bof\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(segment) ||
            Regex("\\btee\\b", RegexOption.IGNORE_CASE).containsMatchIn(segment) ||
            (Regex("\\bsqlite3\\b", RegexOption.IGNORE_CASE).containsMatchIn(segment) &&
                sqliteMutation.containsMatchIn(segment))

    private fun containsProtectedDataReference(segment: String): Boolean = protectedDataRoots.any { root ->
        containsProtectedDataReference(segment, root)
    }

    private fun containsProtectedDataReference(segment: String, root: String): Boolean {
        var fromIndex = 0
        while (true) {
            val index = segment.indexOf(root, fromIndex)
            if (index < 0) return false
            val beforeIsBoundary = index == 0 || !segment[index - 1].isPathCharacter()
            if (beforeIsBoundary) {
                val end = segment.indexOfAny(PATH_TERMINATORS, startIndex = index)
                    .let { if (it < 0) segment.length else it }
                if (isProtectedDataPath(normalizePath(segment.substring(index, end)))) {
                    return true
                }
            }
            fromIndex = index + root.length
        }
    }

    private fun isProtectedDataPath(path: String): Boolean = protectedDataRoots.any { root ->
        path == root || PROTECTED_RELATIVE_ROOTS.any { relative ->
            path == "$root/$relative" || path.startsWith("$root/$relative/")
        }
    }

    private fun packageViolation() = Violation(
        code = "SELF_PRESERVATION_PACKAGE",
        reason = "The assistant cannot modify its own package, components, permissions, or data.",
    )

    private fun dataViolation() = Violation(
        code = "SELF_PRESERVATION_DATA",
        reason = "The assistant cannot mutate RikkaHub's core private data.",
    )

    private fun Char.isPathCharacter(): Boolean = isLetterOrDigit() || this == '_' || this == '.' || this == '-'

    companion object {
        private val PROTECTED_RELATIVE_ROOTS = setOf(
            "databases",
            "datastore",
            "shared_prefs",
            "no_backup",
            "files/datastore",
        )
        private val PATH_TERMINATORS = charArrayOf(
            ' ', '\t', '\r', '\n', '\'', '"', ';', '&', '|', '>', '<', '`',
        )

        fun forApplication(applicationId: String): SelfPreservationPolicy = SelfPreservationPolicy(
            applicationId = applicationId,
            appDataRoots = setOf(
                "/data/data/$applicationId",
                "/data/user/0/$applicationId",
                "/data/user_de/0/$applicationId",
            ),
        )

        private fun normalizePath(raw: String): String {
            val replaced = raw.trim().replace('\\', '/').lowercase()
            val absolute = replaced.startsWith('/')
            val segments = ArrayDeque<String>()
            replaced.split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (segments.isNotEmpty()) segments.removeLast()
                    else -> segments.addLast(segment)
                }
            }
            val joined = segments.joinToString("/")
            return if (absolute) "/$joined" else joined
        }
    }
}
