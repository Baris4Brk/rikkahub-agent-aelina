package me.rerere.rikkahub.service

/**
 * Pure helpers for editing Android's colon-delimited enabled accessibility-service setting.
 * Entries belonging to other apps are deliberately preserved byte-for-byte after trimming.
 */
internal object AccessibilityServiceListPolicy {
    fun contains(raw: String?, target: String): Boolean =
        entries(raw).any { componentEquals(it, target) }

    fun add(raw: String?, target: String): String {
        val result = mutableListOf<String>()
        var targetAdded = false
        entries(raw).forEach { entry ->
            if (componentEquals(entry, target)) {
                if (!targetAdded) {
                    result += target
                    targetAdded = true
                }
            } else {
                result += entry
            }
        }
        if (!targetAdded) result += target
        return result.joinToString(":")
    }

    fun remove(raw: String?, target: String): String = entries(raw)
        .filterNot { componentEquals(it, target) }
        .joinToString(":")

    private fun entries(raw: String?): List<String> = raw
        ?.takeUnless { it.trim().equals("null", ignoreCase = true) }
        .orEmpty()
        .split(':')
        .map(String::trim)
        .filter(String::isNotEmpty)

    private fun componentEquals(left: String, right: String): Boolean {
        if (left.equals(right, ignoreCase = true)) return true
        val leftComponent = canonicalComponent(left) ?: return false
        val rightComponent = canonicalComponent(right) ?: return false
        return leftComponent.first.equals(rightComponent.first, ignoreCase = true) &&
            leftComponent.second.equals(rightComponent.second, ignoreCase = true)
    }

    private fun canonicalComponent(value: String): Pair<String, String>? {
        val slash = value.indexOf('/')
        if (slash <= 0 || slash == value.lastIndex) return null
        val packageName = value.substring(0, slash)
        val rawClassName = value.substring(slash + 1)
        val className = if (rawClassName.startsWith('.')) {
            packageName + rawClassName
        } else {
            rawClassName
        }
        return packageName to className
    }
}
