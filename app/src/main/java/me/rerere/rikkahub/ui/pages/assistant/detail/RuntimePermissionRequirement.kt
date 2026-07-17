package me.rerere.rikkahub.ui.pages.assistant.detail

internal enum class RuntimePermissionPolicy {
    ALL,
    ANY,
}

internal fun runtimePermissionRequirementSatisfied(
    required: List<String>,
    policy: RuntimePermissionPolicy,
    isGranted: (String) -> Boolean,
): Boolean = when {
    required.isEmpty() -> true
    policy == RuntimePermissionPolicy.ALL -> required.all(isGranted)
    else -> required.any(isGranted)
}
