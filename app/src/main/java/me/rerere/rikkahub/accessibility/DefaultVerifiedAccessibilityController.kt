package me.rerere.rikkahub.accessibility

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class DefaultVerifiedAccessibilityController(
    private val driver: VerifiedAccessibilityDriver,
) : VerifiedAccessibilityController {

    override suspend fun waitForWindow(
        expectation: UiExpectation.WindowMatches,
        timeoutMs: Long,
    ): VerifiedUiResult {
        validateTimeout(timeoutMs)?.let { return it }
        validateWindowExpectation(expectation)?.let { return it }
        return awaitResult(timeoutMs, VerifiedUiStep.WAIT_FOR_WINDOW, "WINDOW_WAIT_TIMEOUT") {
            val window = requireWindowOrAwait() ?: return@awaitResult null
            safetyFailure(window, UiAutomationAction.WAIT)?.let { return@awaitResult it }
            if (windowMatches(window, expectation)) {
                success(
                    code = "WINDOW_MATCHED",
                    message = "The requested window is active.",
                    step = VerifiedUiStep.WAIT_FOR_WINDOW,
                    window = window,
                )
            } else {
                driver.awaitWindowChange(window.version)
                null
            }
        }
    }

    override suspend fun waitForNode(
        selector: UiNodeSelector,
        present: Boolean,
        timeoutMs: Long,
    ): VerifiedUiResult {
        validateSelector(selector)?.let { return it }
        validateTimeout(timeoutMs)?.let { return it }
        return awaitResult(timeoutMs, VerifiedUiStep.WAIT_FOR_NODE, "NODE_WAIT_TIMEOUT") {
            val window = requireWindowOrAwait() ?: return@awaitResult null
            safetyFailure(window, UiAutomationAction.WAIT)?.let { return@awaitResult it }
            val match = window.findMatches(selector).firstOrNull()
            if ((match != null) == present) {
                success(
                    code = if (present) "NODE_FOUND" else "NODE_ABSENT",
                    message = if (present) "The requested node is present." else "The requested node is absent.",
                    step = VerifiedUiStep.WAIT_FOR_NODE,
                    window = window,
                    node = match,
                )
            } else {
                driver.awaitWindowChange(window.version)
                null
            }
        }
    }

    override suspend fun clickNodeVerified(
        selector: UiNodeSelector,
        nth: Int,
        expectation: UiExpectation,
        timeoutMs: Long,
    ): VerifiedUiResult {
        validateSelector(selector)?.let { return it }
        validateExpectation(expectation)?.let { return it }
        validateTimeout(timeoutMs)?.let { return it }
        if (nth < 0) return invalid("nth must be at least 0")
        if (!driver.isServiceAvailable()) return serviceUnavailable()

        return try {
            withTimeout(timeoutMs) {
                var actionAttempts = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val before = requireWindowOrAwait()
                    if (before == null) {
                        if (!driver.isServiceAvailable()) return@withTimeout serviceUnavailable()
                        continue
                    }
                    safetyFailure(before, UiAutomationAction.CLICK, selector)?.let { return@withTimeout it }
                    val target = before.findMatches(selector).getOrNull(nth)
                    if (target == null) {
                        driver.awaitWindowChange(before.version)
                        continue
                    }

                    actionAttempts++
                    val action = driver.click(selector, nth)
                    if (!action.accepted) {
                        if (actionAttempts <= MAX_CLICK_RETRIES) {
                            waitBrieflyForChange(before.version)
                            continue
                        }
                        return@withTimeout failure(
                            code = action.code,
                            message = action.message,
                            step = VerifiedUiStep.PERFORM_ACTION,
                            attempts = actionAttempts,
                            window = before,
                            node = target,
                        )
                    }

                    // An accepted click is never sent twice: absence of an observed effect is
                    // uncertainty, not permission to duplicate a potentially destructive tap.
                    return@withTimeout verifyExpectation(
                        expectation = expectation,
                        baselineVersion = before.version,
                        attempts = actionAttempts,
                    )
                }
                @Suppress("UNREACHABLE_CODE")
                failure("CLICK_FAILED", "The click could not be completed.", VerifiedUiStep.PERFORM_ACTION)
            }
        } catch (_: TimeoutCancellationException) {
            failure(
                code = "CLICK_VERIFY_TIMEOUT",
                message = "The click or its expected UI effect was not observed before timeout.",
                step = VerifiedUiStep.VERIFY_ACTION,
            )
        }
    }

    override suspend fun setTextVerified(
        selector: UiNodeSelector,
        text: String,
        nth: Int,
        timeoutMs: Long,
    ): VerifiedUiResult {
        validateSelector(selector)?.let { return it }
        validateTimeout(timeoutMs)?.let { return it }
        if (nth < 0) return invalid("nth must be at least 0")
        if (text.indexOf('\u0000') >= 0) return invalid("text must not contain NUL")
        if (!driver.isServiceAvailable()) return serviceUnavailable()

        return try {
            withTimeout(timeoutMs) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val before = requireWindowOrAwait()
                    if (before == null) {
                        if (!driver.isServiceAvailable()) return@withTimeout serviceUnavailable()
                        continue
                    }
                    safetyFailure(before, UiAutomationAction.SET_TEXT, selector)?.let { return@withTimeout it }
                    val target = before.findMatches(selector).getOrNull(nth)
                    if (target == null) {
                        driver.awaitWindowChange(before.version)
                        continue
                    }
                    val action = driver.setText(selector, nth, text)
                    if (!action.accepted) {
                        return@withTimeout failure(
                            action.code,
                            action.message,
                            VerifiedUiStep.PERFORM_ACTION,
                            attempts = 1,
                            window = before,
                            node = target,
                        )
                    }

                    val verificationSelector = selector.forTextVerification(text)
                    while (true) {
                        val actual = driver.snapshot()
                        if (actual != null) {
                            safetyFailure(actual, UiAutomationAction.SET_TEXT, selector)?.let {
                                return@withTimeout it
                            }
                            val verifiedNode = actual.findMatches(verificationSelector).getOrNull(nth)
                            if (verifiedNode?.text == text) {
                                return@withTimeout success(
                                    code = "TEXT_VERIFIED",
                                    message = "Text was set and verified from a fresh UI snapshot.",
                                    step = VerifiedUiStep.VERIFY_ACTION,
                                    attempts = 1,
                                    window = actual,
                                    node = verifiedNode,
                                )
                            }
                            driver.awaitWindowChange(actual.version)
                        } else {
                            if (!driver.isServiceAvailable()) return@withTimeout serviceUnavailable()
                            driver.awaitWindowChange(-1L)
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                failure("TEXT_SET_FAILED", "Text could not be set.", VerifiedUiStep.PERFORM_ACTION)
            }
        } catch (_: TimeoutCancellationException) {
            failure(
                code = "TEXT_VERIFY_TIMEOUT",
                message = "The requested text was not observed before timeout.",
                step = VerifiedUiStep.VERIFY_ACTION,
            )
        }
    }

    override suspend fun scrollUntil(
        selector: UiNodeSelector,
        direction: UiScrollDirection,
        containerSelector: UiNodeSelector?,
        maxScrolls: Int,
        timeoutMs: Long,
    ): VerifiedUiResult {
        validateSelector(selector)?.let { return it }
        containerSelector?.let { validateSelector(it) }?.let { return it }
        validateTimeout(timeoutMs)?.let { return it }
        if (maxScrolls !in 1..MAX_SCROLLS) return invalid("maxScrolls must be between 1 and $MAX_SCROLLS")
        if (!driver.isServiceAvailable()) return serviceUnavailable()

        return try {
            withTimeout(timeoutMs) {
                var scrolls = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val window = requireWindowOrAwait()
                    if (window == null) {
                        if (!driver.isServiceAvailable()) return@withTimeout serviceUnavailable()
                        continue
                    }
                    safetyFailure(window, UiAutomationAction.SCROLL, selector)?.let { return@withTimeout it }
                    val match = window.findMatches(selector).firstOrNull()
                    if (match != null) {
                        return@withTimeout success(
                            code = "NODE_FOUND",
                            message = "The target node was found after $scrolls scroll action(s).",
                            step = VerifiedUiStep.SCROLL,
                            scrolls = scrolls,
                            window = window,
                            node = match,
                        )
                    }
                    if (scrolls >= maxScrolls) {
                        return@withTimeout failure(
                            code = "SCROLL_LIMIT_REACHED",
                            message = "The target node was not found within the scroll limit.",
                            step = VerifiedUiStep.SCROLL,
                            scrolls = scrolls,
                            window = window,
                        )
                    }
                    val action = driver.scroll(direction, containerSelector)
                    if (!action.accepted) {
                        return@withTimeout failure(
                            code = action.code,
                            message = action.message,
                            step = VerifiedUiStep.SCROLL,
                            scrolls = scrolls,
                            window = window,
                        )
                    }
                    scrolls++
                    waitBrieflyForChange(window.version)
                }
                @Suppress("UNREACHABLE_CODE")
                failure("SCROLL_FAILED", "Scrolling failed.", VerifiedUiStep.SCROLL)
            }
        } catch (_: TimeoutCancellationException) {
            failure(
                code = "SCROLL_TIMEOUT",
                message = "The target node was not found before timeout.",
                step = VerifiedUiStep.SCROLL,
            )
        }
    }

    private suspend fun verifyExpectation(
        expectation: UiExpectation,
        baselineVersion: Long,
        attempts: Int,
    ): VerifiedUiResult {
        while (true) {
            currentCoroutineContext().ensureActive()
            val window = driver.snapshot()
            if (window == null) {
                if (!driver.isServiceAvailable()) return serviceUnavailable()
                driver.awaitWindowChange(baselineVersion)
                continue
            }
            safetyFailure(window, UiAutomationAction.WAIT)?.let { return it }
            val satisfied = when (expectation) {
                UiExpectation.WindowChanged -> window.version > baselineVersion
                is UiExpectation.WindowMatches -> windowMatches(window, expectation)
                is UiExpectation.NodePresence ->
                    (window.findMatches(expectation.selector).isNotEmpty()) == expectation.present
            }
            if (satisfied) {
                return success(
                    code = "CLICK_EFFECT_VERIFIED",
                    message = "The click was accepted and its expected UI effect was observed.",
                    step = VerifiedUiStep.VERIFY_ACTION,
                    attempts = attempts,
                    window = window,
                )
            }
            driver.awaitWindowChange(window.version)
        }
    }

    private suspend fun requireWindowOrAwait(): UiWindowSnapshot? {
        if (!driver.isServiceAvailable()) return null
        val window = driver.snapshot()
        if (window == null) driver.awaitWindowChange(-1L)
        return window
    }

    private suspend fun waitBrieflyForChange(version: Long) {
        withTimeoutOrNull(ACTION_SETTLE_TIMEOUT_MS) { driver.awaitWindowChange(version) }
    }

    private suspend fun awaitResult(
        timeoutMs: Long,
        step: VerifiedUiStep,
        timeoutCode: String,
        block: suspend () -> VerifiedUiResult?,
    ): VerifiedUiResult {
        if (!driver.isServiceAvailable()) return serviceUnavailable()
        return try {
            withTimeout(timeoutMs) {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    block()?.let { return@withTimeout it }
                    if (!driver.isServiceAvailable()) return@withTimeout serviceUnavailable()
                }
                @Suppress("UNREACHABLE_CODE")
                failure(timeoutCode, "UI wait timed out.", step)
            }
        } catch (_: TimeoutCancellationException) {
            failure(timeoutCode, "UI condition was not observed before timeout.", step)
        }
    }

    private fun safetyFailure(
        window: UiWindowSnapshot,
        action: UiAutomationAction,
        selector: UiNodeSelector? = null,
    ): VerifiedUiResult? {
        val decision = SensitiveUiPolicy.check(window, action, selector)
        return if (decision.allowed) null else failure(
            decision.code,
            decision.message,
            VerifiedUiStep.SAFETY_CHECK,
            window = window,
        )
    }

    private fun validateSelector(selector: UiNodeSelector): VerifiedUiResult? {
        if (!selector.isValid()) return invalid("selector must contain viewId, text, contentDescription, or className")
        if (selector.ancestor != null && !selector.ancestor.isValid()) {
            return invalid("ancestor selector must contain at least one selector field")
        }
        return null
    }

    private fun validateExpectation(expectation: UiExpectation): VerifiedUiResult? = when (expectation) {
        UiExpectation.WindowChanged -> null
        is UiExpectation.WindowMatches -> validateWindowExpectation(expectation)
        is UiExpectation.NodePresence -> validateSelector(expectation.selector)
    }

    private fun validateWindowExpectation(expectation: UiExpectation.WindowMatches): VerifiedUiResult? {
        return if (expectation.packageName.isNullOrBlank() && expectation.titleContains.isNullOrBlank()) {
            invalid("window expectation requires packageName or titleContains")
        } else null
    }

    private fun validateTimeout(timeoutMs: Long): VerifiedUiResult? =
        if (timeoutMs !in 1..MAX_UI_WAIT_TIMEOUT_MS) {
            invalid("timeoutMs must be between 1 and $MAX_UI_WAIT_TIMEOUT_MS")
        } else null

    private fun windowMatches(window: UiWindowSnapshot, expectation: UiExpectation.WindowMatches): Boolean {
        if (!expectation.packageName.isNullOrBlank() && window.packageName != expectation.packageName) return false
        if (!expectation.titleContains.isNullOrBlank() &&
            !window.title.orEmpty().contains(expectation.titleContains, ignoreCase = true)
        ) return false
        return true
    }

    private fun UiNodeSelector.forTextVerification(newText: String): UiNodeSelector {
        val hasStableNonTextField = !viewId.isNullOrBlank() ||
            !contentDescription.isNullOrBlank() ||
            !className.isNullOrBlank()
        return copy(text = if (hasStableNonTextField) null else newText)
    }

    private fun invalid(message: String) = failure("INVALID_ARGUMENT", message, VerifiedUiStep.VALIDATE)

    private fun serviceUnavailable() = failure(
        "ACCESSIBILITY_SERVICE_NOT_ACTIVE",
        "Enable and start the RikkaHub accessibility service.",
        VerifiedUiStep.WAIT_FOR_WINDOW,
    )

    private fun success(
        code: String,
        message: String,
        step: VerifiedUiStep,
        attempts: Int = 0,
        scrolls: Int = 0,
        window: UiWindowSnapshot? = null,
        node: UiNodeSnapshot? = null,
    ) = VerifiedUiResult(true, code, message, step, attempts, scrolls, window, node)

    private fun failure(
        code: String,
        message: String,
        step: VerifiedUiStep,
        attempts: Int = 0,
        scrolls: Int = 0,
        window: UiWindowSnapshot? = null,
        node: UiNodeSnapshot? = null,
    ) = VerifiedUiResult(false, code, message, step, attempts, scrolls, window, node)

    private companion object {
        const val ACTION_SETTLE_TIMEOUT_MS = 750L
    }
}
