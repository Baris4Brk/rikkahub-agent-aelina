package me.rerere.rikkahub.accessibility

/**
 * Immutable accessibility data exposed to the verified controller.
 *
 * Android AccessibilityNodeInfo instances deliberately never cross this seam: they are tied
 * to a particular window snapshot and become stale as soon as the UI changes.
 */
data class UiNodeSelector(
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val ancestor: UiNodeSelector? = null,
) {
    fun isValid(): Boolean =
        !viewId.isNullOrBlank() ||
            !text.isNullOrBlank() ||
            !contentDescription.isNullOrBlank() ||
            !className.isNullOrBlank()
}

data class UiNodeSnapshot(
    val traversalId: Int,
    val parentTraversalId: Int?,
    val viewId: String?,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val password: Boolean,
)

data class UiWindowSnapshot(
    val version: Long,
    val packageName: String,
    val title: String?,
    val className: String?,
    val nodes: List<UiNodeSnapshot>,
)

enum class UiScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

sealed interface UiExpectation {
    data class WindowMatches(
        val packageName: String? = null,
        val titleContains: String? = null,
    ) : UiExpectation

    data class NodePresence(
        val selector: UiNodeSelector,
        val present: Boolean,
    ) : UiExpectation

    /** A new accessibility window/content version must be observed after the action. */
    data object WindowChanged : UiExpectation
}

data class UiDriverActionResult(
    val accepted: Boolean,
    val code: String,
    val message: String,
)

enum class VerifiedUiStep {
    VALIDATE,
    WAIT_FOR_WINDOW,
    WAIT_FOR_NODE,
    SAFETY_CHECK,
    PERFORM_ACTION,
    VERIFY_ACTION,
    SCROLL,
}

data class VerifiedUiResult(
    val ok: Boolean,
    val code: String,
    val message: String,
    val step: VerifiedUiStep,
    val attempts: Int = 0,
    val scrolls: Int = 0,
    val window: UiWindowSnapshot? = null,
    val node: UiNodeSnapshot? = null,
)

/**
 * Platform seam. Every method that performs an action must acquire a fresh Android root and
 * resolve [selector] again; callers must never pass a retained Android node through this API.
 */
interface VerifiedAccessibilityDriver {
    fun isServiceAvailable(): Boolean

    suspend fun snapshot(): UiWindowSnapshot?

    suspend fun click(selector: UiNodeSelector, nth: Int): UiDriverActionResult

    suspend fun setText(
        selector: UiNodeSelector,
        nth: Int,
        text: String,
    ): UiDriverActionResult

    suspend fun scroll(
        direction: UiScrollDirection,
        containerSelector: UiNodeSelector?,
    ): UiDriverActionResult

    /** Suspends until a service event newer than [afterVersion] is published. */
    suspend fun awaitWindowChange(afterVersion: Long)
}

interface VerifiedAccessibilityController {
    suspend fun waitForWindow(
        expectation: UiExpectation.WindowMatches,
        timeoutMs: Long = DEFAULT_UI_WAIT_TIMEOUT_MS,
    ): VerifiedUiResult

    suspend fun waitForNode(
        selector: UiNodeSelector,
        present: Boolean = true,
        timeoutMs: Long = DEFAULT_UI_WAIT_TIMEOUT_MS,
    ): VerifiedUiResult

    suspend fun clickNodeVerified(
        selector: UiNodeSelector,
        nth: Int = 0,
        expectation: UiExpectation = UiExpectation.WindowChanged,
        timeoutMs: Long = DEFAULT_UI_WAIT_TIMEOUT_MS,
    ): VerifiedUiResult

    suspend fun setTextVerified(
        selector: UiNodeSelector,
        text: String,
        nth: Int = 0,
        timeoutMs: Long = DEFAULT_UI_WAIT_TIMEOUT_MS,
    ): VerifiedUiResult

    suspend fun scrollUntil(
        selector: UiNodeSelector,
        direction: UiScrollDirection,
        containerSelector: UiNodeSelector? = null,
        maxScrolls: Int = DEFAULT_MAX_SCROLLS,
        timeoutMs: Long = DEFAULT_UI_WAIT_TIMEOUT_MS,
    ): VerifiedUiResult
}

const val DEFAULT_UI_WAIT_TIMEOUT_MS = 10_000L
const val MAX_UI_WAIT_TIMEOUT_MS = 30_000L
const val MAX_CLICK_RETRIES = 2
const val DEFAULT_MAX_SCROLLS = 8
const val MAX_SCROLLS = 20

