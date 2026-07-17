package me.rerere.rikkahub.accessibility

internal fun UiWindowSnapshot.findMatches(selector: UiNodeSelector): List<UiNodeSnapshot> {
    if (!selector.isValid()) return emptyList()
    val byId = nodes.associateBy(UiNodeSnapshot::traversalId)
    return nodes.filter { node ->
        node.matchesFields(selector) && selector.ancestor.let { ancestor ->
            ancestor == null || node.hasMatchingAncestor(ancestor, byId)
        }
    }
}

private fun UiNodeSnapshot.matchesFields(selector: UiNodeSelector): Boolean {
    // Keep this order aligned with the public contract. When several fields are provided,
    // the higher-priority field is checked first and the remaining fields refine the match.
    if (!selector.viewId.isNullOrBlank() && viewId != selector.viewId) return false
    if (!selector.text.isNullOrBlank() && text != selector.text) return false
    if (!selector.contentDescription.isNullOrBlank() &&
        contentDescription != selector.contentDescription
    ) return false
    if (!selector.className.isNullOrBlank() && className != selector.className) return false
    return true
}

private fun UiNodeSnapshot.hasMatchingAncestor(
    selector: UiNodeSelector,
    byId: Map<Int, UiNodeSnapshot>,
): Boolean {
    var parent = parentTraversalId?.let(byId::get)
    while (parent != null) {
        if (parent.matchesFields(selector)) {
            val nestedAncestor = selector.ancestor
            if (nestedAncestor == null || parent.hasMatchingAncestor(nestedAncestor, byId)) {
                return true
            }
        }
        parent = parent.parentTraversalId?.let(byId::get)
    }
    return false
}

