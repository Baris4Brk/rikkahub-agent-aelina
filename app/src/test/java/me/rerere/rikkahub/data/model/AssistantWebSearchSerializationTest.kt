package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantWebSearchSerializationTest {
    @Test
    fun `individually imported legacy assistant defaults search off`() {
        val assistant = JsonInstant.decodeFromString<Assistant>("""{"name":"legacy"}""")

        assertFalse(assistant.enableWebSearch)
    }

    @Test
    fun `explicit imported assistant search value is preserved`() {
        val assistant = JsonInstant.decodeFromString<Assistant>(
            """{"name":"new","enableWebSearch":true}""",
        )

        assertTrue(assistant.enableWebSearch)
    }
}
