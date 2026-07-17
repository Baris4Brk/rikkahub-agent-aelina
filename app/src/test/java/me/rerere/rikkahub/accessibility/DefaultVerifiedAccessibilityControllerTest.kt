package me.rerere.rikkahub.accessibility

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultVerifiedAccessibilityControllerTest {
    private val selector = UiNodeSelector(viewId = "app:id/target")

    @Test
    fun `wait node observes a later fresh snapshot`() = runBlocking {
        val driver = FakeDriver(
            windows = listOf(window(1), window(2, node(viewId = "app:id/target"))),
        )
        val result = DefaultVerifiedAccessibilityController(driver)
            .waitForNode(selector, timeoutMs = 100)

        assertTrue(result.ok)
        assertEquals("NODE_FOUND", result.code)
        assertEquals(2, driver.snapshotCalls)
        assertEquals(1, driver.awaitCalls)
    }

    @Test
    fun `ancestor selector narrows the node match`() = runBlocking {
        val parent = node(id = 1, viewId = "app:id/container")
        val child = node(id = 2, parentId = 1, text = "Open")
        val driver = FakeDriver(listOf(window(1, parent, child)))
        val result = DefaultVerifiedAccessibilityController(driver).waitForNode(
            UiNodeSelector(
                text = "Open",
                ancestor = UiNodeSelector(viewId = "app:id/container"),
            ),
            timeoutMs = 100,
        )

        assertTrue(result.ok)
        assertEquals(2, result.node?.traversalId)
    }

    @Test
    fun `click retries two stale rejections then verifies fresh window`() = runBlocking {
        val driver = FakeDriver(
            windows = listOf(
                window(1, node(viewId = "app:id/target", clickable = true)),
                window(2, node(viewId = "app:id/target", clickable = true)),
                window(3, node(viewId = "app:id/target", clickable = true)),
                window(4),
            ),
            clickResults = ArrayDeque(
                listOf(
                    rejected("STALE_NODE"),
                    rejected("STALE_NODE"),
                    accepted(),
                )
            ),
        )
        val result = DefaultVerifiedAccessibilityController(driver).clickNodeVerified(
            selector = selector,
            expectation = UiExpectation.WindowChanged,
            timeoutMs = 1_000,
        )

        assertTrue(result.ok)
        assertEquals(3, result.attempts)
        assertEquals(3, driver.clickCalls)
    }

    @Test
    fun `accepted click is never dispatched again when verification times out`() = runBlocking {
        val driver = FakeDriver(
            windows = listOf(window(1, node(viewId = "app:id/target", clickable = true))),
            clickResults = ArrayDeque(listOf(accepted())),
        )
        val result = DefaultVerifiedAccessibilityController(driver).clickNodeVerified(
            selector = selector,
            expectation = UiExpectation.NodePresence(UiNodeSelector(text = "Never"), present = true),
            timeoutMs = 20,
        )

        assertFalse(result.ok)
        assertEquals("CLICK_VERIFY_TIMEOUT", result.code)
        assertEquals(1, driver.clickCalls)
    }

    @Test
    fun `set text verifies from a new snapshot without echoing value in message`() = runBlocking {
        val input = node(viewId = "app:id/input", text = "", editable = true)
        val updated = input.copy(text = "825104")
        val driver = FakeDriver(
            windows = listOf(window(1, input), window(2, updated)),
            setTextResults = ArrayDeque(listOf(accepted())),
            advanceAfterSetText = true,
        )
        val result = DefaultVerifiedAccessibilityController(driver).setTextVerified(
            selector = UiNodeSelector(viewId = "app:id/input"),
            text = "825104",
            timeoutMs = 100,
        )

        assertTrue(result.ok)
        assertEquals("TEXT_VERIFIED", result.code)
        assertFalse(result.message.contains("825104"))
        assertEquals(1, driver.setTextCalls)
    }

    @Test
    fun `scroll uses fresh snapshots until target appears`() = runBlocking {
        val scrollable = node(viewId = "app:id/list", scrollable = true)
        val driver = FakeDriver(
            windows = listOf(
                window(1, scrollable),
                window(2, scrollable),
                window(3, scrollable, node(id = 2, viewId = "app:id/target")),
            ),
            scrollResults = ArrayDeque(listOf(accepted(), accepted())),
            advanceAfterScroll = true,
        )
        val result = DefaultVerifiedAccessibilityController(driver).scrollUntil(
            selector = selector,
            direction = UiScrollDirection.DOWN,
            containerSelector = UiNodeSelector(viewId = "app:id/list"),
            maxScrolls = 8,
            timeoutMs = 1_000,
        )

        assertTrue(result.ok)
        assertEquals(2, result.scrolls)
        assertEquals(2, driver.scrollCalls)
    }

    @Test
    fun `scroll stops at configured limit`() = runBlocking {
        val driver = FakeDriver(
            windows = listOf(window(1), window(2), window(3)),
            scrollResults = ArrayDeque(listOf(accepted(), accepted())),
            advanceAfterScroll = true,
        )
        val result = DefaultVerifiedAccessibilityController(driver).scrollUntil(
            selector = selector,
            direction = UiScrollDirection.DOWN,
            maxScrolls = 2,
            timeoutMs = 1_000,
        )

        assertFalse(result.ok)
        assertEquals("SCROLL_LIMIT_REACHED", result.code)
        assertEquals(2, result.scrolls)
    }

    @Test
    fun `protected payment page blocks action before driver click`() = runBlocking {
        val driver = FakeDriver(
            listOf(window(1, node(viewId = "app:id/target", text = "确认支付", clickable = true))),
        )
        val result = DefaultVerifiedAccessibilityController(driver).clickNodeVerified(
            selector,
            timeoutMs = 100,
        )

        assertFalse(result.ok)
        assertEquals("SENSITIVE_UI_BLOCKED", result.code)
        assertEquals(0, driver.clickCalls)
    }

    @Test
    fun `otp may be filled but confirmation click is blocked`() = runBlocking {
        val otpField = node(viewId = "app:id/code", text = "", editable = true, password = true)
        val updated = otpField.copy(text = "123456")
        val fillDriver = FakeDriver(
            windows = listOf(
                window(1, otpField, node(id = 2, text = "验证码")),
                window(2, updated, node(id = 2, text = "验证码")),
            ),
            setTextResults = ArrayDeque(listOf(accepted())),
            advanceAfterSetText = true,
        )
        val fill = DefaultVerifiedAccessibilityController(fillDriver).setTextVerified(
            UiNodeSelector(viewId = "app:id/code"),
            "123456",
            timeoutMs = 100,
        )
        assertTrue(fill.ok)

        val confirmDriver = FakeDriver(
            windows = listOf(
                window(
                    1,
                    node(viewId = "app:id/confirm", text = "确认", clickable = true),
                    node(id = 2, text = "验证码"),
                )
            ),
        )
        val click = DefaultVerifiedAccessibilityController(confirmDriver).clickNodeVerified(
            UiNodeSelector(text = "确认"),
            timeoutMs = 100,
        )
        assertFalse(click.ok)
        assertEquals("OTP_CONFIRMATION_REQUIRES_USER", click.code)
        assertEquals(0, confirmDriver.clickCalls)
    }

    @Test
    fun `external cancellation immediately cancels driver wait`() = runBlocking {
        val driver = FakeDriver(listOf(window(1)), suspendWhenExhausted = true)
        val controller = DefaultVerifiedAccessibilityController(driver)
        val job = launch {
            controller.waitForNode(selector, timeoutMs = 30_000)
        }
        yield()
        job.cancelAndJoin()

        assertTrue(driver.waitCancelled)
    }

    @Test
    fun `invalid timeout and scroll count are rejected before driver access`() = runBlocking {
        val driver = FakeDriver(listOf(window(1)))
        val controller = DefaultVerifiedAccessibilityController(driver)

        assertEquals("INVALID_ARGUMENT", controller.waitForNode(selector, timeoutMs = 30_001).code)
        assertEquals(
            "INVALID_ARGUMENT",
            controller.scrollUntil(selector, UiScrollDirection.DOWN, maxScrolls = 21).code,
        )
        assertEquals(0, driver.snapshotCalls)
    }

    private class FakeDriver(
        private val windows: List<UiWindowSnapshot>,
        private val clickResults: ArrayDeque<UiDriverActionResult> = ArrayDeque(),
        private val setTextResults: ArrayDeque<UiDriverActionResult> = ArrayDeque(),
        private val scrollResults: ArrayDeque<UiDriverActionResult> = ArrayDeque(),
        private val advanceAfterSetText: Boolean = false,
        private val advanceAfterScroll: Boolean = false,
        private val suspendWhenExhausted: Boolean = true,
    ) : VerifiedAccessibilityDriver {
        private var index = 0
        var snapshotCalls = 0
        var awaitCalls = 0
        var clickCalls = 0
        var setTextCalls = 0
        var scrollCalls = 0
        var waitCancelled = false

        override fun isServiceAvailable(): Boolean = true

        override suspend fun snapshot(): UiWindowSnapshot? {
            snapshotCalls++
            return windows.getOrNull(index)
        }

        override suspend fun click(selector: UiNodeSelector, nth: Int): UiDriverActionResult {
            clickCalls++
            val result = clickResults.removeFirstOrNull() ?: rejected("ACTION_REJECTED")
            if (result.accepted) advance()
            return result
        }

        override suspend fun setText(
            selector: UiNodeSelector,
            nth: Int,
            text: String,
        ): UiDriverActionResult {
            setTextCalls++
            val result = setTextResults.removeFirstOrNull() ?: rejected("ACTION_REJECTED")
            if (result.accepted && advanceAfterSetText) advance()
            return result
        }

        override suspend fun scroll(
            direction: UiScrollDirection,
            containerSelector: UiNodeSelector?,
        ): UiDriverActionResult {
            scrollCalls++
            val result = scrollResults.removeFirstOrNull() ?: rejected("SCROLL_REJECTED")
            if (result.accepted && advanceAfterScroll) advance()
            return result
        }

        override suspend fun awaitWindowChange(afterVersion: Long) {
            awaitCalls++
            if (windows.getOrNull(index)?.version?.let { it > afterVersion } == true) return
            if (index < windows.lastIndex) {
                advance()
                return
            }
            if (!suspendWhenExhausted) return
            try {
                awaitCancellation()
            } finally {
                waitCancelled = true
            }
        }

        private fun advance() {
            if (index < windows.lastIndex) index++
        }
    }

    private companion object {
        fun accepted() = UiDriverActionResult(true, "ACTION_ACCEPTED", "accepted")
        fun rejected(code: String) = UiDriverActionResult(false, code, "rejected")

        fun window(version: Long, vararg nodes: UiNodeSnapshot) = UiWindowSnapshot(
            version = version,
            packageName = "example.app",
            title = "Example",
            className = "Activity",
            nodes = nodes.toList(),
        )

        fun node(
            id: Int = 1,
            parentId: Int? = null,
            viewId: String? = null,
            text: String? = null,
            clickable: Boolean = false,
            editable: Boolean = false,
            scrollable: Boolean = false,
            password: Boolean = false,
        ) = UiNodeSnapshot(
            traversalId = id,
            parentTraversalId = parentId,
            viewId = viewId,
            text = text,
            contentDescription = null,
            className = "android.view.View",
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            enabled = true,
            password = password,
        )
    }
}
