package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantConversationHistoryReadSerializationTest {
    @Test
    fun `legacy assistant defaults cross conversation history read off`() {
        assertFalse(JsonInstant.decodeFromString<Assistant>("""{"name":"legacy"}""").allowConversationHistoryRead)
    }

    @Test
    fun `explicit cross conversation history read opt in survives serialization`() {
        val assistant = Assistant(name = "second", allowConversationHistoryRead = true)
        val restored = JsonInstant.decodeFromString<Assistant>(JsonInstant.encodeToString(assistant))
        assertTrue(restored.allowConversationHistoryRead)
    }
}
