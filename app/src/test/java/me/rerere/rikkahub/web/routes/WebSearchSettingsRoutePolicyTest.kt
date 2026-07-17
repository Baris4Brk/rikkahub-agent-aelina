package me.rerere.rikkahub.web.routes

import me.rerere.rikkahub.web.dto.UpdateSearchEnabledRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class WebSearchSettingsRoutePolicyTest {
    @Test
    fun `legacy request without assistant id targets current assistant`() {
        val current = Uuid.random()
        val request = UpdateSearchEnabledRequest(enabled = true)

        assertNull(request.assistantId)
        assertEquals(current, resolveSearchAssistantId(request.assistantId, current))
    }

    @Test
    fun `explicit assistant id wins over current assistant`() {
        val current = Uuid.random()
        val explicit = Uuid.random()

        assertEquals(
            explicit,
            resolveSearchAssistantId(explicit.toString(), current),
        )
    }
}
