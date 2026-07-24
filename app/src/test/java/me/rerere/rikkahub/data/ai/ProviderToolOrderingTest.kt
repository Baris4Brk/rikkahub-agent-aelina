package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderToolOrderingTest {
    @Test
    fun `provider tools have deterministic name order across task construction order`() {
        fun tool(name: String) = Tool(name = name, description = name, execute = { emptyList() })

        val first = stableProviderToolOrder(listOf(tool("zeta"), tool("alpha"), tool("middle")))
        val second = stableProviderToolOrder(listOf(tool("middle"), tool("zeta"), tool("alpha")))

        assertEquals(listOf("alpha", "middle", "zeta"), first.map { it.name })
        assertEquals(first.map { it.name }, second.map { it.name })
    }
}
