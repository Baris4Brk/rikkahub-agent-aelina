package me.rerere.rikkahub.data.capability

import me.rerere.rikkahub.data.ai.InvocationSurfacePolicy
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchCapabilityTest {
    @Test
    fun `web search tools are explicitly catalogued for non-keyguard origins`() {
        val capability = CapabilityCatalog.capabilityOf(CapabilityId.WebSearch)

        assertEquals(ImplementationState.Implemented, capability?.implementationState)
        assertEquals(setOf("search_web", "scrape_web"), capability?.toolNames)
        assertEquals(InvocationSurfacePolicy.ALL_NON_KEYGUARD, capability?.allowedOrigins)
        assertFalse(ToolCallOrigin.SystemAssistantKeyguard in capability!!.allowedOrigins)
        assertTrue(CapabilityCatalog.isAvailableFromSystemAssistant("search_web"))
        assertTrue(CapabilityCatalog.isAvailableFromSystemAssistant("scrape_web"))
    }
}
