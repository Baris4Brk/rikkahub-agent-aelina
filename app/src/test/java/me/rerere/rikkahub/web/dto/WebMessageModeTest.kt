package me.rerere.rikkahub.web.dto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebMessageModeTest {
    @Test
    fun `missing mode defaults to queue`() {
        assertEquals(WebMessageMode.QUEUE, parseWebMessageMode(null))
        assertEquals(WebMessageMode.QUEUE, parseWebMessageMode(""))
        assertEquals(WebMessageMode.QUEUE, parseWebMessageMode("   "))
    }

    @Test
    fun `explicit modes are case insensitive and trimmed`() {
        assertEquals(WebMessageMode.QUEUE, parseWebMessageMode(" queue "))
        assertEquals(WebMessageMode.INTERRUPT, parseWebMessageMode("interrupt"))
        assertEquals(WebMessageMode.STEER, parseWebMessageMode("Steer"))
    }

    @Test
    fun `unknown mode is rejected`() {
        assertNull(parseWebMessageMode("cancel"))
    }
}
