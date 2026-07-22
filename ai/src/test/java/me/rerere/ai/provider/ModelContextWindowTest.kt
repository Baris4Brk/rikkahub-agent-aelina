package me.rerere.ai.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelContextWindowTest {
    @Test
    fun `user context window defaults to one million tokens`() {
        assertEquals(1_000_000, Model().userContextWindowTokens)
    }

    @Test
    fun `legacy model data receives the one million token default`() {
        val model = Json.decodeFromString<Model>("""{"modelId":"deeps-v4flash"}""")

        assertEquals(1_000_000, model.userContextWindowTokens)
    }

    @Test
    fun `user can keep a custom context window independent of provider metadata`() {
        val model = Model(
            contextLength = 100_000,
            userContextWindowTokens = 600_000,
        )

        assertEquals(600_000, model.userContextWindowTokens)
        assertEquals(100_000, model.contextLength)
    }
}
