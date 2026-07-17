package me.rerere.rikkahub.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.service.RikkaAccessibilityService

/** Android adapter that resolves every selector against a newly acquired active-window root. */
class AndroidVerifiedAccessibilityDriver : VerifiedAccessibilityDriver {
    override fun isServiceAvailable(): Boolean = RikkaAccessibilityService.instance != null

    override suspend fun snapshot(): UiWindowSnapshot? {
        currentCoroutineContext().ensureActive()
        val service = RikkaAccessibilityService.instance ?: return null
        val root = service.rootInActiveWindow ?: return null
        return snapshotRoot(root, service.windowState.value.version)
    }

    override suspend fun click(selector: UiNodeSelector, nth: Int): UiDriverActionResult {
        currentCoroutineContext().ensureActive()
        return withFreshTarget(selector, nth, UiAutomationAction.CLICK) { target ->
            var clickable: AccessibilityNodeInfo? = target
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            if (clickable == null || !clickable.isEnabled) {
                UiDriverActionResult(false, "NO_CLICKABLE_TARGET", "No enabled clickable node or ancestor was found.")
            } else if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                UiDriverActionResult(true, "ACTION_ACCEPTED", "Android accepted the click action.")
            } else {
                UiDriverActionResult(false, "ACTION_REJECTED", "Android rejected the click action.")
            }
        }
    }

    override suspend fun setText(
        selector: UiNodeSelector,
        nth: Int,
        text: String,
    ): UiDriverActionResult {
        currentCoroutineContext().ensureActive()
        return withFreshTarget(selector, nth, UiAutomationAction.SET_TEXT) { target ->
            var editable: AccessibilityNodeInfo? = target
            while (editable != null && !editable.isEditable) editable = editable.parent
            if (editable == null || !editable.isEnabled) {
                UiDriverActionResult(false, "NODE_NOT_EDITABLE", "No enabled editable node or ancestor was found.")
            } else {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                if (editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    UiDriverActionResult(true, "ACTION_ACCEPTED", "Android accepted the text action.")
                } else {
                    UiDriverActionResult(false, "ACTION_REJECTED", "Android rejected the text action.")
                }
            }
        }
    }

    override suspend fun scroll(
        direction: UiScrollDirection,
        containerSelector: UiNodeSelector?,
    ): UiDriverActionResult {
        currentCoroutineContext().ensureActive()
        val service = RikkaAccessibilityService.instance
            ?: return UiDriverActionResult(false, "ACCESSIBILITY_SERVICE_NOT_ACTIVE", "Accessibility service is not active.")
        val root = service.rootInActiveWindow
            ?: return UiDriverActionResult(false, "NO_ACTIVE_WINDOW", "There is no active accessibility window.")
        val snapshot = snapshotRoot(root, service.windowState.value.version)
        SensitiveUiPolicy.check(snapshot, UiAutomationAction.SCROLL, containerSelector).let { safety ->
            if (!safety.allowed) return UiDriverActionResult(false, safety.code, safety.message)
        }
        val targetId = if (containerSelector == null) {
            snapshot.nodes.firstOrNull { it.scrollable }?.traversalId
        } else {
            snapshot.findMatches(containerSelector).firstOrNull { it.scrollable }?.traversalId
        } ?: return UiDriverActionResult(false, "NO_SCROLLABLE_TARGET", "No matching scrollable node was found.")
        val target = findByTraversalId(root, targetId)
            ?: return UiDriverActionResult(false, "STALE_NODE", "The scroll target changed before the action.")
        val action = when (direction) {
            UiScrollDirection.DOWN, UiScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            UiScrollDirection.UP, UiScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return if (target.performAction(action)) {
            UiDriverActionResult(true, "ACTION_ACCEPTED", "Android accepted the scroll action.")
        } else {
            UiDriverActionResult(false, "SCROLL_REJECTED", "Android rejected the scroll action.")
        }
    }

    override suspend fun awaitWindowChange(afterVersion: Long) {
        val service = RikkaAccessibilityService.instance ?: return
        // For a real snapshot version, keep the caller's exact baseline so an event racing
        // between snapshot() and first() is observed immediately instead of being skipped.
        // -1 means there was no active root, so wait for the next event from "now".
        val baseline = if (afterVersion < 0L) service.windowState.value.version else afterVersion
        service.windowState.first { it.version > baseline }
    }

    private inline fun withFreshTarget(
        selector: UiNodeSelector,
        nth: Int,
        automationAction: UiAutomationAction,
        action: (AccessibilityNodeInfo) -> UiDriverActionResult,
    ): UiDriverActionResult {
        val service = RikkaAccessibilityService.instance
            ?: return UiDriverActionResult(false, "ACCESSIBILITY_SERVICE_NOT_ACTIVE", "Accessibility service is not active.")
        val root = service.rootInActiveWindow
            ?: return UiDriverActionResult(false, "NO_ACTIVE_WINDOW", "There is no active accessibility window.")
        val snapshot = snapshotRoot(root, service.windowState.value.version)
        SensitiveUiPolicy.check(snapshot, automationAction, selector).let { safety ->
            if (!safety.allowed) return UiDriverActionResult(false, safety.code, safety.message)
        }
        val targetId = snapshot.findMatches(selector).getOrNull(nth)?.traversalId
            ?: return UiDriverActionResult(false, "STALE_NODE", "The target changed before the action.")
        val target = findByTraversalId(root, targetId)
            ?: return UiDriverActionResult(false, "STALE_NODE", "The target changed before the action.")
        return action(target)
    }

    private fun snapshotRoot(root: AccessibilityNodeInfo, version: Long): UiWindowSnapshot {
        val nodes = ArrayList<UiNodeSnapshot>()
        var nextId = 0
        fun visit(node: AccessibilityNodeInfo, parentId: Int?) {
            if (nodes.size >= MAX_SNAPSHOT_NODES) return
            val id = nextId++
            nodes += UiNodeSnapshot(
                traversalId = id,
                parentTraversalId = parentId,
                viewId = node.viewIdResourceName,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                className = node.className?.toString(),
                clickable = node.isClickable,
                editable = node.isEditable,
                scrollable = node.isScrollable,
                enabled = node.isEnabled,
                password = node.isPassword,
            )
            for (index in 0 until node.childCount) {
                if (nodes.size >= MAX_SNAPSHOT_NODES) break
                node.getChild(index)?.let { visit(it, id) }
            }
        }
        visit(root, null)
        val state = RikkaAccessibilityService.instance?.windowState?.value
        return UiWindowSnapshot(
            version = version,
            packageName = root.packageName?.toString().orEmpty(),
            title = root.window?.title?.toString(),
            className = state?.className ?: root.className?.toString(),
            nodes = nodes,
        )
    }

    private fun findByTraversalId(root: AccessibilityNodeInfo, wantedId: Int): AccessibilityNodeInfo? {
        var nextId = 0
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val id = nextId++
            if (id == wantedId) return node
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                visit(child)?.let { return it }
            }
            return null
        }
        return visit(root)
    }

    private companion object {
        const val MAX_SNAPSHOT_NODES = 2_000
    }
}
