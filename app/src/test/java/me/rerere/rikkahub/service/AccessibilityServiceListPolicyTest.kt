package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceListPolicyTest {
    private val target =
        "me.rerere.rikkahub/me.rerere.rikkahub.service.RikkaAccessibilityService"
    private val shortTarget = "me.rerere.rikkahub/.service.RikkaAccessibilityService"
    private val other = "example.accessibility/example.accessibility.ReaderService"

    @Test
    fun `add preserves other services and appends target`() {
        assertEquals("$other:$target", AccessibilityServiceListPolicy.add(other, target))
    }

    @Test
    fun `add treats short and full class names as the same component`() {
        assertEquals(target, AccessibilityServiceListPolicy.add(shortTarget, target))
    }

    @Test
    fun `add removes duplicate copies of only the target`() {
        assertEquals(
            "$target:$other",
            AccessibilityServiceListPolicy.add("$shortTarget:$target:$other", target),
        )
    }

    @Test
    fun `remove leaves every other component in place`() {
        assertEquals(
            other,
            AccessibilityServiceListPolicy.remove("$other:$shortTarget:$target", target),
        )
    }

    @Test
    fun `null shell output is treated as an empty setting`() {
        assertEquals(target, AccessibilityServiceListPolicy.add("null", target))
        assertFalse(AccessibilityServiceListPolicy.contains("null", target))
        assertTrue(AccessibilityServiceListPolicy.contains(target, shortTarget))
    }
}
