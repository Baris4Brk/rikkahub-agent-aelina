package me.rerere.rikkahub.privilege

import me.rerere.rikkahub.display.DisplayCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ManagedDisplayBridgeWireTest {
    @Test
    fun `valid managed display response preserves only declared capabilities`() {
        val response = ManagedDisplayBridgeWire.decode(
            """
            {
              "ok": true,
              "code": "OK",
              "display_id": 42,
              "capabilities": ["create", "key"]
            }
            """.trimIndent(),
        )

        assertEquals(true, response.ok)
        assertEquals(42, response.displayId)
        assertEquals(
            setOf(DisplayCapability.CREATE, DisplayCapability.KEY),
            response.capabilities,
        )
    }

    @Test
    fun `primary display and malformed capability payloads are rejected`() {
        assertInvalid {
            ManagedDisplayBridgeWire.decode(
                """{"ok":true,"code":"OK","display_id":0,"capabilities":["create"]}""",
            )
        }
        assertInvalid {
            ManagedDisplayBridgeWire.decode(
                """{"ok":true,"code":"OK","display_id":7,"capabilities":["unknown"]}""",
            )
        }
        assertInvalid {
            ManagedDisplayBridgeWire.decode(
                """{"ok":true,"code":"OK","display_id":"7","capabilities":["create"]}""",
            )
        }
    }

    @Test
    fun `wire response requires every security relevant field`() {
        assertInvalid {
            ManagedDisplayBridgeWire.decode(
                """{"ok":false,"code":"DISPLAY_NOT_FOUND","capabilities":[]}""",
            )
        }
    }

    private inline fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected: malformed bridge data must not reach the display runtime.
        }
    }
}
